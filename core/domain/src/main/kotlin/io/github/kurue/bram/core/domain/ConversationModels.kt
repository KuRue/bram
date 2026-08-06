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
    /**
     * Whether this was recovered from a reply that did not mark it as a call.
     *
     * A recovered call is the model's text read as an intent rather than the format saying so, and
     * text can be echoed from somewhere untrusted. It is always asked about: it never matches a
     * remembered allowance and never rides on one granted earlier in the run.
     */
    val recovered: Boolean = false,
)

/**
 * Work an agent did on the way to an answer.
 *
 * Kept beside the reply rather than folded into its text: reasoning and tool traffic are useful
 * when inspected but drown the answer when shown inline, so the transcript can collapse them to a
 * single line and let the reader open what matters.
 */
sealed interface AgentActivity {
    val summary: String

    /**
     * Model reasoning, when the runtime can separate it from the reply.
     *
     * [inProgress] is set while the model is still reasoning, so the transcript can say so as it
     * happens rather than only once the block closes — which on a slow device can be a long wait
     * with nothing on screen to explain it.
     */
    data class Thinking(
        val text: String,
        val durationMillis: Long = 0,
        val inProgress: Boolean = false,
    ) : AgentActivity {
        override val summary: String
            get() = when {
                inProgress -> "Thinking…"
                durationMillis > 0 -> "Thought for ${durationMillis / 1000}s"
                else -> "Thought about this"
            }
    }

    /** A tool invocation and, once it returns, its result. [result] is null while in flight. */
    data class ToolInvocation(
        val id: String,
        val name: String,
        val argumentsJson: String,
        val result: String? = null,
        val failed: Boolean = false,
    ) : AgentActivity {
        override val summary: String
            get() = when {
                result == null -> "Calling $name…"
                failed -> "$name failed"
                else -> "Called $name"
            }
    }
}

data class ConversationMessage(
    val id: MessageId = MessageId.new(),
    val role: MessageRole,
    val content: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    /** Reasoning and tool steps that produced [content], in the order they happened. */
    val activity: List<AgentActivity> = emptyList(),
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
