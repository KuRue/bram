package io.github.kurue.bram.core.domain

import kotlinx.coroutines.flow.Flow

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val requiredPermissions: Set<String> = emptySet(),
    val readOnly: Boolean = true,
    /**
     * The argument names that say what a call acts on, used to scope a remembered allowance.
     *
     * A permission granted for good has to be granted for something. "Always allow `run_command`"
     * with no target is a blanket grant to run anything ever again; "always allow `run_command`
     * with `command` = `git status`" is a decision someone can actually make. A tool names the
     * fields that identify its target here — a path, a recipient, a package — and the allowance is
     * remembered against those values.
     *
     * Empty means the tool has no meaningful target and an allowance covers every call to it,
     * which suits a tool that only reads.
     */
    val approvalScopeKeys: List<String> = emptyList(),
    /**
     * Whether this tool's result is content from outside, rather than something Bram produced.
     *
     * A fetched page, a search result or an MCP server's reply all put text into the conversation
     * that nobody in the conversation wrote. That matters because the model's next reply is
     * generated from it: text on a page saying "now call run_command" can come back out as
     * something that parses as a call. Bram only reads calls out of unmarked text on formats that
     * do not mark them, and that recovery is exactly where such an echo would land.
     *
     * Tools that only touch the device and the user's own data are not untrusted in this sense.
     * Their output can be wrong or surprising, but it does not carry an attacker's words.
     */
    val returnsUntrustedContent: Boolean = false,
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
    val isUsable: Boolean get() = endTags.isNotEmpty() && (startTag.isNotEmpty() || startsOpen)
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
    /** Prompt tokens covered by the KV cache kept from the previous turn; null when unknown. */
    val cachedPromptTokens: Int? = null,
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

/**
 * A configured remote MCP server: an HTTP(S) endpoint speaking the streamable-HTTP transport.
 *
 * Only that transport is supported, deliberately: it needs nothing beyond an HTTP client, which is
 * all an Android app can provide — the popular stdio transport spawns a child process, which Bram
 * cannot do (the platform blocks execve of app data, see the architecture notes), so a stdio server
 * could never run here even if it were wired up.
 */
data class McpServer(
    val id: String,
    val displayName: String,
    /** The endpoint the client talks to; requests never leave this origin once configured. */
    val baseUrl: String,
    /** The token sent as an `Authorization: Bearer` header, when configured. */
    val credentialAlias: String = "mcp-token",
    /** The URL stays http:// (LAN servers are common); prompts and results travel unencrypted. */
    val allowInsecureHttp: Boolean = false,
)
