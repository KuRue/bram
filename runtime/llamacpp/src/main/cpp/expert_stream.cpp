#include "expert_stream.h"

#include "gguf.h"

#include <fcntl.h>
#include <unistd.h>

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
                if (!match_expert(src->name, &il, &suffix_idx)) continue;
                if (captured_.find(src->name) != captured_.end()) continue;
                Captured c;
                c.tensor = src;
                c.il = il;
                c.suffix_idx = suffix_idx;
                if (const TensorLoc * loc = offsets_.find(src->name)) c.loc = *loc;
                captured_[src->name] = c;
            }
        }
        return false;
    }

    // Mode::Stream is added in P2.
    return false;
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
