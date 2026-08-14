package io.github.kurue.bram.runtime.llamacpp.inference

import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import io.github.kurue.bram.runtime.llamacpp.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONArray
import org.json.JSONObject

/** Owns every native object and file descriptor inside Bram's crash-isolated process. */
class InferenceProcessService : Service() {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "bram-local-inference").apply { priority = Thread.NORM_PRIORITY }
    }
    private val requests = ConcurrentHashMap<String, Future<*>>()
    private val bridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        // The Hexagon NPU loader resolves its DSP-side skels through ADSP_LIBRARY_PATH, and reads
        // it when the backend initialises, so it has to be set before the native library loads.
        runCatching {
            val nativeDir = applicationInfo.nativeLibraryDir
            android.system.Os.setenv("ADSP_LIBRARY_PATH", nativeDir, true)
        }
        NativeLlamaBridge()
    }
    /**
     * The identity of the load this process is serving, or null when nothing is loaded. Every
     * setting is part of it: a context is never reused for a different configuration, because
     * reusing one would silently run or validate the previous one.
     */
    private var loaded: LoadIdentity? = null
    /**
     * The `GGML_HEXAGON_*` environment this process was born with. The backend reads it once at
     * registration, so the first native load fixes it; a later request that differs must come
     * from a fresh process (the client restarts on `restartRequired`).
     */
    private var processHexEnv: String? = null
    private var cpuValidated = false

    private val binder = object : IInferenceService.Stub() {
        override fun probe(): String = runSerialized {
            JSONObject(bridge.probe())
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("process", ":inference")
                .put("llamaCppCommit", BuildConfig.LLAMA_CPP_COMMIT)
                .toString()
        }

        override fun devices(): String = runSerialized { bridge.devices() }

        override fun quantize(requestJson: String?): String = runSerialized {
            val request = JSONObject(requestJson.orEmpty())
            bridge.quantize(
                request.getString("modelPath"),
                request.getString("outPath"),
                request.getString("ftype"),
            )
        }

        override fun referenceDecode(tokenCount: Int, padTokens: Int): String = runSerialized {
            bridge.referenceDecode(tokenCount, padTokens)
        }

        override fun teacherForced(forcedTokens: IntArray?, padTokens: Int): String = runSerialized {
            bridge.teacherForced(forcedTokens ?: IntArray(0), padTokens)
        }

        override fun parseReply(reply: String?): String = runSerialized {
            bridge.parseReply(reply.orEmpty())
        }

        override fun load(requestJson: String): String = runSerialized {
            loadModel(JSONObject(requestJson))
        }

        override fun countTokens(requestJson: String): Int = runSerialized {
            // Counting is about the conversation, not about what tools a later run might offer.
            val prompt = formatPrompt(JSONObject(requestJson).getJSONArray("messages"), "")
            bridge.countTokens(prompt)
        }

        override fun generate(requestId: String, requestJson: String, callback: IInferenceCallback) {
            bridge.cancel()
            requests.remove(requestId)?.cancel(true)
            requests[requestId] = executor.submit {
                try {
                    check(loaded != null) { "Load a local model before generating" }
                    val request = JSONObject(requestJson)
                    val prompt = formatPrompt(
                        request.getJSONArray("messages"),
                        request.optJSONArray("tools")?.toString().orEmpty(),
                    )
                    // Read after the prompt is built, since applying the template is what decides
                    // the format, and reported with the start event so the caller has the tags
                    // before the first token arrives.
                    val chatFormat = runCatching { JSONObject(bridge.chatFormat()) }.getOrNull()
                    emit(
                        callback,
                        requestId,
                        JSONObject()
                            .put("type", "started")
                            .put("runtimeDescription", "Local CPU · ${loaded?.contextTokens ?: 0} token context")
                            .put("chatFormat", chatFormat),
                    )
                    // Kept so the finished reply can be parsed for tool calls. The deltas are what
                    // the transcript shows; the whole thing is what the parser needs.
                    val reply = StringBuilder()
                    val result = JSONObject(
                        bridge.generate(
                            prompt = prompt,
                            maxOutputTokens = request.optInt("maxOutputTokens", 1_024).coerceIn(1, 16_384),
                            temperature = request.optDouble("temperature", 0.7).toFloat(),
                            topP = request.optDouble("topP", 0.95).toFloat(),
                            topK = request.optInt("topK", 40),
                            repeatPenalty = request.optDouble("repeatPenalty", 1.1).toFloat(),
                            repeatLastTokens = request.optInt("repeatLastTokens", 64),
                            sink = NativeTokenSink { token ->
                                reply.append(token)
                                emit(
                                    callback,
                                    requestId,
                                    JSONObject().put("type", "textDelta").put("text", token),
                                )
                            },
                        ),
                    )
                    // Parsed here rather than by the caller: this process owns the chat format the
                    // reply has to be read against, and a tool call has to reach the agent loop
                    // before the turn is reported finished.
                    // The streamed text has its special tokens rendered away for display; this
                    // copy keeps them, and they are what marks a tool call.
                    val rawReply = result.optString("rawReply").ifBlank { reply.toString() }
                    var parsedReply = runCatching { bridge.parseReply(rawReply) }.getOrNull()
                    var toolCalls = runCatching {
                        JSONObject(parsedReply.orEmpty()).optJSONArray("toolCalls")
                    }.getOrNull()

                    // A model can describe a tool call without emitting the marker its format
                    // requires, which leaves a well-formed intent the parser will not accept. Asking
                    // again with the tool choice required makes the grammar eager, so the call is
                    // constrained as it is written and comes back through the real parser rather
                    // than being recovered from prose by pattern matching.
                    val toolsJson = request.optJSONArray("tools")?.toString().orEmpty()
                    if ((toolCalls == null || toolCalls.length() == 0) &&
                        looksLikeUnparsedCall(rawReply, toolsJson)
                    ) {
                        emit(
                            callback,
                            requestId,
                            JSONObject().put("type", "status").put("text", "Asking for that as a tool call..."),
                        )
                        val retryPrompt = formatPrompt(
                            request.getJSONArray("messages"),
                            toolsJson,
                            requireTool = true,
                        )
                        val retry = StringBuilder()
                        runCatching {
                            bridge.generate(
                                prompt = retryPrompt,
                                maxOutputTokens = request.optInt("maxOutputTokens", 1_024).coerceIn(1, 16_384),
                                // Greedy: the retry is about getting the format right, and a second
                                // sampled variation is not what is wanted here.
                                temperature = 0f,
                                topP = 1f,
                                topK = 0,
                                repeatPenalty = 1f,
                                repeatLastTokens = 0,
                                sink = NativeTokenSink { token -> retry.append(token) },
                            )
                        }.onSuccess {
                            val retryParsed = runCatching { bridge.parseReply(retry.toString()) }.getOrNull()
                            val retryCalls = runCatching {
                                JSONObject(retryParsed.orEmpty()).optJSONArray("toolCalls")
                            }.getOrNull()
                            if (retryCalls != null && retryCalls.length() > 0) {
                                parsedReply = retryParsed
                                toolCalls = retryCalls
                            }
                        }

                        // Last resort, after the parser and the forced retry have both declined.
                        // Marked as recovered so the approval gate always asks about it.
                        if (toolCalls == null || toolCalls.length() == 0) {
                            val names = runCatching {
                                val array = JSONArray(toolsJson)
                                (0 until array.length())
                                    .mapNotNull { array.optJSONObject(it)?.optString("name") }
                                    .filter(String::isNotEmpty)
                                    .toSet()
                            }.getOrDefault(emptySet())
                            val content = runCatching {
                                JSONObject(parsedReply.orEmpty()).optString("content")
                            }.getOrDefault("")
                            BareToolCall.recover(content, names)?.let { recoveredCall ->
                                toolCalls = JSONArray().put(recoveredCall.put("recovered", true))
                            }
                        }
                    }
                    if (toolCalls != null && toolCalls.length() > 0) {
                        emit(
                            callback,
                            requestId,
                            JSONObject().put("type", "toolCalls").put("calls", toolCalls),
                        )
                    }
                    emit(
                        callback,
                        requestId,
                        JSONObject()
                            .put("type", "usage")
                            .put("inputTokens", result.getInt("promptTokens"))
                            .put("outputTokens", result.getInt("outputTokens")),
                    )
                    emit(
                        callback,
                        requestId,
                        JSONObject(result.toString())
                            .put("type", "metrics")
                            .put("processPssBytes", Debug.getPss().toLong() * 1_024L),
                    )
                    emit(
                        callback,
                        requestId,
                        JSONObject()
                            .put("type", "finished")
                            .put("finishReason", result.optString("finishReason", "stop")),
                    )
                } catch (error: Throwable) {
                    emit(
                        callback,
                        requestId,
                        JSONObject()
                            .put("type", "failed")
                            .put("recoverable", true)
                            .put("message", error.rootMessage()),
                    )
                } finally {
                    requests.remove(requestId)
                }
            }
        }

        override fun cancel(requestId: String) {
            bridge.cancel()
            requests.remove(requestId)?.cancel(true)
        }

        override fun unload(): String = runSerialized { unloadModel() }

        override fun state(): String = runSerialized {
            JSONObject(bridge.state())
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("loadedModelId", loaded?.modelId)
                .put("contextTokens", loaded?.contextTokens ?: 0)
                .put("cpuValidated", cpuValidated)
                .toString()
        }

        // The embedder has its own model+context and native mutex, so these run on the Binder
        // thread directly rather than the single-thread chat executor — an embedding must not
        // queue behind a turn, and a turn must not queue behind an embedding.
        override fun loadEmbedder(modelPath: String?, threads: Int): String =
            bridge.loadEmbedder(modelPath.orEmpty(), threads)

        override fun embed(text: String?): FloatArray = bridge.embed(text.orEmpty())

        override fun unloadEmbedder(): String = bridge.unloadEmbedder()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        bridge.cancel()
        requests.values.forEach { it.cancel(true) }
        requests.clear()
        // Never wait on the executor here: a hung native call occupies it forever, and blocking
        // onDestroy would keep the process alive exactly when the client needs it dead so a fresh
        // one can take over. The process teardown frees the model.
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun loadModel(request: JSONObject): String {
        // The identity is parsed and normalized once, here, and compared whole: every setting is
        // part of it, because reusing a context built for another configuration would silently
        // run or validate the wrong thing.
        val identity = LoadIdentity.from(request)
        val requestedHex = identity.hexKey
        // The Hexagon environment is read once per process, at backend registration. A request
        // that would change it cannot be honored by this process, so hand the load back to the
        // client, which restarts the process and retries. Unload first so the restart is clean,
        // and schedule this process's own death: the client waits on the binder death, and a
        // self-kill is deterministic where an unbind-triggered teardown can be slow.
        if (processHexEnv != null && requestedHex != processHexEnv) {
            runCatching { bridge.unload() }
            loaded = null
            cpuValidated = false
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { android.os.Process.killProcess(android.os.Process.myPid()) },
                500L,
            )
            return JSONObject().put("restartRequired", true).toString()
        }
        // The offload plan is part of the load identity: reusing a CPU-resident model for a GPU
        // request would silently validate the accelerator against itself. The attention path, KV
        // type, batch, and threadpool settings are part of it too: they are context parameters, so
        // changing them without reloading would quietly measure the previous configuration.
        if (identity == loaded && cpuValidated) {
            return JSONObject(bridge.state())
                .put("alreadyLoaded", true)
                .put("modelId", identity.modelId)
                .put("cpuValidated", true)
                .toString()
        }

        val localPath = request.optString("localPath")
        if (localPath.isBlank()) {
            throw IllegalArgumentException(
                "This model was imported by an older Bram build. Remove it and import the GGUF again.",
            )
        }
        val modelFile = java.io.File(localPath)
        if (!modelFile.isFile) {
            throw IllegalArgumentException("The imported GGUF copy is missing. Import the model again.")
        }
        try {
            val expectedSize = request.optLong("fileSizeBytes", -1L)
            val actualSize = modelFile.length()
            require(expectedSize < 0 || expectedSize == actualSize) {
                "The GGUF size changed after import. Remove it from Bram and import it again before loading."
            }
            // The JNI layer applies the environment before the backend initializes, so record the
            // process's hex environment even if this particular load then fails.
            processHexEnv = requestedHex
            val loadResult = JSONObject(
                bridge.load(
                    modelPath = modelFile.absolutePath,
                    contextTokens = identity.contextTokens,
                    batchTokens = identity.batchTokens,
                    ubatchTokens = identity.ubatchTokens,
                    threads = identity.threads,
                    gpuLayers = identity.gpuLayers,
                    deviceFilter = identity.deviceFilter,
                    enableThinking = identity.enableThinking,
                    flashAttention = identity.flashAttention,
                    kvCacheType = identity.kvCacheType,
                    cpuMask = identity.cpuMask,
                    cpuStrict = identity.cpuStrict,
                    poll = identity.poll,
                    threadPriority = identity.threadPriority,
                    loadMode = identity.loadMode,
                    hexUseHmx = identity.hexUseHmx,
                    hexDisableNhvx = identity.hexDisableNhvx,
                    hexHostBuf = identity.hexHostBuf,
                    hexOpBatch = identity.hexOpBatch,
                    hexNDev = identity.hexNDev,
                ),
            )
            val validation = JSONObject(bridge.selfTest())
            check(validation.optBoolean("passed")) {
                validation.optString("detail", "Native CPU correctness self-test failed")
            }
            loaded = identity
            cpuValidated = true
            return loadResult
                .put("alreadyLoaded", false)
                .put("modelId", identity.modelId)
                .put("cpuValidated", true)
                .put("processPssBytes", Debug.getPss().toLong() * 1_024L)
                .put("selfTest", validation)
                .toString()
        } catch (error: Throwable) {
            runCatching { bridge.unload() }
            loaded = null
            cpuValidated = false
            throw error
        }
    }

    private fun unloadModel(): String {
        bridge.cancel()
        val result = bridge.unload()
        loaded = null
        cpuValidated = false
        return result
    }

    private fun formatPrompt(
        messages: JSONArray,
        toolsJson: String,
        requireTool: Boolean = false,
    ): String {
        val roles = Array(messages.length()) { index -> messages.getJSONObject(index).getString("role") }
        val contents = Array(messages.length()) { index -> messages.getJSONObject(index).optString("content") }
        return bridge.formatChat(roles, contents, true, toolsJson, requireTool)
    }

    /**
     * Whether a reply reads as an attempt to call a tool that the parser did not accept.
     *
     * Only ever used to decide whether to ask again with the grammar forced — never to execute
     * anything. A false positive here costs one wasted generation; it cannot produce a tool call,
     * because the call that runs is the one the parser returns from the retry.
     */
    private fun looksLikeUnparsedCall(reply: String, toolsJson: String): Boolean {
        if (toolsJson.isBlank()) return false
        val names = runCatching {
            val array = JSONArray(toolsJson)
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
        }.getOrDefault(emptyList()).filter(String::isNotEmpty)
        // The name has to be followed by an opening bracket or brace, so a reply that merely
        // mentions a tool by name does not trigger a retry.
        return names.any { name ->
            Regex(Regex.escape(name) + """\s*[({\[]""").containsMatchIn(reply)
        }
    }

    private fun emit(callback: IInferenceCallback, requestId: String, event: JSONObject) {
        runCatching { callback.onEvent(requestId, event.toString()) }
    }

    private fun <T> runSerialized(block: () -> T): T {
        if (Thread.currentThread().name == "bram-local-inference") return block()
        return try {
            executor.submit<T> { block() }.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    private fun Throwable.rootMessage(): String {
        var current = this
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current.message ?: current::class.java.simpleName
    }

    private companion object {
        const val PROTOCOL_VERSION = 2
    }
}
