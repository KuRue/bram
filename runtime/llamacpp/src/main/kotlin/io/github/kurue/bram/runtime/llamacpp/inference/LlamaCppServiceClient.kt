package io.github.kurue.bram.runtime.llamacpp.inference

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.FlashAttentionMode
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.HexFlags
import io.github.kurue.bram.core.domain.KvCacheType
import io.github.kurue.bram.core.domain.LoadMode
import io.github.kurue.bram.core.domain.ReasoningFormat
import io.github.kurue.bram.core.domain.GenerationMetrics
import io.github.kurue.bram.core.domain.StreamingMetrics
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ThreadPriority
import io.github.kurue.bram.core.domain.TokenUsage
import io.github.kurue.bram.core.domain.ToolCall
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

class LlamaCppServiceClient(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val connectMutex = Mutex()
    private val activeFailures = ConcurrentHashMap<String, (String) -> Unit>()
    @Volatile private var service: IInferenceService? = null
    @Volatile private var serviceBinder: IBinder? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile var processFailureListener: ((String) -> Unit)? = null

    suspend fun probe(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requireService().probe())
    }

    suspend fun state(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requireService().state())
    }

    /** Loads a small embedding GGUF into a resident CPU context in the inference process. */
    suspend fun loadEmbedder(modelPath: String, threads: Int): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requireService().loadEmbedder(modelPath, threads))
    }

    /**
     * Embeds one piece of text, or null if the inference process is unreachable or the call fails.
     * Null lets the memory store fall back to keyword recall rather than failing the turn.
     */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        runCatching { requireService().embed(text) }.getOrNull()
    }

    suspend fun unloadEmbedder(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requireService().unloadEmbedder())
    }

    suspend fun devices(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requireService().devices())
    }

    /**
     * Converts a GGUF to another quant on the device. Long-running: it occupies the inference
     * process for the duration, and the app should show progress while it runs.
     */
    suspend fun quantize(modelPath: String, outPath: String, ftype: String): JSONObject =
        withContext(Dispatchers.IO) {
            val request = JSONObject()
                .put("modelPath", modelPath)
                .put("outPath", outPath)
                .put("ftype", ftype)
            JSONObject(requireService().quantize(request.toString()))
        }

    suspend fun referenceDecode(tokenCount: Int, padTokens: Int = 0): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requireService().referenceDecode(tokenCount, padTokens))
    }

    suspend fun teacherForced(forcedTokens: IntArray, padTokens: Int = 0): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requireService().teacherForced(forcedTokens, padTokens))
    }

    private fun JSONObject.toReasoningFormat(): ReasoningFormat {
        val tags = optJSONArray("endTags")
        return ReasoningFormat(
            supportsThinking = optBoolean("supportsThinking"),
            startsOpen = optBoolean("forcedOpen"),
            startTag = optString("startTag"),
            endTags = (0 until (tags?.length() ?: 0)).mapNotNull { tags?.optString(it) }
                .filter(String::isNotEmpty),
        )
    }

    /** Splits a finished reply into its answer and any reasoning the format exposes. */
    suspend fun parseReply(reply: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requireService().parseReply(reply))
    }

    suspend fun load(
        model: LocalModelRecord,
        threads: Int,
        gpuLayers: Int = 0,
        deviceFilter: String = "",
        enableThinking: Boolean = false,
        flashAttention: FlashAttentionMode = FlashAttentionMode.AUTO,
        kvCacheType: KvCacheType = KvCacheType.F16,
        /** Prompt tokens per evaluation, or 0 for the llama.cpp default Bram always used. */
        batchTokens: Int = 0,
        /** Tokens between model evaluations, or 0 for the llama.cpp default of 128. */
        ubatchTokens: Int = 0,
        /** CPU cores for the generation threadpool as hex, "" for default affinity. */
        cpuMask: String = "",
        cpuStrict: Boolean = false,
        /** Threadpool polling 0–100, or -1 for the backend default. */
        poll: Int = -1,
        threadPriority: ThreadPriority = ThreadPriority.NORMAL,
        loadMode: LoadMode = LoadMode.AUTO,
        hexFlags: HexFlags = HexFlags(),
        /** Stream MoE experts from flash instead of loading them resident (for models past RAM). */
        streamExperts: Boolean = false,
        /** Resident expert-cache budget in MiB, or 0 for unbounded. */
        streamCacheMb: Int = 0,
        /** Pin the always-used weights in anon RAM so they survive memory pressure. */
        streamDenseAnon: Boolean = false,
        /** Overlap expert reads with compute via background reader lanes + the kernel wait hook. */
        streamOverlap: Boolean = false,
        /** Reader-lane count for overlap; 0 lets the native side pick its default. */
        streamOverlapLanes: Int = 0,
    ): JSONObject = withContext(Dispatchers.IO) {
        // Normalized before it reaches the service so the load identity compares concrete numbers:
        // "default" must mean the same thing on every request, or every call would force a reload.
        val effectiveBatch = if (batchTokens > 0) {
            minOf(batchTokens, model.preferredContextTokens)
        } else {
            minOf(512, model.preferredContextTokens)
        }
        val request = JSONObject()
            .put("modelId", model.id.value)
            .put("contentUri", model.contentUri)
            .put("localPath", model.localPath)
            .put("parts", JSONArray().also { array -> model.parts.forEach(array::put) })
            .put("fileSizeBytes", model.fileSizeBytes)
            .put("contextTokens", model.preferredContextTokens)
            .put("batchTokens", effectiveBatch)
            .put("ubatchTokens", if (ubatchTokens > 0) minOf(ubatchTokens, effectiveBatch) else 0)
            .put("threads", threads)
            .put("gpuLayers", gpuLayers)
            .put("deviceFilter", deviceFilter)
            .put("enableThinking", enableThinking)
            .put("flashAttention", flashAttention.wire)
            .put("kvCacheType", kvCacheType.wire)
            .put("cpuMask", cpuMask)
            .put("cpuStrict", cpuStrict)
            .put("poll", poll)
            .put("threadPriority", threadPriority.wire)
            .put("loadMode", loadMode.wire)
            .put("hexUseHmx", hexFlags.useHmx)
            .put("hexDisableNhvx", hexFlags.disableNhvx)
            .put("hexHostBuf", hexFlags.hostBuf)
            .put("hexOpBatch", hexFlags.opBatch)
            .put("hexNDev", hexFlags.nDev)
            .put("streamExperts", streamExperts)
            .put("streamCacheMb", streamCacheMb)
            .put("streamDenseAnon", streamDenseAnon)
            .put("streamOverlap", streamOverlap)
            .put("streamOverlapLanes", streamOverlapLanes)
        val result = JSONObject(requireService().load(request.toString()))
        if (result.optBoolean("restartRequired")) {
            android.util.Log.d("BramTune", "restartRequired: restarting the inference process")
            // The Hexagon environment is read once per process, so a request that changes it can
            // only be honored by a fresh inference process. Restart and retry exactly once.
            restartService()
            android.util.Log.d("BramTune", "restart done, rebinding")
            val retried = JSONObject(requireService().load(request.toString()))
            android.util.Log.d("BramTune", "retry load returned restartRequired=${retried.optBoolean("restartRequired")}")
            check(!retried.optBoolean("restartRequired")) {
                "The inference process could not apply the requested Hexagon configuration"
            }
            return@withContext retried
        }
        result
    }

    suspend fun unload(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requireService().unload())
    }

    /**
     * Kills the inference process so the next [requireService] starts one fresh. Public for the
     * tuning harness, which uses it to unwedge a process whose native call hung: the app-side
     * timeout ends the wait, but only a fresh process can serve the next candidate.
     */
    suspend fun restartInferenceProcess() = restartService()

    /**
     * Kills the inference process so the next [requireService] starts one fresh. The Hexagon
     * backend reads its environment once at registration, so process-level settings (the
     * `GGML_HEXAGON_*` flags) can only change this way.
     *
     * The unbind and the fresh bind must not race: binding while the old process is still
     * unwinding reuses it, and a reused process would answer the retry with `restartRequired`
     * again. The service schedules its own death before returning `restartRequired`, so wait for
     * its binder death (or a short timeout) before letting [requireService] bind again.
     */
    private suspend fun restartService() {
        val oldBinder = serviceBinder
        val oldConnection = connection
        val died = CompletableDeferred<Unit>()
        val deathWatch = IBinder.DeathRecipient { died.complete(Unit) }
        oldBinder?.let {
            runCatching { it.linkToDeath(deathWatch, 0) }
            runCatching { it.unlinkToDeath(deathRecipient, 0) }
        }
        service = null
        serviceBinder = null
        connection = null
        oldConnection?.let { runCatching { appContext.unbindService(it) } }
        runCatching { appContext.stopService(Intent(appContext, InferenceProcessService::class.java)) }
        // The service schedules its own death before returning `restartRequired`, so this wait
        // normally completes in well under a second; 8 seconds covers a slow binder teardown.
        withTimeoutOrNull(8_000) { died.await() }
    }

    suspend fun countTokens(messages: List<ConversationMessage>): Int = withContext(Dispatchers.IO) {
        requireService().countTokens(messagesRequest(messages).toString())
    }

    fun generate(request: GenerationRequest): Flow<GenerationEvent> = callbackFlow {
        val failure: (String) -> Unit = { message ->
            trySend(GenerationEvent.Failed(message, recoverable = true))
            close()
        }
        activeFailures[request.requestId] = failure
        val callback = object : IInferenceCallback.Stub() {
            override fun onEvent(requestId: String, eventJson: String) {
                val event = runCatching { JSONObject(eventJson) }.getOrElse { error ->
                    failure(error.message ?: "Invalid inference event")
                    return
                }
                when (event.optString("type")) {
                    "started" -> trySend(
                        GenerationEvent.Started(
                            runtimeDescription = event.optString("runtimeDescription", "Local CPU"),
                            reasoningFormat = event.optJSONObject("chatFormat")?.toReasoningFormat(),
                        ),
                    )
                    "textDelta" -> trySend(GenerationEvent.TextDelta(event.optString("text")))
                    "toolCalls" -> {
                        val calls = event.optJSONArray("calls")
                        for (index in 0 until (calls?.length() ?: 0)) {
                            val call = calls?.optJSONObject(index) ?: continue
                            trySend(
                                GenerationEvent.ToolCallReady(
                                    ToolCall(
                                        // llama.cpp leaves the id empty for formats that have no
                                        // notion of one, and the loop needs it to match a result
                                        // back to its call.
                                        id = call.optString("id").ifBlank { "call_$index" },
                                        name = call.optString("name"),
                                        argumentsJson = call.optString("arguments").ifBlank { "{}" },
                                        recovered = call.optBoolean("recovered"),
                                    ),
                                ),
                            )
                        }
                    }
                    "usage" -> trySend(
                        GenerationEvent.Usage(
                            TokenUsage(
                                inputTokens = event.optInt("inputTokens"),
                                outputTokens = event.optInt("outputTokens"),
                            ),
                        ),
                    )
                    "metrics" -> trySend(
                        GenerationEvent.Metrics(
                            GenerationMetrics(
                                promptTokens = event.optInt("promptTokens"),
                                outputTokens = event.optInt("outputTokens"),
                                promptMillis = event.optLong("promptMillis"),
                                decodeMillis = event.optLong("decodeMillis"),
                                processPssBytes = event.optLong("processPssBytes").takeIf { it > 0 },
                                cachedPromptTokens = event.optInt("cachedPromptTokens").takeIf { it > 0 },
                                streaming = if (event.optBoolean("streaming")) {
                                    StreamingMetrics(
                                        flashMiB = event.optLong("streamFlashMiB"),
                                        residentMiB = event.optLong("streamResidentMiB"),
                                        evictions = event.optLong("streamEvictions"),
                                        denseMiB = event.optLong("streamDenseMiB"),
                                    )
                                } else {
                                    null
                                },
                            ),
                        ),
                    )
                    "finished" -> {
                        trySend(GenerationEvent.Finished(event.optString("finishReason", "stop")))
                        close()
                    }
                    "failed" -> {
                        trySend(
                            GenerationEvent.Failed(
                                message = event.optString("message", "Local inference failed"),
                                recoverable = event.optBoolean("recoverable", true),
                            ),
                        )
                        close()
                    }
                }
            }
        }

        try {
            val service = requireService()
            service.generate(
                request.requestId,
                messagesRequest(request.messages)
                    // The tools the run is offering. Without these the template never mentions a
                    // tool and the model cannot call one, which is what it used to do.
                    .put(
                        "tools",
                        JSONArray().also { array ->
                            request.tools.forEach { tool ->
                                array.put(
                                    JSONObject()
                                        .put("name", tool.name)
                                        .put("description", tool.description)
                                        .put(
                                            "parameters",
                                            runCatching { JSONObject(tool.inputSchemaJson) }
                                                .getOrElse { JSONObject() },
                                        ),
                                )
                            }
                        },
                    )
                    .put("maxOutputTokens", request.maxOutputTokens)
                    .put("temperature", request.sampler.temperature)
                    .put("topP", request.sampler.topP)
                    .put("topK", request.sampler.topK)
                    .put("repeatPenalty", request.sampler.repeatPenalty)
                    .put("repeatLastTokens", request.sampler.repeatLastTokens)
                    .toString(),
                callback,
            )
        } catch (error: Throwable) {
            failure(error.message ?: "Could not connect to the inference process")
        }

        awaitClose {
            activeFailures.remove(request.requestId)
            runCatching { service?.cancel(request.requestId) }
        }
    }

    suspend fun cancel(requestId: String) = withContext(Dispatchers.IO) {
        service?.cancel(requestId)
    }

    override fun close() {
        activeFailures.values.forEach { it("Inference service closed") }
        activeFailures.clear()
        serviceBinder?.unlinkToDeath(deathRecipient, 0)
        connection?.let { runCatching { appContext.unbindService(it) } }
        service = null
        serviceBinder = null
        connection = null
        processFailureListener = null
    }

    private suspend fun requireService(): IInferenceService {
        service?.let { return it }
        return connectMutex.withLock {
            service?.let { return@withLock it }
            bind()
        }
    }

    private suspend fun bind(): IInferenceService = suspendCancellableCoroutine { continuation ->
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    if (connection === this) connection = null
                    runCatching { appContext.unbindService(this) }
                    if (continuation.isActive) continuation.resumeWithException(
                        IllegalStateException("Inference service returned no binder"),
                    )
                    return
                }
                val typed = IInferenceService.Stub.asInterface(binder)
                service = typed
                serviceBinder = binder
                connection = this
                runCatching { binder.linkToDeath(deathRecipient, 0) }
                if (continuation.isActive) continuation.resume(typed)
            }

            override fun onServiceDisconnected(name: ComponentName?) = disconnected(
                "The inference process stopped. Reload the model to continue.",
            )

            override fun onBindingDied(name: ComponentName?) = disconnected(
                "The inference process binding died. Reload the model to continue.",
            )

            override fun onNullBinding(name: ComponentName?) {
                if (connection === this) connection = null
                runCatching { appContext.unbindService(this) }
                if (continuation.isActive) continuation.resumeWithException(
                    IllegalStateException("Inference service rejected the binding"),
                )
            }
        }
        connection = newConnection
        val didBind = AtomicBoolean(false)
        continuation.invokeOnCancellation {
            if (didBind.get() && connection === newConnection) {
                connection = null
                runCatching { appContext.unbindService(newConnection) }
            }
        }
        val bound = appContext.bindService(
            Intent(appContext, InferenceProcessService::class.java),
            newConnection,
            Context.BIND_AUTO_CREATE,
        )
        didBind.set(bound)
        if (!continuation.isActive && bound && connection === newConnection) {
            connection = null
            runCatching { appContext.unbindService(newConnection) }
        }
        if (!bound && continuation.isActive) {
            connection = null
            continuation.resumeWithException(IllegalStateException("Could not bind the inference service"))
        }
    }

    private val deathRecipient = IBinder.DeathRecipient {
        disconnected("The native inference process crashed. Bram's UI and chat are still available; reload to retry safely.")
    }

    @Synchronized
    private fun disconnected(message: String) {
        if (service == null && serviceBinder == null && connection == null) return
        runCatching { serviceBinder?.unlinkToDeath(deathRecipient, 0) }
        val oldConnection = connection
        service = null
        serviceBinder = null
        connection = null
        oldConnection?.let { runCatching { appContext.unbindService(it) } }
        activeFailures.values.forEach { it(message) }
        activeFailures.clear()
        processFailureListener?.invoke(message)
    }

    private fun messagesRequest(messages: List<ConversationMessage>): JSONObject = JSONObject().put(
        "messages",
        JSONArray().also { array ->
            messages.forEach { message ->
                array.put(
                    JSONObject()
                        .put("role", message.role.name.lowercase())
                        .put("content", message.content),
                )
            }
        },
    )
}
