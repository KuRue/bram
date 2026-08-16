#pragma once

// MoE expert streaming for models larger than device RAM.
//
// A Mixture-of-Experts model is mostly "experts", but each token routes to only a few of them.
// Instead of resident-loading the whole file (which thrashes the page cache and thermally stalls
// once the model is several times RAM), this module reads just the experts each token asks for,
// straight from the gguf shards at their known byte offsets, and rebinds the live expert tensors'
// data pointers at them. The technique rides entirely on llama.cpp's public API — no fork for the
// serial path. See docs and the seam contract before changing the hot path.
//
// P0 (this file): resolve every gguf tensor to (shard, offset, nbytes) without loading data, and
// know which tensors are the streamable experts for a given architecture. Later phases add the
// cb_eval capture warm-up, the serial slice reads, the hot-expert cache, and the overlap hook.

#include "ggml.h"

#include <condition_variable>
#include <cstdint>
#include <deque>
#include <list>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace bram {

// The expert weight tensors an architecture exposes, by their per-block name suffix. A non-fused
// MoE keeps gate/up/down as separate expert tensors; some architectures fuse gate+up into one.
struct ExpertRecipe {
    std::string arch;
    std::vector<std::string> exp_suffixes;
    bool fused = false;
};

// The recipe for a `general.architecture`, or nullptr when the arch is not a streamable MoE.
const ExpertRecipe * expert_recipe_for(const std::string & arch);

// Where a tensor's raw bytes live inside a (possibly multi-shard) gguf model.
struct TensorLoc {
    int shard = -1;            // index into the shard list passed to load()
    uint64_t file_offset = 0;  // absolute byte offset within that shard file
    uint64_t nbytes = 0;       // tensor size in bytes
};

// Resolves gguf tensor names to their byte location across one or more shard files and serves
// positioned reads from them. Only the gguf headers are parsed (no_alloc), never tensor data.
class GgufOffsetMap {
public:
    GgufOffsetMap() = default;
    ~GgufOffsetMap();
    GgufOffsetMap(const GgufOffsetMap &) = delete;
    GgufOffsetMap & operator=(const GgufOffsetMap &) = delete;

    // Parse each shard's header and build the name -> location map, opening one fd per shard for
    // positioned reads. `shard_paths` must be in split order (00001..0000N). Returns false and
    // sets *error on the first shard that cannot be parsed or opened.
    bool load(const std::vector<std::string> & shard_paths, std::string * error);

    // Exact-name lookup; nullptr when the tensor is absent.
    const TensorLoc * find(const std::string & name) const;

    // Read loc.nbytes into dst from the tensor's shard at its offset. Returns bytes read (== nbytes
    // on success) or -1 on error. Positional (pread), so concurrent reads on the shared fd are safe.
    int64_t read_tensor(const TensorLoc & loc, void * dst) const;

    size_t tensor_count() const { return offsets_.size(); }
    size_t shard_count() const { return fds_.size(); }

private:
    std::vector<int> fds_;
    std::unordered_map<std::string, TensorLoc> offsets_;
};

// Drives MoE expert streaming for one loaded model. Installed as the context's cb_eval callback.
//
// Lifecycle:
//   init()      once after the model loads: resolve the arch recipe and build the offset map.
//   Capture     one warm-up decode with mode Capture: record the live expert ggml_tensor* of every
//               routed-expert weight the graph references, and resolve each to its file location.
//   Stream      real generation with mode Stream: for each ffn_moe_topk-<il> node, read the
//               selected experts from flash and rebind their tensors' ->data (added in P2).
//   Off         behaves exactly like stock llama.cpp (the callback is a no-op).
class ExpertStreamer {
public:
    enum class Mode { Off, Capture, Stream };

    ExpertStreamer() = default;
    ~ExpertStreamer();

    // Resolve the recipe for `arch` and build the offset map from the model's shard paths (split
    // order). Returns false and sets *error if the arch is not a streamable MoE or a shard fails.
    bool init(const std::vector<std::string> & shard_paths, const std::string & arch, std::string * error);

    void set_mode(Mode m) { mode_ = m; }
    Mode mode() const { return mode_; }
    bool ready() const { return recipe_ != nullptr; }
    bool armed() const { return armed_; }
    // Byte-for-byte compare each streamed slice against the resident mmap (dev gate; pages weights).
    void set_verify(bool v) { verify_ = v; }
    uint64_t verify_mismatches() const { return verify_mismatches_; }
    // Cap resident expert memory to `bytes`; 0 means unbounded (keep every streamed expert). When
    // the cap is exceeded the least-recently-used experts are evicted (madvise DONTNEED).
    void set_cache_budget(uint64_t bytes) { cache_budget_ = bytes; }
    uint64_t resident_bytes() const { return resident_bytes_; }
    uint64_t evictions() const { return evictions_; }
    // Pin the always-used (non-expert) weights in anonymous memory at arm time so the OS cannot
    // reclaim them mid-generation the way it drops file-backed mmap pages under pressure.
    void set_dense_anon(bool on) { dense_anon_ = on; }
    uint64_t dense_bytes() const { return dense_bytes_; }
    size_t dense_count() const { return dense_.size(); }
    // Overlap expert reads with expert compute: the topk callback enqueues a layer's experts to
    // background reader lanes (non-blocking) and the kernel's per-expert hook blocks until each
    // slice is resident. `lanes` reader threads; 0 lanes keeps the serial path. Needs the injected
    // ggml_cpu_set_expert_ready_hook; falls back to serial (with a log) if the hook is absent.
    void set_overlap(bool on, int lanes) { overlap_ = on; overlap_lanes_ = lanes > 0 ? lanes : 8; }
    // The ggml-cpu expert-ready hook trampoline; registered while armed with overlap on.
    static void expert_ready_trampoline(const ggml_tensor * as, int64_t expert, void * user_data);
    // The batch-prefetch trampoline: fires once per MoE matmul with the routed-row counts, so the
    // whole layer's experts can be enqueued to reader lanes before the compute loop.
    static void expert_batch_trampoline(const ggml_tensor * as, const int64_t * counts, int64_t n_as,
                                        void * user_data);

    // Trampoline to install as llama_context_params.cb_eval, with `this` as cb_eval_user_data.
    static bool eval_callback(ggml_tensor * t, bool ask, void * user_data);

    // After capture: reserve a full-tensor anonymous buffer per expert tensor and repoint each
    // tensor's ->data at it, so routed experts can be streamed into position on demand. Returns
    // false if any resolution/allocation failed (streaming stays off, tensors keep their mmap data).
    bool arm_stream(std::string * error);
    // Restore every expert tensor's original ->data and release the buffers. Safe if not armed.
    void disarm_stream();
    // Debug isolation: after arming, fill EVERY expert's anon buffer from its mmap ->data (memcpy)
    // and mark it resident, so the experts are anon-backed with correct bytes but static — no
    // per-token streaming and no cb_eval graph splits. Separates "rebind to anon" from "streaming".
    void fill_all_from_mmap();

    size_t captured_count() const { return captured_.size(); }
    // Captured tensors whose byte location resolved in the offset map (should equal captured_count()).
    size_t resolved_count() const;
    // Total expert bytes referenced by the captured tensors (whole model, not per token).
    uint64_t captured_bytes() const;
    size_t shard_count() const { return offsets_.shard_count(); }
    // Bytes read from flash so far (this run), and the number of expert-slice reads served.
    uint64_t bytes_read() const { return bytes_read_; }
    uint64_t slices_read() const { return slices_read_; }

private:
    bool on_eval(ggml_tensor * t, bool ask);
    // If `name` is "blk.<il>.<suffix>.weight" for one of the recipe's expert suffixes, fill *il and
    // *suffix_idx and return true.
    bool match_expert(const char * name, int * il, int * suffix_idx) const;
    // Parse the block index out of a routing node name "ffn_moe_topk-<il>". Returns -1 on mismatch.
    static int parse_topk_layer(const char * name);
    // Stream every routed expert for layer `il` that is not already resident, reading each expert's
    // slice from flash into its slot in the tensor buffer.
    void stream_layer(int il, const ggml_tensor * topk);

    struct Captured {
        ggml_tensor * tensor = nullptr;
        TensorLoc loc;   // loc.shard < 0 when the name did not resolve in the offset map
        int il = -1;
        int suffix_idx = -1;
        int id = -1;     // dense index into captured_by_id_, for compact LRU keys
        // Streaming buffer (full-tensor anonymous mmap) that ->data is repointed at while armed.
        void * buffer = nullptr;
        void * orig_data = nullptr;    // the mmap-backed ->data to restore on disarm
        uint64_t buffer_size = 0;
        int64_t n_expert = 0;          // tensor->ne[2]
        uint64_t expert_stride = 0;    // tensor->nb[2]: one expert's byte span
        std::vector<uint8_t> resident; // 1 once expert e's slice has been read into the buffer
        std::vector<uint8_t> in_flight; // 1 while a reader lane is loading expert e (overlap mode)
    };

    Mode mode_ = Mode::Off;
    bool armed_ = false;
    bool verify_ = false;
    bool dense_anon_ = false;
    const ExpertRecipe * recipe_ = nullptr;
    GgufOffsetMap offsets_;
    std::unordered_map<std::string, Captured> captured_;

    // A non-expert model weight, pinned in anonymous memory (filled by pread from the gguf, never
    // by reading the tensor's mmap ->data). loc gives its file bytes; buffer replaces ->data.
    struct Dense {
        ggml_tensor * tensor = nullptr;
        TensorLoc loc;
        void * buffer = nullptr;
        void * orig_data = nullptr;
    };
    std::unordered_map<std::string, Dense> dense_;
    uint64_t dense_bytes_ = 0;

    // Expert tensors grouped by block, in recipe-suffix order, for quick lookup from a topk node.
    std::unordered_map<int, std::vector<Captured *>> by_layer_;
    std::vector<Captured *> captured_by_id_;   // id -> Captured, for LRU key decode
    std::vector<int32_t> id_scratch_;   // reused host buffer for the routing node's expert ids

    // LRU cache of resident expert slices. Keys pack (captured id, expert index); the list is MRU
    // at the front, and iterators in the map give O(1) touch/evict. Physical pages for an evicted
    // expert are dropped with madvise(DONTNEED); the slice re-streams if the router asks again.
    void evict_to_budget();
    std::list<uint64_t> lru_;
    std::unordered_map<uint64_t, std::list<uint64_t>::iterator> lru_pos_;
    uint64_t cache_budget_ = 0;
    uint64_t resident_bytes_ = 0;
    uint64_t evictions_ = 0;

    uint64_t bytes_read_ = 0;
    uint64_t slices_read_ = 0;
    uint64_t verify_mismatches_ = 0;    // resident-vs-streamed byte mismatches (should stay 0)

    // --- Overlap: background reader lanes + the per-expert wait hook -----------------------------
    // One coarse mutex guards resident/in_flight, the LRU cache, and the byte counters; the slow
    // pread itself happens unlocked, so lanes read concurrently. read_cv_ signals waiters that an
    // expert became resident; queue_cv_ wakes reader lanes when work arrives.
    void start_readers();
    void stop_readers();
    void reader_loop();
    // Enqueue expert `e` of `c` for a lane if not resident/in-flight (caller holds mu_).
    void enqueue_locked(Captured * c, int e);
    // Read expert e of c from flash into its buffer slot (no lock held), then mark resident.
    void load_slice(Captured * c, int e);
    void on_expert_ready(const ggml_tensor * as, int e);
    // Prefetch: enqueue every routed expert (counts[e] > 0) of tensor `as` AND its gate/up/down
    // layer siblings (they share expert indices) to the reader lanes, before the compute loop.
    void on_expert_batch(const ggml_tensor * as, const int64_t * counts, int64_t n_as);

    bool overlap_ = false;
    int overlap_lanes_ = 8;
    bool overlap_active_ = false;             // (legacy) reader-lane prefetch; unused in hook-driven path
    bool hook_active_ = false;                // true while the ggml_cpu expert-ready hook is registered
    std::unordered_map<const ggml_tensor *, Captured *> by_tensor_;  // for the hook to find a Captured
    std::mutex mu_;
    std::condition_variable read_cv_;         // an expert became resident
    std::condition_variable queue_cv_;        // work arrived / shutting down
    std::deque<std::pair<Captured *, int>> read_queue_;
    std::vector<std::thread> readers_;
    bool readers_stop_ = false;
};

} // namespace bram
