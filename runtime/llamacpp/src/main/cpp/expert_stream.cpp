#include "expert_stream.h"

#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "gguf.h"

#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <cstring>

namespace bram {

namespace {

// Architectures whose routed experts stream as separate blk.<il>.ffn_{gate,up,down}_exps tensors.
// Kept deliberately small to start; a new MoE arch is one row once its expert layout is verified.
const ExpertRecipe kRecipes[] = {
    {"deepseek2", {"ffn_gate_exps", "ffn_up_exps", "ffn_down_exps"}, false},
    {"deepseek4", {"ffn_gate_exps", "ffn_up_exps", "ffn_down_exps"}, false},
    {"qwen3moe", {"ffn_gate_exps", "ffn_up_exps", "ffn_down_exps"}, false},
    {"qwen35moe", {"ffn_gate_exps", "ffn_up_exps", "ffn_down_exps"}, false},
};

} // namespace

const ExpertRecipe * expert_recipe_for(const std::string & arch) {
    for (const auto & r : kRecipes) {
        if (r.arch == arch) return &r;
    }
    return nullptr;
}

GgufOffsetMap::~GgufOffsetMap() {
    for (int fd : fds_) {
        if (fd >= 0) close(fd);
    }
}

bool GgufOffsetMap::load(const std::vector<std::string> & shard_paths, std::string * error) {
    for (size_t s = 0; s < shard_paths.size(); ++s) {
        const std::string & path = shard_paths[s];

        gguf_init_params gp{};
        gp.no_alloc = true;
        gp.ctx = nullptr;
        gguf_context * gc = gguf_init_from_file(path.c_str(), gp);
        if (gc == nullptr) {
            if (error != nullptr) *error = "gguf_init_from_file failed for " + path;
            return false;
        }

        // Absolute data section start for this shard; per-tensor offsets are relative to it.
        const uint64_t data_off = gguf_get_data_offset(gc);
        const int64_t n = gguf_get_n_tensors(gc);
        for (int64_t i = 0; i < n; ++i) {
            const char * name = gguf_get_tensor_name(gc, i);
            if (name == nullptr) continue;
            TensorLoc loc;
            loc.shard = static_cast<int>(s);
            loc.file_offset = data_off + gguf_get_tensor_offset(gc, i);
            loc.nbytes = gguf_get_tensor_size(gc, i);
            offsets_[name] = loc;
        }
        gguf_free(gc);

        const int fd = open(path.c_str(), O_RDONLY);
        if (fd < 0) {
            if (error != nullptr) *error = std::string("open failed for ") + path + ": " + strerror(errno);
            return false;
        }
        fds_.push_back(fd);
    }
    return true;
}

const TensorLoc * GgufOffsetMap::find(const std::string & name) const {
    const auto it = offsets_.find(name);
    return it == offsets_.end() ? nullptr : &it->second;
}

int64_t GgufOffsetMap::read_tensor(const TensorLoc & loc, void * dst) const {
    if (loc.shard < 0 || static_cast<size_t>(loc.shard) >= fds_.size()) return -1;
    const int fd = fds_[loc.shard];

    uint64_t remaining = loc.nbytes;
    uint64_t offset = loc.file_offset;
    char * out = static_cast<char *>(dst);
    while (remaining > 0) {
        const ssize_t r = pread(fd, out, remaining, static_cast<off_t>(offset));
        if (r < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (r == 0) break; // unexpected EOF
        remaining -= static_cast<uint64_t>(r);
        offset += static_cast<uint64_t>(r);
        out += r;
    }
    return static_cast<int64_t>(loc.nbytes - remaining);
}

// ---- ExpertStreamer ----------------------------------------------------------------------------

ExpertStreamer::~ExpertStreamer() {
    disarm_stream();
}

bool ExpertStreamer::init(const std::vector<std::string> & shard_paths, const std::string & arch,
                          std::string * error) {
    recipe_ = expert_recipe_for(arch);
    if (recipe_ == nullptr) {
        if (error != nullptr) *error = "architecture '" + arch + "' is not a streamable MoE";
        return false;
    }
    if (!offsets_.load(shard_paths, error)) {
        recipe_ = nullptr;
        return false;
    }
    return true;
}

bool ExpertStreamer::match_expert(const char * name, int * il, int * suffix_idx) const {
    if (recipe_ == nullptr || name == nullptr) return false;
    // Expect "blk.<il>.<suffix>.weight".
    if (std::strncmp(name, "blk.", 4) != 0) return false;
    const char * p = name + 4;
    char * end = nullptr;
    const long parsed = std::strtol(p, &end, 10);
    if (end == p || *end != '.') return false;
    const char * rest = end + 1; // points at "<suffix>.weight"
    for (size_t i = 0; i < recipe_->exp_suffixes.size(); ++i) {
        const std::string & suffix = recipe_->exp_suffixes[i];
        if (std::strncmp(rest, suffix.c_str(), suffix.size()) == 0 &&
            std::strcmp(rest + suffix.size(), ".weight") == 0) {
            if (il != nullptr) *il = static_cast<int>(parsed);
            if (suffix_idx != nullptr) *suffix_idx = static_cast<int>(i);
            return true;
        }
    }
    return false;
}

bool ExpertStreamer::eval_callback(ggml_tensor * t, bool ask, void * user_data) {
    auto * self = static_cast<ExpertStreamer *>(user_data);
    if (self == nullptr) return false;
    return self->on_eval(t, ask);
}

bool ExpertStreamer::on_eval(ggml_tensor * t, bool ask) {
    if (mode_ == Mode::Off || t == nullptr) return false;

    if (mode_ == Mode::Capture) {
        // Every node is offered in the ask phase; scan its sources for routed-expert weight tensors
        // and record the live ggml_tensor* plus its file location. Observing only — never isolate.
        if (ask) {
            for (int s = 0; s < GGML_MAX_SRC; ++s) {
                ggml_tensor * src = t->src[s];
                if (src == nullptr || src->name[0] == '\0') continue;
                int il = -1;
                int suffix_idx = -1;
                if (match_expert(src->name, &il, &suffix_idx)) {
                    if (captured_.find(src->name) != captured_.end()) continue;
                    Captured c;
                    c.tensor = src;
                    c.il = il;
                    c.suffix_idx = suffix_idx;
                    if (const TensorLoc * loc = offsets_.find(src->name)) c.loc = *loc;
                    captured_[src->name] = c;
                    continue;
                }
                // A contiguous leaf weight that resolves in the gguf but is not a routed expert is
                // an always-used (dense) weight — record it (with its file location) for anon
                // pinning. Inputs and computed tensors carry no gguf name, so the offset-map lookup
                // filters them out; views/non-contiguous are skipped so a file read maps 1:1.
                if (src->op != GGML_OP_NONE || src->view_src != nullptr) continue;
                if (!ggml_is_contiguous(src)) continue;
                if (dense_.find(src->name) != dense_.end()) continue;
                const TensorLoc * loc = offsets_.find(src->name);
                if (loc == nullptr) continue;
                Dense d;
                d.tensor = src;
                d.loc = *loc;
                dense_[src->name] = d;
            }
        }
        return false;
    }

    // Stream: isolate each layer's routing node so its selected expert ids are materialized, then
    // read exactly those experts from flash into the tensor buffers before the expert matmuls run.
    const int il = parse_topk_layer(t->name);
    if (il < 0) return false;         // not a routing node: compute normally, no callback
    if (ask) return true;             // request isolation so the ids are ready in the non-ask call
    stream_layer(il, t);              // non-ask: ids are computed; stream the experts they need
    return false;
}

int ExpertStreamer::parse_topk_layer(const char * name) {
    if (name == nullptr) return -1;
    static const char * kPrefix = "ffn_moe_topk-";
    const size_t plen = std::strlen(kPrefix);
    if (std::strncmp(name, kPrefix, plen) != 0) return -1;
    char * end = nullptr;
    const long il = std::strtol(name + plen, &end, 10);
    if (end == name + plen) return -1;
    return static_cast<int>(il);
}

void ExpertStreamer::stream_layer(int il, const ggml_tensor * topk) {
    const auto it = by_layer_.find(il);
    if (it == by_layer_.end() || topk == nullptr || topk->type != GGML_TYPE_I32) return;

    // The routing node holds the selected expert ids for every token in the batch. Its ->data is a
    // backend-buffer address, not necessarily host-dereferenceable, so copy the ids out with the
    // backend accessor rather than reading ->data directly. ne is graph metadata and safe to read.
    const int64_t n_ids = topk->ne[0] * topk->ne[1] * topk->ne[2] * topk->ne[3];
    if (n_ids <= 0 || n_ids > (1 << 20)) return;
    if (id_scratch_.size() < static_cast<size_t>(n_ids)) id_scratch_.resize(static_cast<size_t>(n_ids));
    ggml_backend_tensor_get(topk, id_scratch_.data(), 0, static_cast<size_t>(n_ids) * sizeof(int32_t));
    const int32_t * ids = id_scratch_.data();

    // Overlap path: hand this layer's routed experts to the background reader lanes and return.
    // The per-expert kernel hook blocks each compute thread until its slice is resident, so the
    // reads run concurrently with the previous layers' compute instead of serially before it.
    // Eviction runs here (a layer boundary, where no expert is mid-compute) rather than in the
    // reader lanes, so a slice is never dropped while a matmul is reading it. See docs/overlap.
    if (overlap_active_) {
        std::lock_guard<std::mutex> lk(mu_);
        evict_to_budget();
        for (Captured * c : it->second) {
            if (c == nullptr || c->buffer == nullptr) continue;
            for (int64_t k = 0; k < n_ids; ++k) {
                const int32_t e = ids[k];
                if (e < 0 || e >= c->n_expert) continue;
                if (c->resident[e]) {
                    const uint64_t key = (static_cast<uint64_t>(c->id) << 24) | static_cast<uint32_t>(e);
                    const auto pit = lru_pos_.find(key);
                    if (pit != lru_pos_.end()) lru_.splice(lru_.begin(), lru_, pit->second);
                    continue;
                }
                enqueue_locked(c, static_cast<int>(e));
            }
        }
        return;
    }

    const uint64_t slices_before = slices_read_;
    for (Captured * c : it->second) {
        if (c == nullptr || c->buffer == nullptr) continue;
        for (int64_t k = 0; k < n_ids; ++k) {
            const int32_t e = ids[k];
            if (e < 0 || e >= c->n_expert) continue;
            const uint64_t key = (static_cast<uint64_t>(c->id) << 24) | static_cast<uint32_t>(e);
            if (c->resident[e]) {
                // Already cached: promote to most-recently-used and skip the read.
                const auto pit = lru_pos_.find(key);
                if (pit != lru_pos_.end()) lru_.splice(lru_.begin(), lru_, pit->second);
                continue;
            }
            const TensorLoc slice{c->loc.shard, c->loc.file_offset + static_cast<uint64_t>(e) * c->expert_stride,
                                  c->expert_stride};
            void * dst = static_cast<uint8_t *>(c->buffer) + static_cast<uint64_t>(e) * c->expert_stride;
            if (offsets_.read_tensor(slice, dst) == static_cast<int64_t>(c->expert_stride)) {
                c->resident[e] = 1;
                bytes_read_ += c->expert_stride;
                ++slices_read_;
                resident_bytes_ += c->expert_stride;
                lru_.push_front(key);
                lru_pos_[key] = lru_.begin();
                // Self-check (opt-in): on a resident model the original mmap bytes are still mapped,
                // so a slice streamed from flash must equal them byte-for-byte. Zero mismatches over
                // a run proves the streaming path reads exactly what the resident model would use.
                // Off by default — the memcmp would page a genuinely-larger-than-RAM model's weights.
                if (verify_ && c->orig_data != nullptr) {
                    const void * ref = static_cast<const uint8_t *>(c->orig_data) +
                                       static_cast<uint64_t>(e) * c->expert_stride;
                    if (memcmp(dst, ref, c->expert_stride) != 0) {
                        if (verify_mismatches_ < 5) {
                            __android_log_print(ANDROID_LOG_ERROR, "BramLlama",
                                "bram_stream: VERIFY MISMATCH il=%d expert=%d stride=%llu", c->il, e,
                                (unsigned long long) c->expert_stride);
                        }
                        ++verify_mismatches_;
                    }
                }
                evict_to_budget();
            }
        }
    }
    // Live summary so the byte-identity gate is visible without an unload: log the first handful of
    // streaming events with running totals, then thin out to once per 2000 slices.
    if (slices_read_ > slices_before) {
        static int logs = 0;
        if (logs < 15 || slices_read_ / 2000 != slices_before / 2000) {
            ++logs;
            __android_log_print(ANDROID_LOG_INFO, "BramLlama",
                "bram_stream: %llu slices streamed (%.2f MiB), %llu mismatches so far",
                (unsigned long long) slices_read_, bytes_read_ / (1024.0 * 1024.0),
                (unsigned long long) verify_mismatches_);
        }
    }
}

void ExpertStreamer::evict_to_budget() {
    if (cache_budget_ == 0) return; // unbounded: keep every streamed expert
    static const uint64_t page = static_cast<uint64_t>(sysconf(_SC_PAGESIZE));
    size_t scanned = 0;
    const size_t lru_size = lru_.size();
    while (resident_bytes_ > cache_budget_ && !lru_.empty() && scanned < lru_size) {
        const uint64_t key = lru_.back();
        lru_.pop_back();
        const int cid = static_cast<int>(key >> 24);
        const int e = static_cast<int>(key & 0xFFFFFF);
        if (cid < 0 || cid >= static_cast<int>(captured_by_id_.size())) { lru_pos_.erase(key); continue; }
        Captured * c = captured_by_id_[cid];
        if (c == nullptr || e < 0 || e >= c->n_expert || !c->resident[e]) { lru_pos_.erase(key); continue; }
        // A slice a reader lane is still filling must not be dropped: keep it in the LRU (at the MRU
        // end so it is reconsidered later) and look further down for an evictable one.
        if (!c->in_flight.empty() && c->in_flight[e]) {
            lru_.push_front(key);
            lru_pos_[key] = lru_.begin();
            ++scanned;
            continue;
        }
        lru_pos_.erase(key);

        // Drop only whole pages fully inside this expert's slice, so an edge page shared with a
        // neighbouring expert is never released (madvise(DONTNEED) zero-fills anonymous pages).
        const uint64_t start = static_cast<uint64_t>(e) * c->expert_stride;
        const uint64_t end = start + c->expert_stride;
        const uint64_t astart = (start + page - 1) & ~(page - 1);
        const uint64_t aend = end & ~(page - 1);
        if (aend > astart) {
            madvise(static_cast<uint8_t *>(c->buffer) + astart, aend - astart, MADV_DONTNEED);
        }
        c->resident[e] = 0;
        resident_bytes_ -= c->expert_stride;
        ++evictions_;
    }
}

// ---- Overlap: background reader lanes + the per-expert wait hook -------------------------------

void ExpertStreamer::expert_ready_trampoline(const ggml_tensor * as, int64_t expert, void * user_data) {
    auto * self = static_cast<ExpertStreamer *>(user_data);
    if (self != nullptr) self->on_expert_ready(as, static_cast<int>(expert));
}

// Read expert e's slice from flash into its buffer slot, then mark it resident and wake waiters.
// Runs with no lock held (the pread is the slow part); only the bookkeeping takes the mutex.
void ExpertStreamer::load_slice(Captured * c, int e) {
    if (c == nullptr || e < 0 || e >= c->n_expert) return;
    const TensorLoc slice{c->loc.shard, c->loc.file_offset + static_cast<uint64_t>(e) * c->expert_stride,
                          c->expert_stride};
    void * dst = static_cast<uint8_t *>(c->buffer) + static_cast<uint64_t>(e) * c->expert_stride;
    const bool ok = offsets_.read_tensor(slice, dst) == static_cast<int64_t>(c->expert_stride);
    uint64_t mism = 0;
    if (ok && verify_ && c->orig_data != nullptr) {
        const void * ref = static_cast<const uint8_t *>(c->orig_data) + static_cast<uint64_t>(e) * c->expert_stride;
        if (memcmp(dst, ref, c->expert_stride) != 0) mism = 1;
    }
    {
        std::lock_guard<std::mutex> lk(mu_);
        c->in_flight[e] = 0;
        if (ok && !c->resident[e]) {
            c->resident[e] = 1;
            bytes_read_ += c->expert_stride;
            ++slices_read_;
            resident_bytes_ += c->expert_stride;
            verify_mismatches_ += mism;
            const uint64_t key = (static_cast<uint64_t>(c->id) << 24) | static_cast<uint32_t>(e);
            lru_.push_front(key);
            lru_pos_[key] = lru_.begin();
        }
    }
    read_cv_.notify_all();
}

void ExpertStreamer::enqueue_locked(Captured * c, int e) {
    if (c == nullptr || e < 0 || e >= c->n_expert) return;
    if (c->resident[e] || c->in_flight[e]) return;
    c->in_flight[e] = 1;
    read_queue_.emplace_back(c, e);
    queue_cv_.notify_one();
}

void ExpertStreamer::reader_loop() {
    for (;;) {
        std::pair<Captured *, int> job;
        {
            std::unique_lock<std::mutex> lk(mu_);
            queue_cv_.wait(lk, [&] { return readers_stop_ || !read_queue_.empty(); });
            if (readers_stop_ && read_queue_.empty()) return;
            job = read_queue_.front();
            read_queue_.pop_front();
        }
        load_slice(job.first, job.second);
    }
}

void ExpertStreamer::start_readers() {
    readers_stop_ = false;
    for (int i = 0; i < overlap_lanes_; ++i) {
        readers_.emplace_back([this] { reader_loop(); });
    }
}

void ExpertStreamer::stop_readers() {
    {
        std::lock_guard<std::mutex> lk(mu_);
        readers_stop_ = true;
    }
    queue_cv_.notify_all();
    for (auto & t : readers_) {
        if (t.joinable()) t.join();
    }
    readers_.clear();
    read_queue_.clear();
}

// Called by every compute thread for each routed expert, just before the kernel reads its weight
// slice. Block until the slice is resident; if it was evicted or never enqueued, read it here.
void ExpertStreamer::on_expert_ready(const ggml_tensor * as, int e) {
    const auto it = by_tensor_.find(as);
    if (it == by_tensor_.end()) return; // not a streamed expert tensor
    Captured * c = it->second;
    if (c == nullptr || e < 0 || e >= c->n_expert) return;

    std::unique_lock<std::mutex> lk(mu_);
    if (c->resident[e]) {
        const uint64_t key = (static_cast<uint64_t>(c->id) << 24) | static_cast<uint32_t>(e);
        const auto pit = lru_pos_.find(key);
        if (pit != lru_pos_.end()) lru_.splice(lru_.begin(), lru_, pit->second);
        return;
    }
    if (c->in_flight[e]) {
        read_cv_.wait(lk, [&] { return c->resident[e]; });
        return;
    }
    // Not resident and no lane is loading it (evicted, or the enqueue was missed): load it now so a
    // compute thread never reads an empty slot. Marked in-flight so a second waiter blocks instead.
    c->in_flight[e] = 1;
    lk.unlock();
    load_slice(c, e);
}

bool ExpertStreamer::arm_stream(std::string * error) {
    if (armed_) return true;
    by_layer_.clear();
    for (auto & kv : captured_) {
        Captured & c = kv.second;
        if (c.tensor == nullptr || c.loc.shard < 0) {
            if (error != nullptr) *error = "an expert tensor did not resolve to a file offset";
            disarm_stream();
            return false;
        }
        c.n_expert = c.tensor->ne[2];
        c.expert_stride = c.tensor->nb[2];
        c.buffer_size = ggml_nbytes(c.tensor);
        // Each expert must be a contiguous slice of expert_stride bytes, and the file tensor must be
        // the whole expert set. If the layout is not what streaming assumes, refuse rather than
        // silently corrupt the run.
        if (c.n_expert <= 0 || c.expert_stride == 0 ||
            c.expert_stride * static_cast<uint64_t>(c.n_expert) != c.buffer_size ||
            c.loc.nbytes != c.buffer_size) {
            if (error != nullptr) *error = "unexpected expert tensor layout; streaming refused";
            disarm_stream();
            return false;
        }
        // Reserve the full tensor's address space anonymously. Physical pages are committed only for
        // experts actually streamed in, so resident memory tracks use, not the tensor's full size.
        void * buf = mmap(nullptr, c.buffer_size, PROT_READ | PROT_WRITE,
                          MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (buf == MAP_FAILED) {
            if (error != nullptr) *error = std::string("mmap for expert buffer failed: ") + strerror(errno);
            disarm_stream();
            return false;
        }
        c.buffer = buf;
        c.orig_data = c.tensor->data;
        c.resident.assign(static_cast<size_t>(c.n_expert), 0);
        c.in_flight.assign(static_cast<size_t>(c.n_expert), 0);
        c.tensor->data = buf;
        c.id = static_cast<int>(captured_by_id_.size());
        captured_by_id_.push_back(&c);
        by_layer_[c.il].push_back(&c);
        by_tensor_[c.tensor] = &c;
    }
    // Keep each layer's tensors in a stable order (gate, up, down) for readable telemetry.
    for (auto & kv : by_layer_) {
        std::sort(kv.second.begin(), kv.second.end(),
                  [](const Captured * a, const Captured * b) { return a->suffix_idx < b->suffix_idx; });
    }

    // Dense-anon: give each always-used weight an anonymous copy the OS will not reclaim, filled by
    // reading the gguf bytes for that tensor (never by dereferencing its mmap ->data, which is not
    // always safe to memcpy). The tensor's ->data is then repointed at the copy. Best effort: a
    // tensor whose size no longer matches the file, or that will not allocate, is left on its mmap.
    if (dense_anon_) {
        dense_bytes_ = 0;
        size_t pinned = 0;
        for (auto & d_kv : dense_) {
            Dense & d = d_kv.second;
            if (d.tensor == nullptr || d.tensor->data == nullptr || d.loc.nbytes == 0) continue;
            if (d.loc.nbytes != ggml_nbytes(d.tensor)) continue; // layout changed since capture
            void * buf = mmap(nullptr, d.loc.nbytes, PROT_READ | PROT_WRITE,
                              MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (buf == MAP_FAILED) continue;
            if (offsets_.read_tensor(d.loc, buf) != static_cast<int64_t>(d.loc.nbytes)) {
                munmap(buf, d.loc.nbytes);
                continue;
            }
            d.buffer = buf;
            d.orig_data = d.tensor->data;
            d.tensor->data = buf;
            dense_bytes_ += d.loc.nbytes;
            ++pinned;
        }
        __android_log_print(ANDROID_LOG_INFO, "BramLlama",
            "bram_stream: dense-anon pinned %zu/%zu weights (%.1f MiB) in RAM",
            pinned, dense_.size(), dense_bytes_ / (1024.0 * 1024.0));
    }

    // Overlap: register the per-expert kernel hook and spin up the reader lanes. Requires the
    // injected ggml_cpu_set_expert_ready_hook; without it, stay on the validated serial path.
    overlap_active_ = false;
    if (overlap_) {
#ifdef BMOE_HAVE_EXPERT_READY_HOOK
        readers_stop_ = false;
        start_readers();
        ggml_cpu_set_expert_ready_hook(&ExpertStreamer::expert_ready_trampoline, this);
        overlap_active_ = true;
        __android_log_print(ANDROID_LOG_INFO, "BramLlama",
            "bram_stream: overlap on, %d reader lanes", overlap_lanes_);
#else
        __android_log_print(ANDROID_LOG_WARN, "BramLlama",
            "bram_stream: overlap requested but expert-ready hook unavailable; serial reads");
#endif
    }

    armed_ = true;
    return true;
}

void ExpertStreamer::disarm_stream() {
    if (armed_) {
        __android_log_print(ANDROID_LOG_WARN, "BramLlama",
            "bram_stream: streamed %llu slices (%.1f MiB read), %llu resident MiB, %llu evictions, %llu mismatches",
            (unsigned long long) slices_read_, bytes_read_ / (1024.0 * 1024.0),
            (unsigned long long) (resident_bytes_ / (1024 * 1024)), (unsigned long long) evictions_,
            (unsigned long long) verify_mismatches_);
    }
    // Stop the reader lanes and pull the kernel hook before any buffer is unmapped, so no lane or
    // compute thread can touch a Captured while it is being torn down.
    if (overlap_active_) {
#ifdef BMOE_HAVE_EXPERT_READY_HOOK
        ggml_cpu_set_expert_ready_hook(nullptr, nullptr);
#endif
        stop_readers();
        overlap_active_ = false;
    }
    for (auto & kv : captured_) {
        Captured & c = kv.second;
        if (c.buffer != nullptr) {
            if (c.tensor != nullptr && c.orig_data != nullptr) c.tensor->data = c.orig_data;
            munmap(c.buffer, c.buffer_size);
            c.buffer = nullptr;
            c.orig_data = nullptr;
            c.resident.clear();
            c.in_flight.clear();
        }
    }
    by_tensor_.clear();
    for (auto & kv : dense_) {
        Dense & d = kv.second;
        if (d.buffer != nullptr) {
            if (d.tensor != nullptr && d.orig_data != nullptr) d.tensor->data = d.orig_data;
            munmap(d.buffer, d.loc.nbytes);
            d.buffer = nullptr;
            d.orig_data = nullptr;
        }
    }
    by_layer_.clear();
    captured_by_id_.clear();
    lru_.clear();
    lru_pos_.clear();
    resident_bytes_ = 0;
    dense_bytes_ = 0;
    armed_ = false;
}

size_t ExpertStreamer::resolved_count() const {
    size_t n = 0;
    for (const auto & kv : captured_) {
        if (kv.second.loc.shard >= 0 && kv.second.loc.nbytes > 0) ++n;
    }
    return n;
}

uint64_t ExpertStreamer::captured_bytes() const {
    uint64_t total = 0;
    for (const auto & kv : captured_) total += kv.second.loc.nbytes;
    return total;
}

} // namespace bram
