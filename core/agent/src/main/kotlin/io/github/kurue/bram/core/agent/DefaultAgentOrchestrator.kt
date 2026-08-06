package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.AgentEvent
import io.github.kurue.bram.core.domain.AgentOrchestrator
import io.github.kurue.bram.core.domain.AgentRunRequest
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.MemoryRecord
import io.github.kurue.bram.core.domain.MemoryStore
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.ToolApprovalDecision
import io.github.kurue.bram.core.domain.ToolApprovalGate
import io.github.kurue.bram.core.domain.ToolCall
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import io.github.kurue.bram.core.domain.ToolRegistry
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

class DefaultAgentOrchestrator(
    private val contextWindowManager: ContextWindowManager,
    private val memoryStore: MemoryStore,
    private val toolRegistry: ToolRegistry,
    private val approvalGate: ToolApprovalGate,
) : AgentOrchestrator {

    override fun run(request: AgentRunRequest, runtime: ModelRuntime): Flow<AgentEvent> = flow {
        val availability = runtime.availability()
        if (!availability.available) {
            emit(AgentEvent.Failed(availability.detail ?: availability.summary, recoverable = true))
            return@flow
        }

        val workingMessages = request.messages.toMutableList()
        val workingSummary = memoryStore.workingSummary(request.conversationId)
        val memories = memoryStore.search(request.conversationId, request.memoryQuery, limit = 8)
        val allowedForRun = mutableSetOf<String>()

        repeat(request.maxToolTurns + 1) { turn ->
            val context = contextWindowManager.plan(
                systemPrompt = request.identity.systemPrompt,
                profileInstructions = request.profileInstructions,
                transcript = workingMessages,
                contextWindowTokens = runtime.model.contextWindowTokens,
                requestedOutputTokens = request.maxOutputTokens,
                workingSummary = workingSummary,
                retrievedMemories = memories,
                runtimeTokenCount = runtime::countTokens,
            )
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
                    is GenerationEvent.Usage -> emit(AgentEvent.Usage(event.usage))
                    is GenerationEvent.Metrics -> emit(AgentEvent.Metrics(event.metrics))
                    is GenerationEvent.Failed -> failure = event
                    is GenerationEvent.Finished -> Unit
                }
            }

            failure?.let {
                emit(AgentEvent.Failed(it.message, it.recoverable))
                return@flow
            }

            val assistantMessage = ConversationMessage(
                role = MessageRole.ASSISTANT,
                content = responseText.toString(),
                toolCalls = toolCalls,
            )

            if (toolCalls.isEmpty()) {
                emit(AgentEvent.Completed(assistantMessage))
                return@flow
            }

            if (turn >= request.maxToolTurns) {
                emit(AgentEvent.Failed("Agent reached the configured tool-turn limit", recoverable = true))
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
}

class InMemoryMemoryStore : MemoryStore {
    private val values = mutableMapOf<String, MutableList<MemoryRecord>>()

    override suspend fun workingSummary(conversationId: io.github.kurue.bram.core.domain.ConversationId): MemoryRecord? =
        values[conversationId.value]?.lastOrNull { it.kind == io.github.kurue.bram.core.domain.MemoryKind.WORKING_SUMMARY }

    override suspend fun search(
        conversationId: io.github.kurue.bram.core.domain.ConversationId,
        query: String,
        limit: Int,
    ): List<MemoryRecord> {
        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        return values[conversationId.value]
            .orEmpty()
            .filterNot { it.kind == io.github.kurue.bram.core.domain.MemoryKind.WORKING_SUMMARY }
            .sortedByDescending { memory ->
                memory.importance + terms.count { it in memory.text.lowercase() } * 0.15
            }
            .take(limit)
    }

    override suspend fun put(
        conversationId: io.github.kurue.bram.core.domain.ConversationId,
        memory: MemoryRecord,
    ) {
        values.getOrPut(conversationId.value) { mutableListOf() }.add(memory)
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
