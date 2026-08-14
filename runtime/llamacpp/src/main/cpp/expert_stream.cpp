#include "expert_stream.h"

#include "gguf.h"

#include <fcntl.h>
#include <unistd.h>

#include <cerrno>
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

} // namespace bram
