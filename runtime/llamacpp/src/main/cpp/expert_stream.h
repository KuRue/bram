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

#include <cstdint>
#include <string>
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

    // Resolve the recipe for `arch` and build the offset map from the model's shard paths (split
    // order). Returns false and sets *error if the arch is not a streamable MoE or a shard fails.
    bool init(const std::vector<std::string> & shard_paths, const std::string & arch, std::string * error);

    void set_mode(Mode m) { mode_ = m; }
    Mode mode() const { return mode_; }
    bool ready() const { return recipe_ != nullptr; }

    // Trampoline to install as llama_context_params.cb_eval, with `this` as cb_eval_user_data.
    static bool eval_callback(ggml_tensor * t, bool ask, void * user_data);

    size_t captured_count() const { return captured_.size(); }
    // Captured tensors whose byte location resolved in the offset map (should equal captured_count()).
    size_t resolved_count() const;
    // Total expert bytes referenced by the captured tensors (whole model, not per token).
    uint64_t captured_bytes() const;
    size_t shard_count() const { return offsets_.shard_count(); }

private:
    bool on_eval(ggml_tensor * t, bool ask);
    // If `name` is "blk.<il>.<suffix>.weight" for one of the recipe's expert suffixes, fill *il and
    // *suffix_idx and return true.
    bool match_expert(const char * name, int * il, int * suffix_idx) const;

    struct Captured {
        ggml_tensor * tensor = nullptr;
        TensorLoc loc;   // loc.shard < 0 when the name did not resolve in the offset map
        int il = -1;
        int suffix_idx = -1;
    };

    Mode mode_ = Mode::Off;
    const ExpertRecipe * recipe_ = nullptr;
    GgufOffsetMap offsets_;
    std::unordered_map<std::string, Captured> captured_;
};

} // namespace bram
