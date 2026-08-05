package io.github.kurue.bram.core.domain

import java.util.UUID

@JvmInline
value class ModelId(val value: String)

@JvmInline
value class MessageId(val value: String) {
    companion object {
        fun new(): MessageId = MessageId(UUID.randomUUID().toString())
    }
}

@JvmInline
value class ConversationId(val value: String)

enum class MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class ConversationMessage(
    val id: MessageId = MessageId.new(),
    val role: MessageRole,
    val content: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

/**
 * Enough of a conversation to list it without reading its messages, which matters once a device
 * holds a long history.
 */
data class ConversationSummary(
    val id: ConversationId,
    val title: String,
    val updatedAtEpochMillis: Long,
    val messageCount: Int,
)

enum class ModelCapability {
    TEXT,
    IMAGE_INPUT,
    TOOL_CALLING,
    JSON_SCHEMA,
    EMBEDDINGS,
}

enum class ModelLocation {
    LOCAL,
    REMOTE,
}

data class ModelDescriptor(
    val id: ModelId,
    val displayName: String,
    val providerName: String,
    val modelName: String,
    val location: ModelLocation,
    val contextWindowTokens: Int,
    val capabilities: Set<ModelCapability> = setOf(ModelCapability.TEXT),
)

enum class RemoteApiKind {
    CHAT_COMPLETIONS,
    RESPONSES,
}

data class RemoteEndpoint(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val modelName: String,
    val apiKind: RemoteApiKind = RemoteApiKind.CHAT_COMPLETIONS,
    val contextWindowTokens: Int = 32_768,
    val supportsToolCalling: Boolean = true,
    val allowInsecureHttp: Boolean = false,
    val credentialAlias: String = "endpoint-api-key",
) {
    fun asModelDescriptor(): ModelDescriptor = ModelDescriptor(
        id = ModelId("remote:$id:$modelName"),
        displayName = "$displayName — $modelName",
        providerName = displayName,
        modelName = modelName,
        location = ModelLocation.REMOTE,
        contextWindowTokens = contextWindowTokens,
        capabilities = buildSet {
            add(ModelCapability.TEXT)
            if (supportsToolCalling) add(ModelCapability.TOOL_CALLING)
        },
    )
}
