#include <jni.h>

#include <android/log.h>

#include "llama.h"
#include "chat.h"
#include <deque>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

struct runtime_state {
    llama_model * model = nullptr;
    common_chat_templates_ptr chat_templates;
    // Captured when the prompt is built so the reply can be parsed with the same format, which is
    // what lets reasoning be separated from the answer instead of guessed at with tag matching.
    common_chat_params last_chat_params;
    int context_tokens = 0;
    int batch_tokens = 0;
    int threads = 0;
    int gpu_layers = 0;
    bool enable_thinking = false;
    std::string model_path;

    // The chat context outlives a single turn so its KV cache can be reused. Rebuilding it every
    // turn meant re-decoding the whole conversation each time, which grows without bound: at the
    // measured prompt speed a 2,000-token thread spent about two minutes before its first token.
    llama_context * chat_context = nullptr;
    // Exactly the tokens the KV cache holds, in order, so the next prompt can be matched against
    // it. Includes the tokens generated in previous turns, which the chat template will re-render
    // as part of the next prompt.
    std::vector<llama_token> cached_tokens;
};

// Fixed prompt for the cross-backend correctness comparison. Changing it invalidates every
// recorded CPU reference, so treat it as part of the validation contract.
constexpr const char * kReferencePrompt = "List the first five prime numbers in order.";

runtime_state g_state;
std::mutex g_mutex;
std::once_flag g_backend_once;
std::atomic_bool g_cancelled{false};

// Recent native log lines, kept so load failures can surface llama.cpp's actual reason.
std::mutex g_log_mutex;
std::deque<std::string> g_recent_log;

void forward_llama_log(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr) return;
    int priority = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: priority = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN: priority = ANDROID_LOG_WARN; break;
        case GGML_LOG_LEVEL_DEBUG: priority = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    __android_log_write(priority, "BramLlama", text);
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        g_recent_log.emplace_back(text);
        while (g_recent_log.size() > 8) g_recent_log.pop_front();
    }
}

std::string drain_recent_log() {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    std::string joined;
    for (const std::string & line : g_recent_log) {
        std::string trimmed = line;
        while (!trimmed.empty() && (trimmed.back() == '\n' || trimmed.back() == '\r')) trimmed.pop_back();
        if (trimmed.empty()) continue;
        if (!joined.empty()) joined += " | ";
        joined += trimmed;
    }
    g_recent_log.clear();
    return joined;
}

void ensure_backend() {
    std::call_once(g_backend_once, [] {
        llama_log_set(forward_llama_log, nullptr);
        llama_backend_init();
    });
}

void throw_java(JNIEnv * env, const std::string & message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) {
        env->ThrowNew(type, message.c_str());
    }
}

std::string json_escape(const std::string & value) {
    std::ostringstream out;
    for (unsigned char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) {
                    const char * hex = "0123456789abcdef";
                    out << "\\u00" << hex[(c >> 4) & 0xf] << hex[c & 0xf];
                } else {
                    out << static_cast<char>(c);
                }
        }
    }
    return out.str();
}

void append_utf8(std::string & out, uint32_t codepoint) {
    if (codepoint <= 0x7f) {
        out.push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
        out.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0xffff) {
        out.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else {
        out.push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        out.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    }
}

std::string from_jstring(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) throw std::runtime_error("Could not read Java string");
    std::string result;
    result.reserve(static_cast<size_t>(length) * 2);
    for (jsize index = 0; index < length; ++index) {
        uint32_t codepoint = chars[index];
        if (codepoint >= 0xd800 && codepoint <= 0xdbff && index + 1 < length) {
            const uint32_t low = chars[index + 1];
            if (low >= 0xdc00 && low <= 0xdfff) {
                codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (low - 0xdc00);
                ++index;
            }
        }
        append_utf8(result, codepoint);
    }
    env->ReleaseStringChars(value, chars);
    return result;
}

jstring to_jstring(JNIEnv * env, const std::string & value) {
    std::vector<jchar> utf16;
    utf16.reserve(value.size());
    for (size_t index = 0; index < value.size();) {
        const uint8_t first = static_cast<uint8_t>(value[index]);
        uint32_t codepoint = 0xfffd;
        size_t count = 1;
        if (first < 0x80) {
            codepoint = first;
        } else if ((first & 0xe0) == 0xc0 && index + 1 < value.size()) {
            codepoint = ((first & 0x1f) << 6) | (static_cast<uint8_t>(value[index + 1]) & 0x3f);
            count = 2;
        } else if ((first & 0xf0) == 0xe0 && index + 2 < value.size()) {
            codepoint = ((first & 0x0f) << 12) |
                ((static_cast<uint8_t>(value[index + 1]) & 0x3f) << 6) |
                (static_cast<uint8_t>(value[index + 2]) & 0x3f);
            count = 3;
        } else if ((first & 0xf8) == 0xf0 && index + 3 < value.size()) {
            codepoint = ((first & 0x07) << 18) |
                ((static_cast<uint8_t>(value[index + 1]) & 0x3f) << 12) |
                ((static_cast<uint8_t>(value[index + 2]) & 0x3f) << 6) |
                (static_cast<uint8_t>(value[index + 3]) & 0x3f);
            count = 4;
        }
        index += count;
        if (codepoint <= 0xffff) {
            utf16.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xd800 + (codepoint >> 10)));
            utf16.push_back(static_cast<jchar>(0xdc00 + (codepoint & 0x3ff)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

bool is_complete_utf8(const std::string & value) {
    for (size_t index = 0; index < value.size();) {
        const uint8_t first = static_cast<uint8_t>(value[index]);
        size_t count = 0;
        if (first < 0x80) count = 1;
        else if ((first & 0xe0) == 0xc0) count = 2;
        else if ((first & 0xf0) == 0xe0) count = 3;
        else if ((first & 0xf8) == 0xf0) count = 4;
        else return false;
        if (index + count > value.size()) return false;
        for (size_t continuation = 1; continuation < count; ++continuation) {
            if ((static_cast<uint8_t>(value[index + continuation]) & 0xc0) != 0x80) return false;
        }
        index += count;
    }
    return true;
}

std::vector<llama_token> tokenize(const std::string & prompt) {
    if (g_state.model == nullptr) throw std::runtime_error("No model is loaded");
    const llama_vocab * vocab = llama_model_get_vocab(g_state.model);
    const int32_t required = -llama_tokenize(
        vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (required <= 0) throw std::runtime_error("Model tokenizer rejected the prompt");
    std::vector<llama_token> tokens(static_cast<size_t>(required));
    const int32_t actual = llama_tokenize(
        vocab,
        prompt.data(),
        static_cast<int32_t>(prompt.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,
        true);
    if (actual < 0) throw std::runtime_error("Model tokenizer failed");
    tokens.resize(static_cast<size_t>(actual));
    return tokens;
}

bool abort_callback(void *) {
    return g_cancelled.load(std::memory_order_relaxed);
}

llama_context * create_context(int context_tokens = 0) {
    llama_context_params params = llama_context_default_params();
    params.n_ctx = static_cast<uint32_t>(context_tokens > 0 ? context_tokens : g_state.context_tokens);
    params.n_batch = static_cast<uint32_t>(std::min(g_state.batch_tokens, static_cast<int>(params.n_ctx)));
    params.n_ubatch = static_cast<uint32_t>(std::min(128, static_cast<int>(params.n_batch)));
    params.n_threads = g_state.threads;
    params.n_threads_batch = g_state.threads;
    params.no_perf = false;
    params.abort_callback = abort_callback;
    llama_context * context = llama_init_from_model(g_state.model, params);
    if (context == nullptr) throw std::runtime_error("Could not allocate the requested model context");
    return context;
}

/** Drops the reusable chat context, so the next turn starts from an empty cache. */
void release_chat_context() {
    if (g_state.chat_context != nullptr) {
        llama_free(g_state.chat_context);
        g_state.chat_context = nullptr;
    }
    g_state.cached_tokens.clear();
}

/**
 * How much of [tokens] the cache already holds, counted from the start.
 *
 * Capped one short of the prompt so there is always a token left to decode: llama.cpp produces
 * logits from decoding, so a fully cached prompt would leave nothing to sample from.
 */
size_t reusable_prefix(const std::vector<llama_token> & tokens) {
    const size_t limit = std::min(g_state.cached_tokens.size(), tokens.size());
    size_t shared = 0;
    while (shared < limit && g_state.cached_tokens[shared] == tokens[shared]) ++shared;
    if (shared >= tokens.size() && shared > 0) --shared;
    return shared;
}

void decode_prompt(llama_context * context, const std::vector<llama_token> & tokens) {
    size_t offset = 0;
    while (offset < tokens.size()) {
        if (g_cancelled.load(std::memory_order_relaxed)) throw std::runtime_error("Generation cancelled");
        const int count = std::min(
            static_cast<int>(tokens.size() - offset),
            g_state.batch_tokens);
        llama_batch batch = llama_batch_get_one(
            const_cast<llama_token *>(tokens.data() + offset), count);
        const int result = llama_decode(context, batch);
        if (result != 0) {
            if (g_cancelled.load(std::memory_order_relaxed)) throw std::runtime_error("Generation cancelled");
            throw std::runtime_error("llama.cpp failed while processing the prompt (code " + std::to_string(result) + ")");
        }
        offset += static_cast<size_t>(count);
    }
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    std::vector<char> buffer(256);
    int32_t count = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    if (count < 0) {
        buffer.resize(static_cast<size_t>(-count));
        count = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    }
    if (count < 0) throw std::runtime_error("Could not decode an output token");
    return {buffer.data(), static_cast<size_t>(count)};
}

/** The string value of the key whose quoted name starts at [key], with escapes undone. */
std::string json_field(const std::string & source, size_t key) {
    const size_t colon = source.find(':', key);
    if (colon == std::string::npos) return {};
    const size_t open = source.find('"', colon);
    if (open == std::string::npos) return {};
    std::string value;
    for (size_t index = open + 1; index < source.size(); ++index) {
        const char character = source[index];
        if (character == '\\' && index + 1 < source.size()) {
            const char escaped = source[++index];
            switch (escaped) {
                case 'n': value += '\n'; break;
                case 't': value += '\t'; break;
                case 'r': value += '\r'; break;
                default: value += escaped; break;
            }
            continue;
        }
        if (character == '"') break;
        value += character;
    }
    return value;
}

/** The whole object value of the key whose quoted name starts at [key], braces included. */
std::string json_object_at(const std::string & source, size_t key) {
    const size_t open = source.find('{', key);
    if (open == std::string::npos) return "{}";
    int depth = 0;
    bool in_string = false;
    for (size_t index = open; index < source.size(); ++index) {
        const char character = source[index];
        if (in_string) {
            if (character == '\\') ++index;
            else if (character == '"') in_string = false;
            continue;
        }
        if (character == '"') in_string = true;
        else if (character == '{') ++depth;
        else if (character == '}' && --depth == 0) return source.substr(open, index - open + 1);
    }
    return "{}";
}

/**
 * Parses the tool list the app sends into what the template engine expects.
 *
 * Each entry is `{"name","description","parameters"}`, where parameters is the JSON schema as a
 * string — llama.cpp wants the schema unparsed, since it feeds it to the grammar builder.
 */
std::vector<common_chat_tool> parse_tools(const std::string & tools_json) {
    std::vector<common_chat_tool> tools;
    if (tools_json.empty()) return tools;
    // The app has already validated this JSON; what arrives is an array of flat objects whose
    // only nested value is the schema. Reading it with the string helpers keeps this file free of
    // a JSON dependency that the vendored headers only forward-declare here.
    size_t cursor = 0;
    while (true) {
        const size_t name = tools_json.find("\"name\"", cursor);
        if (name == std::string::npos) break;
        common_chat_tool tool;
        tool.name = json_field(tools_json, name);
        const size_t description = tools_json.find("\"description\"", name);
        tool.description = description == std::string::npos ? "" : json_field(tools_json, description);
        const size_t parameters = tools_json.find("\"parameters\"", name);
        tool.parameters = parameters == std::string::npos ? "{}" : json_object_at(tools_json, parameters);
        cursor = parameters == std::string::npos ? name + 6 : parameters + 12;
        if (!tool.name.empty()) tools.push_back(std::move(tool));
    }
    return tools;
}

std::string apply_chat_template(
    const std::vector<std::string> & roles,
    const std::vector<std::string> & contents,
    bool add_assistant,
    const std::string & tools_json,
    bool require_tool) {
    if (g_state.model == nullptr) throw std::runtime_error("No model is loaded");
    if (!g_state.chat_templates) {
        throw std::runtime_error("This GGUF does not contain a usable chat template");
    }
    common_chat_templates_inputs inputs;
    inputs.messages.reserve(roles.size());
    for (size_t index = 0; index < roles.size(); ++index) {
        common_chat_msg message;
        message.role = roles[index];
        message.content = contents[index];
        inputs.messages.push_back(std::move(message));
    }
    inputs.add_generation_prompt = add_assistant;
    inputs.enable_thinking = g_state.enable_thinking;
    inputs.tools = parse_tools(tools_json);
    // Required tool choice makes the grammar eager rather than lazy, so the model cannot answer in
    // prose instead of calling. Used only for a retry, never for a first attempt, since a run that
    // must call a tool cannot say it has nothing to do.
    inputs.tool_choice = require_tool
        ? COMMON_CHAT_TOOL_CHOICE_REQUIRED
        : COMMON_CHAT_TOOL_CHOICE_AUTO;

    // Prefer the model's own Jinja template, but do not let a template the engine cannot render
    // take chat down with it. Some published templates use constructs minja does not implement
    // (Qwen3.5 iterates messages[::-1], which yields nothing and then raises "No user query found
    // in messages"). llama.cpp's built-in templates handle the common formats, so fall back to
    // those rather than refusing to talk to the model at all.
    inputs.use_jinja = true;
    try {
        g_state.last_chat_params = common_chat_templates_apply(g_state.chat_templates.get(), inputs);
    } catch (const std::exception & jinja_error) {
        __android_log_print(ANDROID_LOG_WARN, "BramLlama",
            "chat template: jinja render failed (%s); falling back to the built-in template",
            jinja_error.what());
        inputs.use_jinja = false;
        g_state.last_chat_params = common_chat_templates_apply(g_state.chat_templates.get(), inputs);
    }
    return g_state.last_chat_params.prompt;
}

void unload_locked() {
    g_cancelled.store(true, std::memory_order_relaxed);
    release_chat_context();
    g_state.chat_templates.reset();
    if (g_state.model != nullptr) llama_model_free(g_state.model);
    g_state = {};
}

template <typename Function>
jstring guarded_string(JNIEnv * env, Function && function) {
    try {
        return to_jstring(env, function());
    } catch (const std::exception & error) {
        throw_java(env, error.what());
        return nullptr;
    }
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_probe(
    JNIEnv * env, jobject) {
    return guarded_string(env, [] {
        ensure_backend();
        return std::string("{\"nativeRuntimeLinked\":true,\"cpuValidated\":false,\"systemInfo\":\"") +
            json_escape(llama_print_system_info()) + "\"}";
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_load(
    JNIEnv * env, jobject, jstring path, jint context_tokens, jint batch_tokens, jint threads,
    jint gpu_layers, jstring device_filter, jboolean enable_thinking) {
    return guarded_string(env, [&] {
        std::lock_guard<std::mutex> lock(g_mutex);
        ensure_backend();
        unload_locked();
        g_cancelled.store(false, std::memory_order_relaxed);
        const std::string model_path = from_jstring(env, path);
        llama_model_params params = llama_model_default_params();
        params.n_gpu_layers = gpu_layers;

        // Without an explicit list llama.cpp offloads to whichever accelerator it considers best,
        // which makes "validate the NPU" ambiguous on a device that also exposes a GPU. When a
        // filter is supplied, restrict offload to devices whose name matches it. The vector must
        // outlive the load call because llama_model_params only borrows the pointer.
        const std::string filter = device_filter == nullptr ? std::string() : from_jstring(env, device_filter);
        std::vector<ggml_backend_dev_t> selected;
        if (filter.empty()) {
            // An empty filter means CPU. Leaving params.devices null lets llama.cpp enumerate every
            // registered backend, so a GPU whose driver cannot create a device fails the load even
            // though no offload was requested. An explicitly empty list keeps CPU loads independent
            // of accelerator health.
            selected.push_back(nullptr);
            params.devices = selected.data();
        } else {
            const size_t count = ggml_backend_dev_count();
            for (size_t index = 0; index < count; ++index) {
                ggml_backend_dev_t device = ggml_backend_dev_get(index);
                if (device == nullptr) continue;
                const char * name = ggml_backend_dev_name(device);
                if (name != nullptr && std::string(name).rfind(filter, 0) == 0) {
                    selected.push_back(device);
                }
            }
            if (selected.empty()) {
                throw std::runtime_error("No backend device matches '" + filter + "' on this build");
            }
            selected.push_back(nullptr);  // llama.cpp expects a null-terminated list
            params.devices = selected.data();
        }
        params.load_mode = LLAMA_LOAD_MODE_MMAP;
        params.vocab_only = false;
        params.check_tensors = false;
        drain_recent_log();
        __android_log_print(ANDROID_LOG_INFO, "BramLlama",
            "bram_load: requesting n_gpu_layers=%d device_filter='%s' matched=%d",
            static_cast<int>(gpu_layers), filter.c_str(),
            selected.empty() ? 0 : static_cast<int>(selected.size() - 1));
        g_state.model = llama_model_load_from_file(model_path.c_str(), params);
        if (g_state.model == nullptr) {
            std::string detail = drain_recent_log();
            std::string message = "llama.cpp could not load the selected GGUF";
            if (!detail.empty()) message += ": " + detail;
            throw std::runtime_error(message);
        }
        g_state.chat_templates = common_chat_templates_init(g_state.model, "");
        if (!g_state.chat_templates) throw std::runtime_error("Could not initialize the GGUF chat template");
        g_state.context_tokens = context_tokens;
        g_state.batch_tokens = batch_tokens;
        g_state.threads = threads;
        g_state.gpu_layers = gpu_layers;
        g_state.enable_thinking = enable_thinking == JNI_TRUE;
        g_state.model_path = model_path;

        char description[512] = {};
        llama_model_desc(g_state.model, description, sizeof(description));
        const char * quantization = llama_ftype_name(llama_model_ftype(g_state.model));
        std::ostringstream result;
        result << "{\"loaded\":true"
               << ",\"description\":\"" << json_escape(description) << "\""
               << ",\"quantization\":\"" << json_escape(quantization == nullptr ? "unknown" : quantization) << "\""
               << ",\"trainedContextTokens\":" << llama_model_n_ctx_train(g_state.model)
               << ",\"layerCount\":" << llama_model_n_layer(g_state.model)
               << ",\"parameterCount\":" << llama_model_n_params(g_state.model)
               << ",\"tensorBytes\":" << llama_model_size(g_state.model)
               << ",\"contextTokens\":" << g_state.context_tokens
               << ",\"threads\":" << g_state.threads
               << ",\"requestedGpuLayers\":" << g_state.gpu_layers
               << ",\"offloadedToGpu\":" << (g_state.gpu_layers > 0 ? "true" : "false") << "}";
        return result.str();
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_formatChat(
    JNIEnv * env, jobject, jobjectArray role_array, jobjectArray content_array, jboolean add_assistant,
    jstring tools_value, jboolean require_tool) {
    return guarded_string(env, [&] {
        std::lock_guard<std::mutex> lock(g_mutex);
        const jsize count = env->GetArrayLength(role_array);
        if (count != env->GetArrayLength(content_array)) throw std::runtime_error("Role/content count mismatch");
        std::vector<std::string> roles;
        std::vector<std::string> contents;
        roles.reserve(static_cast<size_t>(count));
        contents.reserve(static_cast<size_t>(count));
        for (jsize index = 0; index < count; ++index) {
            auto role = static_cast<jstring>(env->GetObjectArrayElement(role_array, index));
            auto content = static_cast<jstring>(env->GetObjectArrayElement(content_array, index));
            roles.push_back(from_jstring(env, role));
            contents.push_back(from_jstring(env, content));
            env->DeleteLocalRef(role);
            env->DeleteLocalRef(content);
        }
        return apply_chat_template(
            roles, contents, add_assistant == JNI_TRUE, from_jstring(env, tools_value),
            require_tool == JNI_TRUE);
    });
}

/**
 * The reasoning tags of the format the last prompt was built with.
 *
 * llama.cpp computes these per format — `[THINK]`, `<|channel|>analysis<|message|>` and `<mm:think>`
 * are all in use, and some formats close with more than one tag — and the app was hardcoding
 * `<think>`. `forcedOpen` answers the question the app was inferring from the reasoning setting:
 * whether the prompt already opened the block, leaving the model's stream to contain only its close.
 *
 * Valid only after a prompt has been built, since that is when the template is applied.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_chatFormat(
    JNIEnv * env, jobject) {
    return guarded_string(env, [] {
        std::lock_guard<std::mutex> lock(g_mutex);
        const common_chat_params & params = g_state.last_chat_params;
        const std::string & start = params.thinking_start_tag;
        const bool forced_open = !start.empty() && params.prompt.size() >= start.size() &&
            params.prompt.compare(params.prompt.size() - start.size(), start.size(), start) == 0;
        std::ostringstream result;
        result << "{\"supportsTools\":" << (params.grammar.empty() ? "false" : "true")
               << ",\"supportsThinking\":" << (params.supports_thinking ? "true" : "false")
               << ",\"forcedOpen\":" << (forced_open ? "true" : "false")
               << ",\"startTag\":\"" << json_escape(start) << "\""
               << ",\"endTags\":[";
        bool first = true;
        for (const std::string & tag : params.thinking_end_tags) {
            if (!first) result << ",";
            result << '"' << json_escape(tag) << '"';
            first = false;
        }
        result << "]}";
        return result.str();
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_countTokens(
    JNIEnv * env, jobject, jstring prompt) {
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        return static_cast<jint>(tokenize(from_jstring(env, prompt)).size());
    } catch (const std::exception & error) {
        throw_java(env, error.what());
        return -1;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_generate(
    JNIEnv * env, jobject, jstring prompt_value, jint max_output_tokens, jfloat temperature,
    jfloat top_p, jint top_k, jfloat repeat_penalty, jint repeat_last_tokens, jobject sink) {
    return guarded_string(env, [&] {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_cancelled.store(false, std::memory_order_relaxed);
        const std::string prompt = from_jstring(env, prompt_value);
        const auto prompt_tokens = tokenize(prompt);
        if (prompt_tokens.size() + static_cast<size_t>(max_output_tokens) > static_cast<size_t>(g_state.context_tokens)) {
            throw std::runtime_error(
                "Prompt and reserved output exceed the loaded context (" +
                std::to_string(prompt_tokens.size()) + " + " + std::to_string(max_output_tokens) +
                " > " + std::to_string(g_state.context_tokens) + ")");
        }

        if (g_state.chat_context == nullptr) {
            g_state.chat_context = create_context();
            g_state.cached_tokens.clear();
        }
        llama_context * context = g_state.chat_context;

        // Keep the part of the cache the new prompt agrees with and drop the rest. A turn appends
        // to the conversation, so in the common case everything up to the previous reply matches
        // and only the new user message has to be decoded.
        const size_t matched = reusable_prefix(prompt_tokens);
        // Only ask for a trim when there is something to remove. A turn that purely extends the
        // cache needs none, and asking anyway fails on architectures that cannot erase part of a
        // sequence — which would throw away a cache that was already correct.
        const bool trimmed = matched == g_state.cached_tokens.size() ||
            llama_memory_seq_rm(llama_get_memory(context), 0, static_cast<llama_pos>(matched), -1);
        if (!trimmed) {
            // A cache that cannot be partially trimmed has to go entirely, or the positions of what
            // follows would no longer line up with what the model is told it has seen. Recurrent
            // and hybrid architectures — Mamba, RWKV, and LFM2 among them — refuse partial removal
            // because their state is not kept per token, so they land here on every turn that does
            // not purely extend the cache.
            llama_memory_clear(llama_get_memory(context), true);
            g_state.cached_tokens.clear();
        } else {
            g_state.cached_tokens.resize(matched);
        }
        // What was kept, after a trim that may have had to discard everything.
        const size_t reused = g_state.cached_tokens.size();
        __android_log_print(ANDROID_LOG_INFO, "BramLlama",
                            "kv cache: prompt %zu tokens, matched %zu, reused %zu",
                            prompt_tokens.size(), matched, reused);

        const std::vector<llama_token> pending(
            prompt_tokens.begin() + static_cast<std::ptrdiff_t>(g_state.cached_tokens.size()),
            prompt_tokens.end());
        const auto prompt_start = std::chrono::steady_clock::now();
        try {
            decode_prompt(context, pending);
        } catch (...) {
            // The cache no longer describes what the model has seen, and there is no way to tell
            // how far it got, so it cannot be trusted for the next turn.
            release_chat_context();
            throw;
        }
        g_state.cached_tokens = prompt_tokens;
        const auto prompt_end = std::chrono::steady_clock::now();

        llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
        sampler_params.no_perf = false;
        llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
        // First in the chain, so the grammar constrains what the later samplers choose between
        // rather than being asked to fix a choice already made. Without it a small model produces
        // something that looks like a tool call but does not parse.
        if (!g_state.last_chat_params.grammar.empty()) {
            const common_chat_params & chat = g_state.last_chat_params;
            const llama_vocab * grammar_vocab = llama_model_get_vocab(g_state.model);
            llama_sampler * grammar = nullptr;
            if (chat.grammar_lazy) {
                // A lazy grammar must be given its triggers, or it never engages: the model writes
                // the call in whatever shape it likes and the parser, which expects the format's
                // own opening marker, finds nothing. Applying it eagerly instead is worse — that
                // forces every reply to be a tool call.
                std::vector<std::string> pattern_storage;
                std::vector<llama_token> trigger_tokens;
                for (const common_grammar_trigger & trigger : chat.grammar_triggers) {
                    switch (trigger.type) {
                        case COMMON_GRAMMAR_TRIGGER_TYPE_TOKEN:
                            trigger_tokens.push_back(trigger.token);
                            break;
                        case COMMON_GRAMMAR_TRIGGER_TYPE_WORD:
                            // A word trigger is a literal; the sampler takes patterns, so it is
                            // escaped into one that matches the literal and nothing else.
                            pattern_storage.push_back(::regex_escape(trigger.value));
                            break;
                        case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN:
                            pattern_storage.push_back("(" + trigger.value + ")[\\s\\S]*");
                            break;
                        case COMMON_GRAMMAR_TRIGGER_TYPE_PATTERN_FULL:
                            pattern_storage.push_back(trigger.value);
                            break;
                    }
                }
                std::vector<const char *> patterns;
                patterns.reserve(pattern_storage.size());
                for (const std::string & pattern : pattern_storage) patterns.push_back(pattern.c_str());
                grammar = llama_sampler_init_grammar_lazy_patterns(
                    grammar_vocab,
                    chat.grammar.c_str(),
                    "root",
                    patterns.empty() ? nullptr : patterns.data(),
                    patterns.size(),
                    trigger_tokens.empty() ? nullptr : trigger_tokens.data(),
                    trigger_tokens.size());
            } else {
                grammar = llama_sampler_init_grammar(grammar_vocab, chat.grammar.c_str(), "root");
            }
            if (grammar == nullptr) {
                __android_log_print(ANDROID_LOG_WARN, "BramLlama",
                    "tool grammar failed to compile (%zu bytes, lazy=%d); the reply is unconstrained",
                    chat.grammar.size(), chat.grammar_lazy ? 1 : 0);
            } else {
                llama_sampler_chain_add(sampler, grammar);
            }
        }
        const auto sampler_guard = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(sampler, llama_sampler_free);
        // Repetition is penalised before truncation, so the penalty applies to the full
        // distribution rather than to whatever top-k happened to leave behind.
        if (repeat_penalty > 1.0f && repeat_last_tokens > 0) {
            llama_sampler_chain_add(
                sampler,
                llama_sampler_init_penalties(
                    llama_vocab_n_tokens(llama_model_get_vocab(g_state.model)),
                    repeat_last_tokens,
                    repeat_penalty,
                    0.0f,
                    0.0f));
        }
        if (temperature <= 0.0f) {
            // Greedy. The accelerator comparison depends on this being reachable, though it builds
            // its own sampler rather than coming through here.
            llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
        } else {
            if (top_k > 0) llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
            if (top_p < 1.0f) llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
            llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
            llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }

        const llama_vocab * vocab = llama_model_get_vocab(g_state.model);
        jclass sink_class = env->GetObjectClass(sink);
        jmethodID on_token = env->GetMethodID(sink_class, "onToken", "(Ljava/lang/String;)V");
        if (on_token == nullptr) throw std::runtime_error("Native token callback is unavailable");

        int output_count = 0;
        std::string pending_utf8;
        std::string finish_reason = "length";
        const auto decode_start = std::chrono::steady_clock::now();
        for (; output_count < max_output_tokens; ++output_count) {
            if (g_cancelled.load(std::memory_order_relaxed)) {
                finish_reason = "cancelled";
                break;
            }
            const llama_token token = llama_sampler_sample(sampler, context, -1);
            if (llama_vocab_is_eog(vocab, token)) {
                finish_reason = "stop";
                break;
            }
            pending_utf8 += token_piece(vocab, token);
            if (is_complete_utf8(pending_utf8)) {
                jstring text = to_jstring(env, pending_utf8);
                env->CallVoidMethod(sink, on_token, text);
                env->DeleteLocalRef(text);
                if (env->ExceptionCheck()) throw std::runtime_error("Token callback failed");
                pending_utf8.clear();
            }

            llama_token next = token;
            const int result = llama_decode(context, llama_batch_get_one(&next, 1));
            if (result != 0) {
                if (g_cancelled.load(std::memory_order_relaxed)) {
                    finish_reason = "cancelled";
                    break;
                }
                release_chat_context();
                throw std::runtime_error("llama.cpp failed during token generation (code " + std::to_string(result) + ")");
            }
            // Recorded only once the token is in the cache, so a failed decode does not leave the
            // record claiming more than the cache holds.
            g_state.cached_tokens.push_back(next);
        }
        if (!pending_utf8.empty()) {
            jstring text = to_jstring(env, pending_utf8);
            env->CallVoidMethod(sink, on_token, text);
            env->DeleteLocalRef(text);
        }
        const auto decode_end = std::chrono::steady_clock::now();
        env->DeleteLocalRef(sink_class);

        const auto prompt_ms = std::chrono::duration_cast<std::chrono::milliseconds>(prompt_end - prompt_start).count();
        const auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(decode_end - decode_start).count();
        std::ostringstream result;
        result << "{\"promptTokens\":" << prompt_tokens.size()
               << ",\"cachedPromptTokens\":" << reused
               << ",\"outputTokens\":" << output_count
               << ",\"promptMillis\":" << prompt_ms
               << ",\"decodeMillis\":" << decode_ms
               << ",\"finishReason\":\"" << finish_reason << "\"}";
        return result.str();
    });
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_cancel(
    JNIEnv *, jobject) {
    g_cancelled.store(true, std::memory_order_relaxed);
}

// Enumerates the ggml backend devices this build can actually see, so accelerator capability is
// reported from the runtime rather than from Android feature flags alone.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_devices(
    JNIEnv * env, jobject) {
    return guarded_string(env, [] {
        ensure_backend();
        std::ostringstream result;
        result << "{\"devices\":[";
        const size_t count = ggml_backend_dev_count();
        bool first = true;
        bool vulkan_present = false;
        for (size_t index = 0; index < count; ++index) {
            ggml_backend_dev_t device = ggml_backend_dev_get(index);
            if (device == nullptr) continue;
            const char * name = ggml_backend_dev_name(device);
            const char * description = ggml_backend_dev_description(device);
            const int type = static_cast<int>(ggml_backend_dev_type(device));
            size_t free_bytes = 0;
            size_t total_bytes = 0;
            ggml_backend_dev_memory(device, &free_bytes, &total_bytes);
            const std::string device_name = name == nullptr ? "" : name;
            if (device_name.rfind("Vulkan", 0) == 0) vulkan_present = true;
            if (!first) result << ",";
            first = false;
            result << "{\"name\":\"" << json_escape(device_name) << "\""
                   << ",\"description\":\"" << json_escape(description == nullptr ? "" : description) << "\""
                   << ",\"type\":" << type
                   << ",\"freeBytes\":" << static_cast<uint64_t>(free_bytes)
                   << ",\"totalBytes\":" << static_cast<uint64_t>(total_bytes) << "}";
        }
        result << "],\"vulkanAvailable\":" << (vulkan_present ? "true" : "false") << "}";
        return result.str();
    });
}

// Deterministic greedy decode over a fixed prompt. Milestone 2 records this sequence once on CPU
// and then requires the accelerator to reproduce it exactly before its capability is validated.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_referenceDecode(
    JNIEnv * env, jobject, jint token_count) {
    return guarded_string(env, [&] {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_state.model == nullptr) throw std::runtime_error("Load a model before running the reference decode");
        g_cancelled.store(false, std::memory_order_relaxed);
        const int wanted = std::max(1, std::min(static_cast<int>(token_count), 64));
        const std::string prompt = apply_chat_template({"user"}, {kReferencePrompt}, true, "", false);
        const auto tokens = tokenize(prompt);
        if (tokens.empty()) throw std::runtime_error("Reference decode tokenizer returned no tokens");
        llama_context * context = create_context();
        const auto context_guard = std::unique_ptr<llama_context, decltype(&llama_free)>(context, llama_free);
        decode_prompt(context, tokens);
        llama_sampler * sampler = llama_sampler_init_greedy();
        const auto sampler_guard = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(sampler, llama_sampler_free);
        const llama_vocab * vocab = llama_model_get_vocab(g_state.model);

        std::ostringstream ids;
        std::string text;
        ids << "[";
        for (int index = 0; index < wanted; ++index) {
            const llama_token token = llama_sampler_sample(sampler, context, -1);
            if (index > 0) ids << ",";
            ids << token;
            if (llama_vocab_is_eog(vocab, token)) break;
            text += token_piece(vocab, token);
            llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
            if (llama_decode(context, batch) != 0) {
                throw std::runtime_error("Reference decode failed while advancing the context");
            }
        }
        ids << "]";
        __android_log_print(ANDROID_LOG_INFO, "BramLlama",
            "bram_reference: gpu_layers=%d tokens=%s text=\"%s\"",
            g_state.gpu_layers, ids.str().c_str(), text.c_str());
        std::ostringstream result;
        result << "{\"tokens\":" << ids.str()
               << ",\"text\":\"" << json_escape(text) << "\""
               << ",\"promptTokens\":" << tokens.size()
               << ",\"gpuLayers\":" << g_state.gpu_layers << "}";
        return result.str();
    });
}

// Teacher-forced agreement check. Both backends are fed the identical token sequence and asked
// only for the next-token prediction at each position, so a numerical difference cannot compound
// into unrelated text the way free-running generation does. This isolates "does the accelerator
// compute the same thing" from "did one early token send generation somewhere else".
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_teacherForced(
    JNIEnv * env, jobject, jintArray forced_tokens) {
    return guarded_string(env, [&] {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_state.model == nullptr) throw std::runtime_error("Load a model before the agreement check");
        g_cancelled.store(false, std::memory_order_relaxed);

        std::vector<llama_token> forced;
        if (forced_tokens != nullptr) {
            const jsize length = env->GetArrayLength(forced_tokens);
            forced.resize(static_cast<size_t>(length));
            if (length > 0) {
                env->GetIntArrayRegion(forced_tokens, 0, length, reinterpret_cast<jint *>(forced.data()));
            }
        }

        const std::string prompt = apply_chat_template({"user"}, {kReferencePrompt}, true, "", false);
        auto tokens = tokenize(prompt);
        if (tokens.empty()) throw std::runtime_error("Agreement check tokenizer returned no tokens");

        llama_context * context = create_context();
        const auto context_guard = std::unique_ptr<llama_context, decltype(&llama_free)>(context, llama_free);
        decode_prompt(context, tokens);
        llama_sampler * sampler = llama_sampler_init_greedy();
        const auto sampler_guard = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(sampler, llama_sampler_free);

        // With no forced sequence this behaves as a plain greedy run and produces the reference.
        const size_t steps = forced.empty() ? 24 : forced.size();
        std::ostringstream predictions;
        predictions << "[";
        for (size_t index = 0; index < steps; ++index) {
            const llama_token predicted = llama_sampler_sample(sampler, context, -1);
            if (index > 0) predictions << ",";
            predictions << predicted;
            // Advance with the reference token when teacher forcing, otherwise with our own.
            const llama_token advance = forced.empty() ? predicted : forced[index];
            llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&advance), 1);
            if (llama_decode(context, batch) != 0) {
                throw std::runtime_error("Agreement check failed while advancing the context");
            }
        }
        predictions << "]";
        __android_log_print(ANDROID_LOG_INFO, "BramLlama",
            "bram_teacher_forced: gpu_layers=%d predictions=%s",
            g_state.gpu_layers, predictions.str().c_str());
        std::ostringstream result;
        result << "{\"predictions\":" << predictions.str()
               << ",\"gpuLayers\":" << g_state.gpu_layers << "}";
        return result.str();
    });
}

// Splits a finished reply into reasoning and answer using the same chat format that produced the
// prompt. Bram shows reasoning as a collapsed transcript entry rather than inline prose, so it has
// to be identified rather than left mixed into the answer.
extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_parseReply(
    JNIEnv * env, jobject, jstring reply) {
    return guarded_string(env, [&] {
        std::lock_guard<std::mutex> lock(g_mutex);
        const std::string text = from_jstring(env, reply);

        std::string content = text;
        std::string reasoning;
        std::vector<common_chat_tool_call> tool_calls;

        // Try the structured parser first: when the chat format is known it is the only thing that
        // understands where reasoning ends and the answer begins.
        try {
            common_chat_parser_params params(g_state.last_chat_params);
            // The constructor copies only the format and generation prompt, not the parser the
            // template built. Without it the parse falls back to what the format alone implies and
            // cannot see a marked tool call, which is why every call arrived as text. llama.cpp's
            // own server loads it, and with it loaded the same model returns a parsed call.
            if (!g_state.last_chat_params.parser.empty()) {
                params.parser.load(g_state.last_chat_params.parser);
            }
            params.reasoning_format = COMMON_REASONING_FORMAT_AUTO;
            // Tool calls come from the same parse. They were being discarded, which is why a
            // local model could not call a tool even once the template offered it one.
            params.parse_tool_calls = true;
            const common_chat_msg parsed = common_chat_parse(text, false, params);
            if (!parsed.content.empty()) content = parsed.content;
            if (!parsed.reasoning_content.empty()) reasoning = parsed.reasoning_content;
            tool_calls = parsed.tool_calls;
        } catch (const std::exception & error) {
            __android_log_print(ANDROID_LOG_WARN, "BramLlama",
                "reply parse failed (%s); cleaning the raw reply instead", error.what());
        }

        // Then clean up whatever survived. A degraded template path leaves the model emitting its
        // own turn header and empty reasoning markers, and a content-only format passes those
        // straight through, so the reply would otherwise read as protocol rather than as an answer.
        auto trim = [](std::string & value) {
            const char * spaces = " TABNLCR";
            (void) spaces;
            while (!value.empty() && (value.front() == ' ' || value.front() == '\n' ||
                                      value.front() == '\r' || value.front() == '\t')) {
                value.erase(0, 1);
            }
            while (!value.empty() && (value.back() == ' ' || value.back() == '\n' ||
                                     value.back() == '\r' || value.back() == '\t')) {
                value.pop_back();
            }
        };
        auto strip_leading_marker = [&](const std::string & marker) {
            const size_t position = content.find(marker);
            if (position != std::string::npos && position < 32) {
                content.erase(0, position + marker.size());
                trim(content);
            }
        };
        strip_leading_marker("<|im_start|>assistant");
        strip_leading_marker("<|start_header_id|>assistant<|end_header_id|>");

        const size_t think_open = content.find("<think>");
        const size_t think_close = content.find("</think>");
        if (think_open != std::string::npos && think_close != std::string::npos && think_close > think_open) {
            std::string inner = content.substr(think_open + 7, think_close - think_open - 7);
            trim(inner);
            if (!inner.empty() && reasoning.empty()) reasoning = inner;
            content.erase(think_open, think_close - think_open + 8);
        }
        trim(content);
        trim(reasoning);

        std::ostringstream result;
        result << "{" << '"' << "content" << '"' << ":" << '"' << json_escape(content) << '"'
               << "," << '"' << "reasoning" << '"' << ":" << '"' << json_escape(reasoning) << '"'
               << ",\"toolCalls\":[";
        bool first = true;
        for (const common_chat_tool_call & call : tool_calls) {
            if (!first) result << ",";
            result << "{\"name\":\"" << json_escape(call.name) << "\""
                   << ",\"arguments\":\"" << json_escape(call.arguments) << "\""
                   << ",\"id\":\"" << json_escape(call.id) << "\"}";
            first = false;
        }
        result << "]}";
        return result.str();
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_selfTest(
    JNIEnv * env, jobject) {
    return guarded_string(env, [] {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_cancelled.store(false, std::memory_order_relaxed);
        const std::string prompt = apply_chat_template({"user"}, {"Reply with OK."}, true, "", false);
        const auto tokens = tokenize(prompt);
        if (tokens.empty()) throw std::runtime_error("CPU self-test tokenizer returned no tokens");
        llama_context * context = create_context();
        const auto context_guard = std::unique_ptr<llama_context, decltype(&llama_free)>(context, llama_free);
        decode_prompt(context, tokens);
        llama_sampler * sampler = llama_sampler_init_greedy();
        const auto sampler_guard = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>(sampler, llama_sampler_free);
        const llama_token token = llama_sampler_sample(sampler, context, -1);
        const llama_vocab * vocab = llama_model_get_vocab(g_state.model);
        const std::string piece = llama_vocab_is_eog(vocab, token) ? "<eog>" : token_piece(vocab, token);
        if (piece.empty()) throw std::runtime_error("CPU self-test produced an empty token");
        return std::string("{\"passed\":true,\"detail\":\"tokenizer + one-token CPU decode\",\"sample\":\"") +
            json_escape(piece) + "\"}";
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_unload(
    JNIEnv * env, jobject) {
    return guarded_string(env, [] {
        std::lock_guard<std::mutex> lock(g_mutex);
        unload_locked();
        return std::string("{\"loaded\":false}");
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_kurue_bram_runtime_llamacpp_inference_NativeLlamaBridge_state(
    JNIEnv * env, jobject) {
    return guarded_string(env, [] {
        std::lock_guard<std::mutex> lock(g_mutex);
        std::ostringstream result;
        result << "{\"loaded\":" << (g_state.model != nullptr ? "true" : "false")
               << ",\"contextTokens\":" << g_state.context_tokens
               << ",\"threads\":" << g_state.threads << "}";
        return result.str();
    });
}
