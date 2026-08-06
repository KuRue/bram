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
    private var loadedModelId: String? = null
    private var loadedContextTokens: Int = 0
    private var loadedGpuLayers: Int = 0
    private var loadedDeviceFilter: String = ""
    private var loadedThinking: Boolean = false
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

        override fun referenceDecode(tokenCount: Int): String = runSerialized {
            bridge.referenceDecode(tokenCount)
        }

        override fun teacherForced(forcedTokens: IntArray?): String = runSerialized {
            bridge.teacherForced(forcedTokens ?: IntArray(0))
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
                    check(loadedModelId != null) { "Load a local model before generating" }
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
                            .put("runtimeDescription", "Local CPU · ${loadedContextTokens} token context")
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
                    val toolCalls = runCatching {
                        JSONObject(bridge.parseReply(reply.toString())).optJSONArray("toolCalls")
                    }.getOrNull()
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
                .put("loadedModelId", loadedModelId)
                .put("contextTokens", loadedContextTokens)
                .put("cpuValidated", cpuValidated)
                .toString()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        bridge.cancel()
        requests.values.forEach { it.cancel(true) }
        requests.clear()
        runCatching { executor.submit<String> { unloadModel() }.get() }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun loadModel(request: JSONObject): String {
        val modelId = request.getString("modelId")
        val contextTokens = request.getInt("contextTokens").coerceAtLeast(256)
        val gpuLayers = request.optInt("gpuLayers", 0).coerceAtLeast(0)
        val deviceFilter = request.optString("deviceFilter")
        val enableThinking = request.optBoolean("enableThinking", false)
        // The offload plan is part of the load identity: reusing a CPU-resident model for a GPU
        // request would silently validate the accelerator against itself.
        if (modelId == loadedModelId &&
            contextTokens == loadedContextTokens &&
            gpuLayers == loadedGpuLayers &&
            deviceFilter == loadedDeviceFilter &&
            enableThinking == loadedThinking &&
            cpuValidated
        ) {
            return JSONObject(bridge.state())
                .put("alreadyLoaded", true)
                .put("modelId", modelId)
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
            val loadResult = JSONObject(
                bridge.load(
                    modelPath = modelFile.absolutePath,
                    contextTokens = contextTokens,
                    batchTokens = request.optInt("batchTokens", 512).coerceIn(32, contextTokens),
                    threads = request.optInt("threads", Runtime.getRuntime().availableProcessors())
                        .coerceIn(1, Runtime.getRuntime().availableProcessors()),
                    gpuLayers = gpuLayers,
                    deviceFilter = deviceFilter,
                    enableThinking = enableThinking,
                ),
            )
            val validation = JSONObject(bridge.selfTest())
            check(validation.optBoolean("passed")) {
                validation.optString("detail", "Native CPU correctness self-test failed")
            }
            loadedModelId = modelId
            loadedContextTokens = contextTokens
            loadedGpuLayers = gpuLayers
            loadedDeviceFilter = deviceFilter
            loadedThinking = enableThinking
            cpuValidated = true
            return loadResult
                .put("alreadyLoaded", false)
                .put("modelId", modelId)
                .put("cpuValidated", true)
                .put("processPssBytes", Debug.getPss().toLong() * 1_024L)
                .put("selfTest", validation)
                .toString()
        } catch (error: Throwable) {
            runCatching { bridge.unload() }
            loadedModelId = null
            loadedContextTokens = 0
            loadedGpuLayers = 0
            loadedDeviceFilter = ""
            cpuValidated = false
            throw error
        }
    }

    private fun unloadModel(): String {
        bridge.cancel()
        val result = bridge.unload()
        loadedModelId = null
        loadedContextTokens = 0
        loadedGpuLayers = 0
        loadedDeviceFilter = ""
        cpuValidated = false
        return result
    }

    private fun formatPrompt(messages: JSONArray, toolsJson: String): String {
        val roles = Array(messages.length()) { index -> messages.getJSONObject(index).getString("role") }
        val contents = Array(messages.length()) { index -> messages.getJSONObject(index).optString("content") }
        return bridge.formatChat(roles, contents, true, toolsJson)
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
