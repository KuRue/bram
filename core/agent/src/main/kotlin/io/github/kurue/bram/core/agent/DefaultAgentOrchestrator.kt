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
import io.github.kurue.bram.core.domain.ToolSelector
import io.github.kurue.bram.core.domain.AllToolsSelector
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
    private val toolSelector: ToolSelector = AllToolsSelector,
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
        // Recall is an enhancement, not a step the run depends on — exactly like extraction
        // below. A query the store cannot handle (a too-short message that collapses to an empty
        // FTS expression, a corrupt index, etc.) must take the turn down with it, so swallow it.
        val workingSummary = runCatching { memoryStore.workingSummary(request.conversationId) }.getOrNull()
        val memories = runCatching { memoryStore.search(request.conversationId, request.memoryQuery, limit = 8) }
            .getOrDefault(emptyList())
        val allowedForRun = mutableSetOf<String>()
        // Seeded from the history, not assumed fresh: a page fetched three turns ago is still in
        // the context this run generates from.
        var untrustedContext = hasUntrustedContent(workingMessages)
        var compacted = false
        // Tools are chosen once per run, from the ask that started it. Mid-run re-selection would
        // let the set change under the model's feet (a tool it planned to chain vanishing after a
        // tool result), and the query the run started with stays the best statement of its intent.
        val selectedTools = toolSelector.select(
            query = request.messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty(),
            contextWindowTokens = runtime.model.contextWindowTokens,
            available = toolRegistry.definitions(),
        )
        emit(AgentEvent.ToolsSelected(selectedTools.map { it.name }))

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
                    tools = selectedTools,
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
                val result = executeTool(call, allowedForRun, untrustedContext)
                workingMessages += ConversationMessage(
                    role = MessageRole.TOOL,
                    content = result,
                    toolCallId = call.id,
                )
                // Once outside content is in the conversation it stays in it, so this only ever
                // goes one way within a run. A failed or denied call brought nothing back.
                if (!untrustedContext &&
                    toolRegistry.find(call.name)?.definition?.returnsUntrustedContent == true &&
                    !isErrorResult(result)
                ) {
                    untrustedContext = true
                }
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

    private suspend fun executeTool(
        call: ToolCall,
        allowedForRun: MutableSet<String>,
        untrustedContext: Boolean,
    ): String {
        val handler = toolRegistry.find(call.name)
            ?: return errorJson("unknown_tool", "No tool named '${call.name}' is registered")

        val decision = if (call.name in allowedForRun && !call.recovered) {
            ToolApprovalDecision.ALLOW_ONCE
        } else {
            approvalGate.decide(
                handler.definition,
                call.argumentsJson,
                call.recovered,
                untrustedContext,
            )
        }

        when (decision) {
            ToolApprovalDecision.DENY -> return errorJson(
                "permission_denied",
                "The user declined this tool call. Do not call it again this run; continue " +
                    "without it or explain what you need from the user.",
            )
            // The two silent refusals differ from a declined call in what the model should do
            // next: nobody was there to say no, so the limitation is worth naming in the reply.
            ToolApprovalDecision.DENY_TIMEOUT -> return errorJson(
                "approval_timeout",
                "The approval request expired with nobody answering it; the run may be " +
                    "unattended. Continue without the tool, say so in your reply, and do not " +
                    "immediately retry.",
            )
            ToolApprovalDecision.DENY_UNATTENDED -> return errorJson(
                "approval_unattended",
                "Nobody could be asked to approve this call: the run is unattended and no " +
                    "notification could be posted. Continue without the tool and mention the " +
                    "limitation.",
            )
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

    /**
     * Whether the conversation already carries content from outside.
     *
     * Worked out from the history rather than assumed fresh each run: a page fetched three turns
     * ago is still in the context the model is generating from, so the run that follows it is in
     * the same position as the run that fetched it.
     *
     * A tool result that is one of Bram's own error envelopes does not count. A denied `web_fetch`
     * brought back nothing to be echoed, and treating it as though it had would let any refused
     * call put the conversation into a stricter mode for good.
     */
    private fun hasUntrustedContent(messages: List<ConversationMessage>): Boolean {
        val untrustedCallIds = messages
            .flatMap(ConversationMessage::toolCalls)
            .filter { toolRegistry.find(it.name)?.definition?.returnsUntrustedContent == true }
            .map(ToolCall::id)
            .toSet()
        if (untrustedCallIds.isEmpty()) return false
        return messages.any {
            it.role == MessageRole.TOOL &&
                it.toolCallId in untrustedCallIds &&
                !isErrorResult(it.content)
        }
    }

    /** Recognises the envelope [errorJson] writes, so a refusal is not mistaken for a page. */
    private fun isErrorResult(result: String): Boolean =
        result.trimStart().startsWith("{\"error\":")

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
        val list = values.getOrPut(conversationId.value) { mutableListOf() }
        list.add(memory)
        if (memory.kind == io.github.kurue.bram.core.domain.MemoryKind.EPISODE) {
            trimEpisodes(list)
        }
    }

    /**
     * Keeps only the newest [io.github.kurue.bram.core.domain.MAX_EPISODES_PER_CONVERSATION] episodes
     * in this conversation's list. Episodes accumulate every substantial turn; this mirrors the cap
     * the persistent store enforces so the in-memory double behaves the same way in tests.
     */
    private fun trimEpisodes(list: MutableList<MemoryRecord>) {
        val episodes = list.filter { it.kind == io.github.kurue.bram.core.domain.MemoryKind.EPISODE }
        if (episodes.size <= io.github.kurue.bram.core.domain.MAX_EPISODES_PER_CONVERSATION) return
        val oldest = episodes
            .sortedByDescending { it.createdAtEpochMillis }
            .drop(io.github.kurue.bram.core.domain.MAX_EPISODES_PER_CONVERSATION)
        list.removeAll(oldest.toSet())
    }

    override suspend fun recent(limit: Int): List<MemoryRecord> =
        values.values.flatten()
            .filterNot { it.kind == io.github.kurue.bram.core.domain.MemoryKind.WORKING_SUMMARY }
            .sortedByDescending { it.createdAtEpochMillis }
            .take(limit)

    override suspend fun mostImportant(limit: Int): List<MemoryRecord> =
        values.values.flatten()
            .filterNot { it.kind == io.github.kurue.bram.core.domain.MemoryKind.WORKING_SUMMARY }
            .sortedWith(compareByDescending<MemoryRecord> { it.importance }.thenByDescending { it.createdAtEpochMillis })
            .take(limit)

    override suspend fun remove(id: String) {
        values.values.forEach { list -> list.removeAll { it.id == id } }
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

/**
 * The built-in tools plus whatever remote servers contribute, keyed per server so a server can be
 * removed without touching the others. Built-ins win name collisions: a server tool named the same
 * as a built-in would otherwise shadow a tool whose behavior the user knows, on a server they just
 * configured.
 */
class MutableToolRegistry(
    base: ToolRegistry,
) : ToolRegistry {
    private val builtin = base
    private var serverTools: Map<String, List<ToolHandler>> = emptyMap()

    @Synchronized
    fun setServerTools(serverId: String, handlers: List<ToolHandler>) {
        serverTools = serverTools + (serverId to handlers)
    }

    @Synchronized
    fun removeServerTools(serverId: String) {
        serverTools = serverTools - serverId
    }

    @Synchronized
    override fun definitions(): List<ToolDefinition> =
        builtin.definitions() + serverTools.values.flatten().map { it.definition }

    @Synchronized
    override fun find(name: String): ToolHandler? =
        builtin.find(name) ?: serverTools.values.flatten().firstOrNull { it.definition.name == name }
}

class ReadOnlyApprovalGate : ToolApprovalGate {
    override suspend fun decide(
        tool: ToolDefinition,
        argumentsJson: String,
        recovered: Boolean,
        untrustedContext: Boolean,
    ): ToolApprovalDecision =
        if (!recovered && tool.readOnly && tool.requiredPermissions.isEmpty()) {
            ToolApprovalDecision.ALLOW_ONCE
        } else {
            ToolApprovalDecision.DENY
        }
}
