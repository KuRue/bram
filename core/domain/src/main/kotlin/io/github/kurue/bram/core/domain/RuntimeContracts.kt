package io.github.kurue.bram.core.domain

import kotlinx.coroutines.flow.Flow

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val requiredPermissions: Set<String> = emptySet(),
    val readOnly: Boolean = true,
)

data class GenerationRequest(
    val messages: List<ConversationMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val maxOutputTokens: Int = 1_024,
    /** How to sample. Carried whole, since the settings only make sense together. */
    val sampler: SamplerSettings = SamplerSettings(),
    val requestId: String,
)

/**
 * The reasoning markers of the chat format a runtime is actually using.
 *
 * Reported by the runtime rather than assumed, because the tags differ per format — `<think>`,
 * `[THINK]`, `<|channel|>analysis<|message|>` and `<mm:think>` are all in use, and some formats
 * close with more than one. [startsOpen] says the prompt already opened the block, so the model's
 * own output contains only the close; guessing at that from a reasoning setting is what it replaces.
 */
data class ReasoningFormat(
    val supportsThinking: Boolean = false,
    val startsOpen: Boolean = false,
    val startTag: String = "",
    val endTags: List<String> = emptyList(),
) {
    val isUsable: Boolean get() = startTag.isNotEmpty() && endTags.isNotEmpty()
}

data class TokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
) {
    val totalTokens: Int?
        get() = if (inputTokens != null && outputTokens != null) inputTokens + outputTokens else null
}

data class GenerationMetrics(
    val promptTokens: Int,
    val outputTokens: Int,
    val promptMillis: Long,
    val decodeMillis: Long,
    val processPssBytes: Long? = null,
) {
    val promptTokensPerSecond: Double?
        get() = promptMillis.takeIf { it > 0 }?.let { promptTokens * 1_000.0 / it }

    val decodeTokensPerSecond: Double?
        get() = decodeMillis.takeIf { it > 0 }?.let { outputTokens * 1_000.0 / it }
}

sealed interface GenerationEvent {
    data class Started(
        val runtimeDescription: String,
        /** Absent for runtimes that do not report one, such as a remote endpoint. */
        val reasoningFormat: ReasoningFormat? = null,
    ) : GenerationEvent
    data class TextDelta(val text: String) : GenerationEvent
    data class ToolCallReady(val call: ToolCall) : GenerationEvent
    data class Usage(val usage: TokenUsage) : GenerationEvent
    data class Metrics(val metrics: GenerationMetrics) : GenerationEvent
    data class Finished(val finishReason: String? = null) : GenerationEvent
    data class Failed(
        val message: String,
        val recoverable: Boolean,
        val cause: Throwable? = null,
    ) : GenerationEvent
}

data class RuntimeAvailability(
    val available: Boolean,
    val summary: String,
    val detail: String? = null,
)

interface ModelRuntime {
    val model: ModelDescriptor

    suspend fun availability(): RuntimeAvailability

    /**
     * Returns null until a runtime-specific tokenizer is available. Callers must then use a
     * conservative estimator rather than assume a count of zero.
     */
    suspend fun countTokens(messages: List<ConversationMessage>): Int? = null

    fun generate(request: GenerationRequest): Flow<GenerationEvent>

    suspend fun cancel(requestId: String) = Unit
}

fun interface EndpointCredentialResolver {
    suspend fun resolve(endpointId: String, credentialAlias: String): String?
}

interface RemoteEndpointStore {
    suspend fun list(): List<RemoteEndpoint>
    suspend fun upsert(endpoint: RemoteEndpoint, apiKey: String?)
    suspend fun remove(endpointId: String)
    suspend fun resolveCredential(endpointId: String, credentialAlias: String): String?
}
