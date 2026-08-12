package io.github.kurue.bram.core.domain

import kotlinx.coroutines.flow.Flow

enum class MemoryKind {
    WORKING_SUMMARY,
    SEMANTIC_FACT,
    EPISODE,
    USER_INSTRUCTION,
}

/**
 * The most episodes a single conversation keeps. Episodes are produced every substantial turn, so
 * without a cap a long-running conversation would let them dominate the store and add recall noise;
 * the store trims to the newest [MAX_EPISODES_PER_CONVERSATION] after each episode is written.
 */
const val MAX_EPISODES_PER_CONVERSATION = 20

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
    /** Searches every conversation, for the agent's own recall tool and for diagnostics. */
    suspend fun searchAll(query: String, limit: Int): List<MemoryRecord>
    suspend fun put(conversationId: ConversationId, memory: MemoryRecord)
    /** Newest memories first across every conversation, for the memory browser. */
    suspend fun recent(limit: Int): List<MemoryRecord>
    /** Highest-importance memories first across every conversation, for the standing context. */
    suspend fun mostImportant(limit: Int): List<MemoryRecord>
    /** Drops a memory by id; safe to call with an id that is no longer present. */
    suspend fun remove(id: String)
}

/**
 * Renders a small, char-budgeted block of the user's standing memories into the system prompt, so
 * the agent always knows the durable facts and instructions without having to call memory_search.
 * Working summaries are excluded: they are a per-conversation scratchpad that already has its own
 * slot in the context head. Mirrors [SkillPrompt].
 */
object MemoryPrompt {
    fun append(prompt: String, memories: List<MemoryRecord>): String {
        val standing = memories.filter { it.kind != MemoryKind.WORKING_SUMMARY }
        val section = section(standing)
        return if (section.isBlank()) prompt else "$prompt\n\n$section"
    }

    fun section(memories: List<MemoryRecord>): String {
        if (memories.isEmpty()) return ""
        val budget = MAX_MEMORY_PROMPT_CHARS
        return buildString {
            appendLine("WHAT YOU REMEMBER")
            appendLine(
                "Standing facts about the user and instructions gathered from earlier " +
                    "conversations. Treat them as true and act on them, unless the user says " +
                    "otherwise this turn.",
            )
            for (memory in memories) {
                val label = when (memory.kind) {
                    MemoryKind.USER_INSTRUCTION -> "Instruction"
                    MemoryKind.SEMANTIC_FACT -> "Fact"
                    MemoryKind.EPISODE -> "Episode"
                    MemoryKind.WORKING_SUMMARY -> null
                } ?: continue
                val block = "$label: ${memory.text}"
                if (length + block.length > budget) break
                appendLine()
                append(block)
            }
        }
    }

    /** Caps the standing-memory block so it never crowds the context window. ~1k tokens. */
    const val MAX_MEMORY_PROMPT_CHARS = 4_000
}

/** Where an agent run stands, for the persistent run journal. */
enum class RunStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}

/**
 * One entry of the run journal: a turn (or a task run) from start to finish, with what it did and
 * how much it cost. Written by the orchestrator around every run so there is a record of agent
 * activity that survives the app — the provenance half of the memory story.
 */
data class RunJournalEntry(
    val id: String,
    val conversationId: ConversationId,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val status: RunStatus,
    val toolTurns: Int = 0,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val error: String? = null,
)

interface RunJournal {
    suspend fun upsert(entry: RunJournalEntry)
}

object NoopRunJournal : RunJournal {
    override suspend fun upsert(entry: RunJournalEntry) = Unit
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
    /** Remembered past the end of the run, until the user withdraws it. */
    ALLOW_ALWAYS,
    DENY,
}

/**
 * How a conversation asks before running a tool.
 *
 * A session-level choice rather than a global one: a quick lookup and an unattended long-running
 * task want different defaults, and the conversation is the unit of a task. AUTO is the quiet
 * option that still guards side effects; MANUAL asks about every call, including read-only ones,
 * which is the mode for watching a tool closely; BYPASS skips the gate entirely, for a run the
 * user has decided to trust outright.
 */
enum class PermissionMode(val wire: String) {
    AUTO("auto"),
    MANUAL("manual"),
    BYPASS("bypass");

    val label: String
        get() = when (this) {
            AUTO -> "Auto"
            MANUAL -> "Manual"
            BYPASS -> "Bypass"
        }

    companion object {
        fun fromWire(value: String?): PermissionMode = entries.firstOrNull { it.wire == value } ?: AUTO
    }
}

/**
 * Decides whether a tool call may proceed.
 *
 * Denial is an ordinary answer, not an error: it comes back to the model as a tool result so the
 * run can respond to it — say what it wanted and why — rather than ending. That matters for
 * unattended runs, where nobody is present to answer and the honest outcome is a recorded refusal
 * rather than a hang.
 */
interface ToolApprovalGate {
    suspend fun decide(
        tool: ToolDefinition,
        argumentsJson: String,
        /** A call recovered from unmarked text. See [ToolCall.recovered]. */
        recovered: Boolean = false,
        /**
         * Whether the conversation already contains content from outside — a fetched page, a
         * search result, an MCP reply. Only meaningful together with [recovered]: a recovered call
         * is the model's own text read as an intent, and it is only dangerous if that text could
         * have been put there by someone else.
         */
        untrustedContext: Boolean = false,
    ): ToolApprovalDecision
}

data class AgentRunRequest(
    val conversationId: ConversationId,
    val messages: List<ConversationMessage>,
    val identity: AgentIdentity,
    val maxOutputTokens: Int = 1_024,
    /** Taken from the profile the run is using, so sampling is part of the saved configuration. */
    val sampler: SamplerSettings = SamplerSettings(),
    /** The profile's own instructions. Layered after [identity], never in place of it. */
    val profileInstructions: String = "",
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
