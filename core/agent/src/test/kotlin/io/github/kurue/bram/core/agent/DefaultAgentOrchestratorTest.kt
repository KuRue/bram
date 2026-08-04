package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.AgentEvent
import io.github.kurue.bram.core.domain.AgentIdentity
import io.github.kurue.bram.core.domain.AgentRunRequest
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ModelDescriptor
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.core.domain.ModelLocation
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.RuntimeAvailability
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
