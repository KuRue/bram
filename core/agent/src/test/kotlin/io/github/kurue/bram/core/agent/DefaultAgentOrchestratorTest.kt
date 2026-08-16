package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.AgentEvent
import io.github.kurue.bram.core.domain.AgentIdentity
import io.github.kurue.bram.core.domain.AgentRunRequest
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.ExtractedMemory
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.MemoryExtractor
import io.github.kurue.bram.core.domain.MemoryKind
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ModelDescriptor
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.core.domain.ModelLocation
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.RuntimeAvailability
import io.github.kurue.bram.core.domain.ToolApprovalDecision
import io.github.kurue.bram.core.domain.ToolApprovalGate
import io.github.kurue.bram.core.domain.ToolCall
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAgentOrchestratorTest {
    @Test
    fun `agent identity supplies the system prompt`() = runBlocking {
        val runtime = CapturingRuntime()
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = ContextWindowManager(),
            memoryStore = InMemoryMemoryStore(),
            toolRegistry = StaticToolRegistry(),
            approvalGate = ReadOnlyApprovalGate(),
        )

        val events = orchestrator.run(
            request = AgentRunRequest(
                conversationId = ConversationId("test"),
                messages = listOf(ConversationMessage(role = MessageRole.USER, content = "Hello")),
                identity = AgentIdentity(
                    id = "bram",
                    version = "test",
                    displayName = "Bram",
                    systemPrompt = "You are Bram.",
                ),
            ),
            runtime = runtime,
        ).toList()

        assertEquals(MessageRole.SYSTEM, runtime.lastRequest?.messages?.first()?.role)
        assertEquals("You are Bram.", runtime.lastRequest?.messages?.first()?.content)
        assertTrue(events.any { it is AgentEvent.Completed && it.message.content == "Hello back" })
    }

    @Test
    fun `extraction runs after a completed turn and persists its records`() = runBlocking {
        val runtime = CapturingRuntime()
        val memory = InMemoryMemoryStore()
        val extractor = StubMemoryExtractor(
            listOf(ExtractedMemory(MemoryKind.SEMANTIC_FACT, "The user lives in Tokyo", 0.6)),
        )
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = ContextWindowManager(),
            memoryStore = memory,
            toolRegistry = StaticToolRegistry(),
            approvalGate = ReadOnlyApprovalGate(),
            memoryExtractor = extractor,
        )

        val events = orchestrator.run(
            request = AgentRunRequest(
                conversationId = ConversationId("extract"),
                messages = listOf(ConversationMessage(role = MessageRole.USER, content = "I live in Tokyo")),
                identity = AgentIdentity(
                    id = "bram",
                    version = "test",
                    displayName = "Bram",
                    systemPrompt = "You are Bram.",
                ),
            ),
            runtime = runtime,
        ).toList()

        assertTrue(events.any { it is AgentEvent.Completed })
        assertEquals("extraction should run exactly once per turn", 1, extractor.calls)
        val recalled = memory.searchAll("tokyo", 10)
        assertEquals(1, recalled.size)
        assertEquals("The user lives in Tokyo", recalled[0].text)
        assertEquals(MemoryKind.SEMANTIC_FACT, recalled[0].kind)
    }

    @Test
    fun `a throwing extractor does not break the turn`() = runBlocking {
        val runtime = CapturingRuntime()
        val memory = InMemoryMemoryStore()
        val extractor = StubMemoryExtractor(emptyList(), throwOnCall = true)
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = ContextWindowManager(),
            memoryStore = memory,
            toolRegistry = StaticToolRegistry(),
            approvalGate = ReadOnlyApprovalGate(),
            memoryExtractor = extractor,
        )

        val events = orchestrator.run(
            request = AgentRunRequest(
                conversationId = ConversationId("extract-fail"),
                messages = listOf(ConversationMessage(role = MessageRole.USER, content = "Hello")),
                identity = AgentIdentity(
                    id = "bram",
                    version = "test",
                    displayName = "Bram",
                    systemPrompt = "You are Bram.",
                ),
            ),
            runtime = runtime,
        ).toList()

        assertTrue("the turn must still complete", events.any { it is AgentEvent.Completed })
        assertTrue(memory.searchAll("anything", 10).isEmpty())
    }

    @Test
    fun `the selected tools reach the generation request`() = runBlocking {
        val runtime = CapturingRuntime()
        val offered = ToolDefinition(name = "only_me", description = "the one tool this run gets", inputSchemaJson = "{}")
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = ContextWindowManager(),
            memoryStore = InMemoryMemoryStore(),
            toolRegistry = StaticToolRegistry(listOf(NoopHandler("only_me"), NoopHandler("trimmed_away"))),
            approvalGate = ReadOnlyApprovalGate(),
            toolSelector = { _, _, _ -> listOf(offered) },
        )

        orchestrator.run(
            request = AgentRunRequest(
                conversationId = ConversationId("select"),
                messages = listOf(ConversationMessage(role = MessageRole.USER, content = "Trim the tool list")),
                identity = AgentIdentity(
                    id = "bram",
                    version = "test",
                    displayName = "Bram",
                    systemPrompt = "You are Bram.",
                ),
            ),
            runtime = runtime,
        ).toList()

        assertEquals(listOf("only_me"), runtime.lastRequest?.tools?.map { it.name })
    }

    // A refusal's error envelope says what happened and what to do, because "denied" from a
    // timeout reads to a model exactly like "the user said no" — and the right next action differs.

    @Test
    fun `an expired approval reads as a timeout, not a refusal`() = runBlocking {
        val events = runWithToolCall { ToolApprovalDecision.DENY_TIMEOUT }
        val result = (events.filterIsInstance<AgentEvent.ToolFinished>().single()).result
        assertTrue(result.contains("\"code\":\"approval_timeout\""))
        assertTrue(result.contains("unattended"))
    }

    @Test
    fun `an unattended refusal names the limitation`() = runBlocking {
        val events = runWithToolCall { ToolApprovalDecision.DENY_UNATTENDED }
        val result = (events.filterIsInstance<AgentEvent.ToolFinished>().single()).result
        assertTrue(result.contains("\"code\":\"approval_unattended\""))
    }

    @Test
    fun `a declined call says not to retry it`() = runBlocking {
        val events = runWithToolCall { ToolApprovalDecision.DENY }
        val result = (events.filterIsInstance<AgentEvent.ToolFinished>().single()).result
        assertTrue(result.contains("\"code\":\"permission_denied\""))
        assertTrue(result.contains("Do not call it again"))
    }

    /** One run whose model calls a tool once, with the gate answering [decision]. */
    private suspend fun runWithToolCall(decision: () -> ToolApprovalDecision): List<AgentEvent> {
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = ContextWindowManager(),
            memoryStore = InMemoryMemoryStore(),
            toolRegistry = StaticToolRegistry(listOf(NoopHandler("asked_about"))),
            approvalGate = object : ToolApprovalGate {
                override suspend fun decide(
                    tool: ToolDefinition,
                    argumentsJson: String,
                    recovered: Boolean,
                    untrustedContext: Boolean,
                ): ToolApprovalDecision = decision()
            },
        )
        return orchestrator.run(
            request = AgentRunRequest(
                conversationId = ConversationId("deny"),
                messages = listOf(ConversationMessage(role = MessageRole.USER, content = "Run the tool")),
                identity = AgentIdentity(
                    id = "bram",
                    version = "test",
                    displayName = "Bram",
                    systemPrompt = "You are Bram.",
                ),
            ),
            runtime = ToolCallingRuntime(),
        ).toList()
    }
}

/**
 * A runtime that asks for one tool call on the first generation and answers plainly after it, so
 * a denied call produces exactly one ToolFinished before the run completes.
 */
private class ToolCallingRuntime : ModelRuntime {
    override val model = ModelDescriptor(
        id = ModelId("test"),
        displayName = "Test",
        providerName = "Test",
        modelName = "test",
        location = ModelLocation.LOCAL,
        contextWindowTokens = 4_096,
    )

    private var generations = 0

    override suspend fun availability() = RuntimeAvailability(available = true, summary = "Ready")

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        emit(GenerationEvent.Started("Test"))
        if (generations == 0) {
            emit(GenerationEvent.ToolCallReady(ToolCall(id = "call_1", name = "asked_about", argumentsJson = "{}")))
        } else {
            emit(GenerationEvent.TextDelta("Done without it"))
        }
        generations++
        emit(GenerationEvent.Finished("stop"))
    }
}

private class NoopHandler(name: String) : ToolHandler {
    override val definition = ToolDefinition(name = name, description = name, inputSchemaJson = "{}")
    override suspend fun execute(argumentsJson: String): String = "{}"
}

private class StubMemoryExtractor(
    private val result: List<ExtractedMemory>,
    private val throwOnCall: Boolean = false,
) : MemoryExtractor {
    var calls = 0
    override suspend fun extract(
        conversationId: ConversationId,
        userMessage: ConversationMessage?,
        assistantReply: String,
        runtime: ModelRuntime,
    ): List<ExtractedMemory> {
        calls++
        if (throwOnCall) error("extractor blew up")
        return result
    }
}

private class CapturingRuntime : ModelRuntime {
    override val model = ModelDescriptor(
        id = ModelId("test"),
        displayName = "Test",
        providerName = "Test",
        modelName = "test",
        location = ModelLocation.LOCAL,
        contextWindowTokens = 4_096,
    )

    var lastRequest: GenerationRequest? = null

    override suspend fun availability() = RuntimeAvailability(available = true, summary = "Ready")

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        lastRequest = request
        emit(GenerationEvent.Started("Test"))
        emit(GenerationEvent.TextDelta("Hello back"))
        emit(GenerationEvent.Finished("stop"))
    }
}
