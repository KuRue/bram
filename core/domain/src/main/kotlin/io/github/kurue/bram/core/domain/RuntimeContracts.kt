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
    val temperature: Double = 0.7,
    val requestId: String,
)

data class TokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
) {
    val totalTokens: Int?
        get() = if (inputTokens != null && outputTokens != null) inputTokens + outputTokens else null
}

sealed interface GenerationEvent {
    data class Started(val runtimeDescription: String) : GenerationEvent
    data class TextDelta(val text: String) : GenerationEvent
    data class ToolCallReady(val call: ToolCall) : GenerationEvent
    data class Usage(val usage: TokenUsage) : GenerationEvent
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
