package io.github.kurue.bram.runtime.litertlm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.MessageCallback
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.LiteRtBackend
import io.github.kurue.bram.core.domain.LiteRtModelRecord
import io.github.kurue.bram.core.domain.ToolCall
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Owns the one LiteRT-LM engine the app keeps resident, mirroring how the llama.cpp service holds
 * its single loaded model: a route only ever runs a package that was explicitly loaded here.
 *
 * The engine is expensive to bring up (model load plus per-device kernel compilation, seconds on a
 * phone), so it stays alive between turns and between routing decisions, and is torn down only on
 * unload. The compiled kernels live in a per-package cache directory — the engine's `cacheDir` —
 * so a second load of the same package reuses the compiled work instead of redoing it.
 *
 * Each generate call gets a brand-new [Conversation]: Bram replays the planned messages into it,
 * which keeps this manager stateless across turns. A failed attempt leaves nothing half-sent, and
 * cancellation does not have to roll native state back.
 */
class LiteRtEngineManager(context: Context) {
    private val cacheRoot = File(context.applicationContext.filesDir, "litert-cache")

    private val engineRef = AtomicReference<Engine?>(null)
    private val conversationRef = AtomicReference<Conversation?>(null)

    @Volatile
    var loadedRecordId: String? = null
        private set

    @Volatile
    var loadedBackend: LiteRtBackend? = null
        private set

    val isLoaded: Boolean
        get() = loadedRecordId != null && engineRef.get()?.isInitialized() == true

    /** What the active engine would call itself in a status line, e.g. "LiteRT-LM · GPU". */
    val runtimeDescription: String
        get() = "LiteRT-LM · ${loadedBackend?.label ?: "?"}"

    /**
     * Loads [record] onto its chosen backend, replacing whatever is resident.
     *
     * Blocks for as long as the engine takes to initialize — seconds on a phone — so it must run
     * off the main thread. The multi-token-prediction (speculative decoding) flag is set to match
     * the backend: it is the recommended setting on GPU and deliberately off on CPU.
     *
     * @throws Throwable when the package cannot be loaded; the message is the user-facing reason.
     */
    fun load(record: LiteRtModelRecord) {
        unload()
        val cacheDir = File(cacheRoot, record.id.value).also(File::mkdirs)
        @OptIn(ExperimentalApi::class)
        ExperimentalFlags.enableSpeculativeDecoding = record.backend == LiteRtBackend.GPU
        val engine = Engine(
            EngineConfig(
                modelPath = record.localPath,
                backend = record.backend.toLiteRt(),
                cacheDir = cacheDir.absolutePath,
            ),
        )
        engine.initialize()
        engineRef.set(engine)
        loadedRecordId = record.id.value
        loadedBackend = record.backend
    }

    /** Tears the engine down. Safe to call when nothing is loaded. */
    fun unload() {
        runCatching { conversationRef.getAndSet(null)?.close() }
        runCatching { engineRef.getAndSet(null)?.close() }
        loadedRecordId = null
        loadedBackend = null
    }

    /**
     * One turn on the resident engine: a fresh conversation replays the planned history and sends
     * the last message, streaming text and tool calls as they arrive. The conversation is closed
     * when the flow completes, fails, or is cancelled.
     *
     * The AAR's own `sendMessageAsync(...): Flow` crashes on completion (its bytecode calls a
     * `SendChannel.close$default` bridge that only exists in coroutines 1.11.0, while the AAR is
     * published against 1.9.0), so the callback overload is used and wrapped in our own
     * `callbackFlow` — the channel's `close()` then compiles against the app's coroutines and is
     * binary-safe (upstream-acknowledged workaround for google-ai-edge/litert-lm#2812).
     */
    fun generate(request: GenerationRequest): Flow<GenerationEvent> = callbackFlow {
        val engine = engineRef.get()
        if (engine == null || !engine.isInitialized()) {
            trySend(
                GenerationEvent.Failed(
                    message = "No LiteRT-LM package is loaded. Load one from Models before sending a message.",
                    recoverable = true,
                ),
            )
            close()
            return@callbackFlow
        }
        val turn = LiteRtTurn.from(request)
        if (turn == null) {
            trySend(GenerationEvent.Failed(message = "Nothing to send to the model", recoverable = true))
            close()
            return@callbackFlow
        }
        val conversation = try {
            engine.createConversation(
                ConversationConfig(
                    systemInstruction = turn.systemInstruction,
                    initialMessages = turn.replay,
                    tools = turn.tools,
                    samplerConfig = turn.sampler,
                    // Tool execution is the agent loop's job: the model's calls come back as
                    // events and run through the approval gate, never executed by the engine.
                    automaticToolCalling = false,
                    maxOutputToken = turn.maxOutputTokens,
                ),
            )
        } catch (error: Throwable) {
            trySend(
                GenerationEvent.Failed(
                    message = "Could not start a conversation: ${error.message ?: "unknown error"}",
                    recoverable = true,
                    cause = error,
                ),
            )
            close()
            return@callbackFlow
        }
        conversationRef.set(conversation)
        trySend(GenerationEvent.Started(runtimeDescription = runtimeDescription, reasoningFormat = null))
        val callback = object : MessageCallback {
            override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                message.contents.contents.forEach { content ->
                    if (content is Content.Text) {
                        trySend(GenerationEvent.TextDelta(content.text))
                    }
                }
                message.toolCalls.forEachIndexed { index, call ->
                    trySend(
                        GenerationEvent.ToolCallReady(
                            ToolCall(
                                id = "call_$index",
                                name = call.name,
                                argumentsJson = LiteRtLmRequests.toolCallArgumentsJson(call),
                            ),
                        ),
                    )
                }
            }

            override fun onDone() {
                trySend(GenerationEvent.Finished(finishReason = "stop"))
                close()
            }

            override fun onError(error: Throwable) {
                trySend(
                    GenerationEvent.Failed(
                        message = error.message ?: "LiteRT-LM inference failed",
                        recoverable = true,
                        cause = error,
                    ),
                )
                close()
            }
        }
        try {
            conversation.sendMessageAsync(
                turn.lastMessage,
                callback,
                maxOutputToken = turn.maxOutputTokens,
                repetitionPenaltyConfig = turn.repetitionPenalty,
            )
        } catch (error: CancellationException) {
            runCatching { conversation.cancelProcess() }
            throw error
        } catch (error: Throwable) {
            trySend(
                GenerationEvent.Failed(
                    message = error.message ?: "LiteRT-LM inference failed",
                    recoverable = true,
                    cause = error,
                ),
            )
            close()
        }
        // Keep the channel open until the callback closes it; if the collector goes away first,
        // stop the native decode and close the conversation.
        awaitClose {
            conversationRef.set(null)
            runCatching { conversation.cancelProcess() }
            runCatching { conversation.close() }
        }
    }

    /** Stops the in-flight turn, if any. */
    fun cancel() {
        runCatching { conversationRef.get()?.cancelProcess() }
    }

    private fun LiteRtBackend.toLiteRt(): Backend = when (this) {
        LiteRtBackend.CPU -> Backend.CPU()
        LiteRtBackend.GPU -> Backend.GPU()
    }
}
