package io.github.kurue.bram.core.domain

import kotlinx.coroutines.flow.Flow

enum class MemoryKind {
    WORKING_SUMMARY,
    SEMANTIC_FACT,
    EPISODE,
    USER_INSTRUCTION,
}

data class MemoryRecord(
    val id: String,
    val kind: MemoryKind,
    val text: String,
    val importance: Double = 0.5,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val sourceMessageIds: List<MessageId> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
)

interface MemoryStore {
    suspend fun workingSummary(conversationId: ConversationId): MemoryRecord?
    suspend fun search(conversationId: ConversationId, query: String, limit: Int): List<MemoryRecord>
    suspend fun put(conversationId: ConversationId, memory: MemoryRecord)
}

interface ToolHandler {
    val definition: ToolDefinition
    suspend fun execute(argumentsJson: String): String
}

interface ToolRegistry {
    fun definitions(): List<ToolDefinition>
    fun find(name: String): ToolHandler?
}

/**
 * Versioned identity supplied to the runtime for an agent run.
 *
 * Keeping identity out of the model adapter lets Bram evolve independently of GGUF, LiteRT,
 * or remote providers, while the version gives future prompt and KV caches a stable key.
 */
data class AgentIdentity(
    val id: String,
    val version: String,
    val displayName: String,
    val systemPrompt: String,
)

enum class ToolApprovalDecision {
    ALLOW_ONCE,
    ALLOW_FOR_RUN,
    DENY,
}

fun interface ToolApprovalGate {
    suspend fun decide(tool: ToolDefinition, argumentsJson: String): ToolApprovalDecision
}

data class AgentRunRequest(
    val conversationId: ConversationId,
    val messages: List<ConversationMessage>,
    val identity: AgentIdentity,
    val maxOutputTokens: Int = 1_024,
    val maxToolTurns: Int = 6,
    val memoryQuery: String = messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty(),
)

sealed interface AgentEvent {
    data class Status(val text: String) : AgentEvent
    data class ContextPrepared(
        val estimatedInputTokens: Int,
        val omittedMessageCount: Int,
    ) : AgentEvent
    /**
     * The reasoning markers the runtime is using, reported before any text arrives so a partial
     * reply can be split with the loaded format's own tags rather than an assumed `<think>`.
     */
    data class Reasoning(val format: ReasoningFormat) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ToolStarted(val call: ToolCall) : AgentEvent
    data class ToolFinished(val call: ToolCall, val result: String) : AgentEvent
    data class Usage(val usage: TokenUsage) : AgentEvent
    data class Metrics(val metrics: GenerationMetrics) : AgentEvent
    data class Completed(val message: ConversationMessage) : AgentEvent
    data class Failed(val message: String, val recoverable: Boolean) : AgentEvent
}

interface AgentOrchestrator {
    fun run(request: AgentRunRequest, runtime: ModelRuntime): Flow<AgentEvent>
}

enum class SkillLifecycle {
    DRAFT,
    ACTIVE,
    DISABLED,
    QUARANTINED,
}

data class SkillManifest(
    val id: String,
    val version: String,
    val displayName: String,
    val description: String,
    val author: String,
    val lifecycle: SkillLifecycle = SkillLifecycle.DRAFT,
    val requiredTools: Set<String> = emptySet(),
    val requestedPermissions: Set<String> = emptySet(),
    val minimumContextTokens: Int? = null,
)

interface SkillRepository {
    suspend fun listActive(): List<SkillManifest>
    suspend fun stageDraft(manifest: SkillManifest, instructions: String)
    suspend fun activate(skillId: String, version: String)
    suspend fun disable(skillId: String)
}

enum class AutomationScheduleKind {
    ONE_SHOT,
    INTERVAL,
    CRON,
}

data class AutomationSpec(
    val id: String,
    val displayName: String,
    val scheduleKind: AutomationScheduleKind,
    val scheduleExpression: String,
    val agentPrompt: String,
    val modelPolicyId: String,
    val requiresNetwork: Boolean = false,
    val requiresCharging: Boolean = false,
    val maxRunSeconds: Long = 600,
    val maxToolTurns: Int = 6,
    val enabled: Boolean = true,
)

interface AutomationScheduler {
    suspend fun schedule(spec: AutomationSpec)
    suspend fun cancel(automationId: String)
}
