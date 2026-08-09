package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.AgentEvent
import io.github.kurue.bram.core.domain.AgentOrchestrator
import io.github.kurue.bram.core.domain.AgentRunRequest
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.MemoryExtractor
import io.github.kurue.bram.core.domain.MemoryKind
import io.github.kurue.bram.core.domain.MemoryRecord
import io.github.kurue.bram.core.domain.MemoryStore
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.NoopMemoryExtractor
import io.github.kurue.bram.core.domain.NoopRunJournal
import io.github.kurue.bram.core.domain.RunJournal
import io.github.kurue.bram.core.domain.RunJournalEntry
import io.github.kurue.bram.core.domain.RunStatus
import io.github.kurue.bram.core.domain.ToolApprovalDecision
import io.github.kurue.bram.core.domain.ToolApprovalGate
import io.github.kurue.bram.core.domain.ToolCall
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import io.github.kurue.bram.core.domain.ToolRegistry
import io.github.kurue.bram.core.domain.toRecord
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

class DefaultAgentOrchestrator(
    private val contextWindowManager: ContextWindowManager,
    private val memoryStore: MemoryStore,
    private val toolRegistry: ToolRegistry,
    private val approvalGate: ToolApprovalGate,
    private val journal: RunJournal = NoopRunJournal,
    private val memoryExtractor: MemoryExtractor = NoopMemoryExtractor,
) : AgentOrchestrator {

    override fun run(request: AgentRunRequest, runtime: ModelRuntime): Flow<AgentEvent> = flow {
        val availability = runtime.availability()
        if (!availability.available) {
            emit(AgentEvent.Failed(availability.detail ?: availability.summary, recoverable = true))
            return@flow
        }

        val startedAt = System.currentTimeMillis()
        val journalId = UUID.randomUUID().toString()
        var inputTokensTotal = 0L
        var outputTokensTotal = 0L
        var toolTurns = 0

        suspend fun upsertJournal(status: RunStatus, error: String? = null) {
            runCatching {
                journal.upsert(
                    RunJournalEntry(
                        id = journalId,
                        conversationId = request.conversationId,
                        startedAtEpochMillis = startedAt,
                        finishedAtEpochMillis = System.currentTimeMillis(),
                        status = status,
                        toolTurns = toolTurns,
                        inputTokens = inputTokensTotal.takeIf { it > 0 }?.toInt(),
                        outputTokens = outputTokensTotal.takeIf { it > 0 }?.toInt(),
                        error = error,
                    ),
                )
            }
        }

        val workingMessages = request.messages.toMutableList()
        val workingSummary = memoryStore.workingSummary(request.conversationId)
        val memories = memoryStore.search(request.conversationId, request.memoryQuery, limit = 8)
        val allowedForRun = mutableSetOf<String>()
        var compacted = false

        repeat(request.maxToolTurns + 1) { turn ->
            var context = contextWindowManager.plan(
                systemPrompt = request.identity.systemPrompt,
                profileInstructions = request.profileInstructions,
                transcript = workingMessages,
                contextWindowTokens = runtime.model.contextWindowTokens,
                requestedOutputTokens = request.maxOutputTokens,
                workingSummary = workingSummary,
                retrievedMemories = memories,
                runtimeTokenCount = runtime::countTokens,
            )
            // Messages fell out of the context window. Instead of dropping them, summarise them
            // into the working summary, then re-plan: the summary replaces what was omitted, so a
            // long conversation keeps one dense record rather than a gap. Done once per run — after
            // compaction nothing is omitted, so a second pass would summarize nothing.
            if (!compacted && context.omittedMessageIds.isNotEmpty()) {
                compacted = true
                val omitted = workingMessages.filter { it.id in context.omittedMessageIds }
                emit(AgentEvent.Status("Compacting ${omitted.size} earlier messages…"))
                summarizeOmitted(request, runtime, omitted)?.let { summary ->
                    runCatching {
                        memoryStore.put(
                            request.conversationId,
                            MemoryRecord(
                                id = "summary-${UUID.randomUUID()}",
                                kind = MemoryKind.WORKING_SUMMARY,
                                text = summary,
                                importance = 1.0,
                                sourceMessageIds = omitted.mapNotNull { it.id },
                            ),
                        )
                    }
                    emit(AgentEvent.Status("Compacted ${omitted.size} earlier messages into a working summary."))
                    // Re-plan with the fresh summary in the fixed head, so the model sees it this
                    // turn rather than the next one.
                    context = contextWindowManager.plan(
                        systemPrompt = request.identity.systemPrompt,
                        profileInstructions = request.profileInstructions,
                        transcript = workingMessages,
                        contextWindowTokens = runtime.model.contextWindowTokens,
                        requestedOutputTokens = request.maxOutputTokens,
                        workingSummary = memoryStore.workingSummary(request.conversationId),
                        retrievedMemories = memories,
                        runtimeTokenCount = runtime::countTokens,
                    )
                }
            }
            emit(
                AgentEvent.ContextPrepared(
                    estimatedInputTokens = context.estimatedInputTokens,
                    omittedMessageCount = context.omittedMessageIds.size,
                ),
            )

            val responseText = StringBuilder()
            val toolCalls = mutableListOf<ToolCall>()
            var failure: GenerationEvent.Failed? = null

            runtime.generate(
                GenerationRequest(
                    messages = context.messages,
                    tools = toolRegistry.definitions(),
                    maxOutputTokens = request.maxOutputTokens,
                    sampler = request.sampler,
                    requestId = UUID.randomUUID().toString(),
                ),
            ).collect { event ->
                when (event) {
                    is GenerationEvent.Started -> {
                        emit(AgentEvent.Status(event.runtimeDescription))
                        event.reasoningFormat?.let { emit(AgentEvent.Reasoning(it)) }
                    }
                    is GenerationEvent.TextDelta -> {
                        responseText.append(event.text)
                        emit(AgentEvent.TextDelta(event.text))
                    }
                    is GenerationEvent.ToolCallReady -> toolCalls += event.call
                    is GenerationEvent.Usage -> {
                        event.usage.inputTokens?.let { inputTokensTotal += it }
                        event.usage.outputTokens?.let { outputTokensTotal += it }
                        emit(AgentEvent.Usage(event.usage))
                    }
                    is GenerationEvent.Metrics -> emit(AgentEvent.Metrics(event.metrics))
                    is GenerationEvent.Failed -> failure = event
                    is GenerationEvent.Finished -> Unit
                }
            }

            failure?.let {
                emit(AgentEvent.Failed(it.message, it.recoverable))
                upsertJournal(RunStatus.FAILED, it.message)
                return@flow
            }

            val assistantMessage = ConversationMessage(
                role = MessageRole.ASSISTANT,
                content = responseText.toString(),
                toolCalls = toolCalls,
            )

            if (toolCalls.isEmpty()) {
                // The reply has fully streamed above. Before declaring the turn done, pull durable
                // memories out of this exchange so they are retrievable next turn. Best-effort at
                // every layer: a failed extraction or a failed write is dropped, never surfaced —
                // recall is an enhancement, not a step the run depends on.
                request.messages.lastOrNull { it.role == MessageRole.USER }?.let { userMessage ->
                    runCatching {
                        memoryExtractor.extract(
                            conversationId = request.conversationId,
                            userMessage = userMessage,
                            assistantReply = responseText.toString(),
                            runtime = runtime,
                        )
                    }.getOrDefault(emptyList()).forEach { extracted ->
                        runCatching {
                            memoryStore.put(request.conversationId, extracted.toRecord(userMessage.id))
                        }
                    }
                }
                emit(AgentEvent.Completed(assistantMessage))
                upsertJournal(RunStatus.SUCCEEDED)
                return@flow
            }

            toolTurns += 1
            if (turn >= request.maxToolTurns) {
                emit(AgentEvent.Failed("Agent reached the configured tool-turn limit", recoverable = true))
                upsertJournal(RunStatus.FAILED, "Tool-turn limit reached")
                return@flow
            }

            workingMessages += assistantMessage
            for (call in toolCalls) {
                emit(AgentEvent.ToolStarted(call))
                val result = executeTool(call, allowedForRun)
                workingMessages += ConversationMessage(
                    role = MessageRole.TOOL,
                    content = result,
                    toolCallId = call.id,
                )
                emit(AgentEvent.ToolFinished(call, result))
            }
        }
        emit(AgentEvent.Failed("Agent stopped without a reply", recoverable = true))
        upsertJournal(RunStatus.FAILED, "Stopped without a reply")
    }

    /**
     * Runs one generation asking for a summary of the messages that no longer fit. Bounded input —
     * head and tail of what was omitted, since the middle is the least informative part of an old
     * stretch — and a bounded output, so a compaction costs a short run rather than a long one.
     * Returns null on any failure: compaction must never take the run down with it.
     */
    private suspend fun summarizeOmitted(
        request: AgentRunRequest,
        runtime: ModelRuntime,
        omitted: List<ConversationMessage>,
    ): String? {
        if (omitted.isEmpty()) return null
        val excerpt = buildString {
            var length = 0
            for (message in omitted) {
                val text = message.content
                if (length + text.length > COMPACT_MAX_EXCERPT_CHARS) {
                    append("\n\n…[remaining ${omitted.size} messages omitted from the excerpt]…")
                    break
                }
                val prefix = when (message.role) {
                    MessageRole.USER -> "User: "
                    MessageRole.ASSISTANT -> "Bram: "
                    MessageRole.TOOL -> "Tool result: "
                    MessageRole.SYSTEM -> "Instruction: "
                }
                append(prefix).append(text).append('\n')
                length += text.length + prefix.length + 1
            }
        }
        val summary = StringBuilder()
        var failed: String? = null
        runtime.generate(
            GenerationRequest(
                messages = listOf(
                    ConversationMessage(
                        role = MessageRole.SYSTEM,
                        content = "Write a dense factual summary of this conversation excerpt: " +
                            "keep decisions, names, numbers, instructions, and open questions. " +
                            "Do not add commentary or advice. Use plain prose.",
                    ),
                    ConversationMessage(role = MessageRole.USER, content = excerpt),
                ),
                tools = emptyList(),
                maxOutputTokens = COMPACT_MAX_OUTPUT_TOKENS,
                sampler = request.sampler.copy(temperature = 0f),
                requestId = "compact-${UUID.randomUUID()}",
            ),
        ).collect { event ->
            when (event) {
                is GenerationEvent.TextDelta -> summary.append(event.text)
                is GenerationEvent.Failed -> failed = event.message
                else -> Unit
            }
        }
        if (failed != null) return null
        val text = summary.toString().trim()
        return text.takeIf(String::isNotEmpty)
    }

    private suspend fun executeTool(call: ToolCall, allowedForRun: MutableSet<String>): String {
        val handler = toolRegistry.find(call.name)
            ?: return errorJson("unknown_tool", "No tool named '${call.name}' is registered")

        val decision = if (call.name in allowedForRun && !call.recovered) {
            ToolApprovalDecision.ALLOW_ONCE
        } else {
            approvalGate.decide(handler.definition, call.argumentsJson, call.recovered)
        }

        when (decision) {
            ToolApprovalDecision.DENY -> return errorJson("permission_denied", "The user or policy denied this tool call")
            // Remembering past the run is the gate's business, not the loop's; here both mean the
            // same thing — do not ask again before this run ends.
            // A recovered call grants nothing forward: allowing this one says nothing about the
            // next piece of text that happens to look like it.
            ToolApprovalDecision.ALLOW_FOR_RUN, ToolApprovalDecision.ALLOW_ALWAYS ->
                if (!call.recovered) allowedForRun += call.name
            ToolApprovalDecision.ALLOW_ONCE -> Unit
        }

        return runCatching { handler.execute(call.argumentsJson) }
            .getOrElse { errorJson("tool_error", it.message ?: it::class.java.simpleName) }
    }

    private fun errorJson(code: String, message: String): String {
        val safe = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "{\"error\":{\"code\":\"$code\",\"message\":\"$safe\"}}"
    }

    private companion object {
        const val COMPACT_MAX_EXCERPT_CHARS = 6_000
        const val COMPACT_MAX_OUTPUT_TOKENS = 384
    }
}

class InMemoryMemoryStore : MemoryStore {
    private val values = mutableMapOf<String, MutableList<MemoryRecord>>()

    override suspend fun workingSummary(conversationId: io.github.kurue.bram.core.domain.ConversationId): MemoryRecord? =
        values[conversationId.value]?.lastOrNull { it.kind == io.github.kurue.bram.core.domain.MemoryKind.WORKING_SUMMARY }

    override suspend fun search(
        conversationId: io.github.kurue.bram.core.domain.ConversationId,
        query: String,
        limit: Int,
    ): List<MemoryRecord> =
        values[conversationId.value].orEmpty().searchScored(query, limit)

    override suspend fun searchAll(query: String, limit: Int): List<MemoryRecord> =
        values.values.flatten().searchScored(query, limit)

    override suspend fun put(
        conversationId: io.github.kurue.bram.core.domain.ConversationId,
        memory: MemoryRecord,
    ) {
        values.getOrPut(conversationId.value) { mutableListOf() }.add(memory)
    }

    private fun List<MemoryRecord>.searchScored(query: String, limit: Int): List<MemoryRecord> {
        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        return filterNot { it.kind == io.github.kurue.bram.core.domain.MemoryKind.WORKING_SUMMARY }
            .sortedByDescending { memory ->
                memory.importance + terms.count { it in memory.text.lowercase() } * 0.15
            }
            .take(limit)
    }
}

class StaticToolRegistry(
    handlers: List<ToolHandler> = emptyList(),
) : ToolRegistry {
    private val byName = handlers.associateBy { it.definition.name }
    override fun definitions(): List<ToolDefinition> = byName.values.map { it.definition }
    override fun find(name: String): ToolHandler? = byName[name]
}

class ReadOnlyApprovalGate : ToolApprovalGate {
    override suspend fun decide(
        tool: ToolDefinition,
        argumentsJson: String,
        recovered: Boolean,
    ): ToolApprovalDecision =
        if (!recovered && tool.readOnly && tool.requiredPermissions.isEmpty()) {
            ToolApprovalDecision.ALLOW_ONCE
        } else {
            ToolApprovalDecision.DENY
        }
}
