package io.github.kurue.bram.runtime.llamacpp.inference

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.ReasoningFormat
import io.github.kurue.bram.core.domain.GenerationMetrics
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.TokenUsage
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    suspend fun devices(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requireService().devices())
    }

    suspend fun referenceDecode(tokenCount: Int): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requireService().referenceDecode(tokenCount))
    }

    suspend fun teacherForced(forcedTokens: IntArray): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requireService().teacherForced(forcedTokens))
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
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = JSONObject()
            .put("modelId", model.id.value)
            .put("contentUri", model.contentUri)
            .put("localPath", model.localPath)
            .put("fileSizeBytes", model.fileSizeBytes)
            .put("contextTokens", model.preferredContextTokens)
            .put("batchTokens", minOf(512, model.preferredContextTokens))
            .put("threads", threads)
            .put("gpuLayers", gpuLayers)
            .put("deviceFilter", deviceFilter)
            .put("enableThinking", enableThinking)
        JSONObject(requireService().load(request.toString()))
    }

    suspend fun unload(): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(requireService().unload())
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
