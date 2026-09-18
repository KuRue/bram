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
import io.github.kurue.bram.core.domain.ModelCapability
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
import io.github.kurue.bram.core.domain.ToolResultBudget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every test runtime here speaks tool calls; the capability gate must not starve them. */
private val TOOL_CAPABLE = setOf(ModelCapability.TEXT, ModelCapability.TOOL_CALLING)

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
    fun `a runtime without tool calling is offered no tools`() = runBlocking {
        // Sending definitions to an engine that cannot use them costs context and can error on
        // strict hosts; the capability flag is what keeps that from happening.
        val runtime = CapturingRuntime(capabilities = setOf(ModelCapability.TEXT))
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = ContextWindowManager(),
            memoryStore = InMemoryMemoryStore(),
            toolRegistry = StaticToolRegistry(listOf(NoopHandler("asked_about"))),
            approvalGate = ReadOnlyApprovalGate(),
        )

        orchestrator.run(
            request = AgentRunRequest(
                conversationId = ConversationId("no-tools"),
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

        assertTrue("no tools may reach a runtime that cannot call them", runtime.lastRequest?.tools?.isEmpty() == true)
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

    @Test
    fun `an os permission denial is not reported as a user refusal`() = runBlocking {
        // Android declining a permission is not the user declining the call. The model must be
        // pointed at the system setting instead of apologising for a decision nobody made.
        val events = runWithToolCall { ToolApprovalDecision.DENY_OS_PERMISSION }
        val result = (events.filterIsInstance<AgentEvent.ToolFinished>().single()).result
        assertTrue(result.contains("\"code\":\"os_permission_denied\""))
        assertTrue(result.contains("system settings"))
        assertTrue("must not blame the user", !result.contains("user declined"))
    }

    @Test
    fun `allow for run skips asking again this run`() = runBlocking {
        var gateCalls = 0
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
                ): ToolApprovalDecision {
                    gateCalls++
                    return ToolApprovalDecision.ALLOW_FOR_RUN
                }
            },
        )

        val events = orchestrator.run(
            request = AgentRunRequest(
                conversationId = ConversationId("for-run"),
                messages = listOf(ConversationMessage(role = MessageRole.USER, content = "Run the tool twice")),
                identity = AgentIdentity(
                    id = "bram",
                    version = "test",
                    displayName = "Bram",
                    systemPrompt = "You are Bram.",
                ),
            ),
            runtime = RepeatingToolRuntime("asked_about", times = 2),
        ).toList()

        assertEquals("the second call must ride on the run's allowance", 1, gateCalls)
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolFinished>().size)
        assertTrue(events.any { it is AgentEvent.Completed })
    }

    @Test
    fun `an oversized tool result reaches the transcript bounded and marked`() = runBlocking {
        val runtime = ToolResultCapturingRuntime(contextWindowTokens = 8_192)
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = ContextWindowManager(),
            memoryStore = InMemoryMemoryStore(),
            toolRegistry = StaticToolRegistry(listOf(ResultHandler("asked_about", "z".repeat(200_000)))),
            approvalGate = ReadOnlyApprovalGate(),
        )

        orchestrator.run(
            request = AgentRunRequest(
                conversationId = ConversationId("bounded"),
                messages = listOf(ConversationMessage(role = MessageRole.USER, content = "Run the tool")),
                identity = AgentIdentity(
                    id = "bram",
                    version = "test",
                    displayName = "Bram",
                    systemPrompt = "You are Bram.",
                ),
            ),
            runtime = runtime,
        ).toList()

        val toolMessage = runtime.secondRequest?.messages?.lastOrNull { it.role == MessageRole.TOOL }
        assertTrue("the run must have replayed a tool result", toolMessage != null)
        val content = toolMessage!!.content
        assertTrue(
            "bounded length ${content.length} exceeds cap ${ToolResultBudget.capChars(8_192)}",
            content.length <= ToolResultBudget.capChars(8_192) + 64,
        )
        assertTrue(content.contains("tool result truncated: 200000 characters total"))
        assertEquals("call_1", toolMessage.toolCallId)
    }

    @Test
    fun `invalid arguments are refused before approval and execution`() = runBlocking {
        val handler = CountingHandler("asked_about")
        var gateCalls = 0
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = ContextWindowManager(),
            memoryStore = InMemoryMemoryStore(),
            toolRegistry = StaticToolRegistry(listOf(handler)),
            approvalGate = object : ToolApprovalGate {
                override suspend fun decide(
                    tool: ToolDefinition,
                    argumentsJson: String,
                    recovered: Boolean,
                    untrustedContext: Boolean,
                ): ToolApprovalDecision {
                    gateCalls++
                    return ToolApprovalDecision.ALLOW_ONCE
                }
            },
        )

        val events = orchestrator.run(
            request = AgentRunRequest(
                conversationId = ConversationId("invalid-args"),
                messages = listOf(ConversationMessage(role = MessageRole.USER, content = "Run the tool")),
                identity = AgentIdentity(
                    id = "bram",
                    version = "test",
                    displayName = "Bram",
                    systemPrompt = "You are Bram.",
                ),
            ),
            runtime = ToolCallingRuntime(argumentsJson = """{"count":"two"}"""),
        ).toList()

        assertEquals("nothing may execute with bad arguments", 0, handler.executions)
        assertEquals("a call that cannot run needs no approval", 0, gateCalls)
        val result = events.filterIsInstance<AgentEvent.ToolFinished>().single().result
        assertTrue(result.contains("\"code\":\"invalid_arguments\""))
        assertTrue(result.contains("count"))
    }

    @Test
    fun `a tool that outlives its deadline is abandoned and the turn continues`() = runBlocking {
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = ContextWindowManager(),
            memoryStore = InMemoryMemoryStore(),
            toolRegistry = StaticToolRegistry(listOf(SlowHandler("asked_about", timeoutMillis = 40))),
            approvalGate = ReadOnlyApprovalGate(),
        )

        val events = orchestrator.run(
            request = AgentRunRequest(
                conversationId = ConversationId("timeout"),
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

        assertTrue("the turn must still complete", events.any { it is AgentEvent.Completed })
        val result = events.filterIsInstance<AgentEvent.ToolFinished>().single().result
        assertTrue(result.contains("\"code\":\"tool_timeout\""))
    }

    @Test
    fun `read-only calls from one reply run together`() = runBlocking {
        val latch = CountDownLatch(2)
        val first = LatchHandler("read_one", latch)
        val second = LatchHandler("read_two", latch)
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = ContextWindowManager(),
            memoryStore = InMemoryMemoryStore(),
            toolRegistry = StaticToolRegistry(listOf(first, second)),
            approvalGate = ReadOnlyApprovalGate(),
        )

        val events = orchestrator.run(
            request = AgentRunRequest(
                conversationId = ConversationId("parallel"),
                messages = listOf(ConversationMessage(role = MessageRole.USER, content = "Run both")),
                identity = AgentIdentity(
                    id = "bram",
                    version = "test",
                    displayName = "Bram",
                    systemPrompt = "You are Bram.",
                ),
            ),
            runtime = TwoCallRuntime("read_one", "read_two"),
        ).toList()

        assertTrue(events.any { it is AgentEvent.Completed })
        assertTrue("both read-only calls must overlap; saw $first/$second", first.overlapped && second.overlapped)
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolFinished>().size)
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
private class ToolCallingRuntime(
    private val argumentsJson: String = "{}",
) : ModelRuntime {
    override val model = ModelDescriptor(
        id = ModelId("test"),
        displayName = "Test",
        providerName = "Test",
        modelName = "test",
        location = ModelLocation.LOCAL,
        contextWindowTokens = 4_096,
        capabilities = TOOL_CAPABLE,
    )

    private var generations = 0

    override suspend fun availability() = RuntimeAvailability(available = true, summary = "Ready")

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        emit(GenerationEvent.Started("Test"))
        if (generations == 0) {
            emit(GenerationEvent.ToolCallReady(ToolCall(id = "call_1", name = "asked_about", argumentsJson = argumentsJson)))
        } else {
            emit(GenerationEvent.TextDelta("Done without it"))
        }
        generations++
        emit(GenerationEvent.Finished("stop"))
    }
}

/** Asks for two calls in one reply, then answers plainly. */
private class TwoCallRuntime(private vararg val toolNames: String) : ModelRuntime {
    override val model = ModelDescriptor(
        id = ModelId("test"),
        displayName = "Test",
        providerName = "Test",
        modelName = "test",
        location = ModelLocation.LOCAL,
        contextWindowTokens = 4_096,
        capabilities = TOOL_CAPABLE,
    )

    private var generations = 0

    override suspend fun availability() = RuntimeAvailability(available = true, summary = "Ready")

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        emit(GenerationEvent.Started("Test"))
        if (generations == 0) {
            toolNames.forEachIndexed { index, name ->
                emit(
                    GenerationEvent.ToolCallReady(
                        ToolCall(id = "call_$index", name = name, argumentsJson = "{}"),
                    ),
                )
            }
        } else {
            emit(GenerationEvent.TextDelta("Done"))
        }
        generations++
        emit(GenerationEvent.Finished("stop"))
    }
}

/** Asks for the same tool on [times] consecutive replies, then answers plainly. */
private class RepeatingToolRuntime(private val toolName: String, private val times: Int) : ModelRuntime {
    override val model = ModelDescriptor(
        id = ModelId("test"),
        displayName = "Test",
        providerName = "Test",
        modelName = "test",
        location = ModelLocation.LOCAL,
        contextWindowTokens = 4_096,
        capabilities = TOOL_CAPABLE,
    )

    private var generations = 0

    override suspend fun availability() = RuntimeAvailability(available = true, summary = "Ready")

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        emit(GenerationEvent.Started("Test"))
        if (generations < times) {
            emit(GenerationEvent.ToolCallReady(ToolCall(id = "call_$generations", name = toolName, argumentsJson = "{}")))
        } else {
            emit(GenerationEvent.TextDelta("Done"))
        }
        generations++
        emit(GenerationEvent.Finished("stop"))
    }
}

private class CountingHandler(name: String) : ToolHandler {
    var executions = 0
    override val definition = ToolDefinition(
        name = name,
        description = name,
        inputSchemaJson =
            """{"type":"object","properties":{"count":{"type":"integer"}},"additionalProperties":false}""",
    )

    override suspend fun execute(argumentsJson: String): String {
        executions++
        return "{}"
    }
}

private class SlowHandler(name: String, timeoutMillis: Long) : ToolHandler {
    override val definition = ToolDefinition(
        name = name,
        description = name,
        inputSchemaJson = "{}",
        timeoutMillis = timeoutMillis,
    )

    override suspend fun execute(argumentsJson: String): String {
        delay(30_000)
        return "{}"
    }
}

/** Records whether another handler was running at the same time; both must overlap. */
private class LatchHandler(name: String, private val latch: CountDownLatch) : ToolHandler {
    var overlapped = false
    override val definition = ToolDefinition(name = name, description = name, inputSchemaJson = "{}", readOnly = true)

    override suspend fun execute(argumentsJson: String): String {
        withContext(Dispatchers.Default) {
            latch.countDown()
            overlapped = latch.await(3, TimeUnit.SECONDS)
        }
        return "{}"
    }
}

private class NoopHandler(name: String) : ToolHandler {
    override val definition = ToolDefinition(name = name, description = name, inputSchemaJson = "{}")
    override suspend fun execute(argumentsJson: String): String = "{}"
}

private class ResultHandler(name: String, private val result: String) : ToolHandler {
    override val definition = ToolDefinition(name = name, description = name, inputSchemaJson = "{}")
    override suspend fun execute(argumentsJson: String): String = result
}

/**
 * Calls one tool on the first generation and captures the follow-up request — the one carrying the
 * tool result — before answering plainly.
 */
private class ToolResultCapturingRuntime(contextWindowTokens: Int) : ModelRuntime {
    override val model = ModelDescriptor(
        id = ModelId("test"),
        displayName = "Test",
        providerName = "Test",
        modelName = "test",
        location = ModelLocation.LOCAL,
        contextWindowTokens = contextWindowTokens,
        capabilities = TOOL_CAPABLE,
    )

    private var generations = 0
    var secondRequest: GenerationRequest? = null

    override suspend fun availability() = RuntimeAvailability(available = true, summary = "Ready")

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        emit(GenerationEvent.Started("Test"))
        if (generations == 0) {
            emit(GenerationEvent.ToolCallReady(ToolCall(id = "call_1", name = "asked_about", argumentsJson = "{}")))
        } else {
            secondRequest = request
            emit(GenerationEvent.TextDelta("Done"))
        }
        generations++
        emit(GenerationEvent.Finished("stop"))
    }
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

private class CapturingRuntime(
    capabilities: Set<ModelCapability> = TOOL_CAPABLE,
) : ModelRuntime {
    override val model = ModelDescriptor(
        id = ModelId("test"),
        displayName = "Test",
        providerName = "Test",
        modelName = "test",
        location = ModelLocation.LOCAL,
        contextWindowTokens = 4_096,
        capabilities = capabilities,
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
