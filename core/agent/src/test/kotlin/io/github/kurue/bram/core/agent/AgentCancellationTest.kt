package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.AgentRunRequest
import io.github.kurue.bram.core.domain.AgentIdentity
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ModelCapability
import io.github.kurue.bram.core.domain.ModelDescriptor
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.core.domain.ModelLocation
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.RuntimeAvailability
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * That a stop reaches the runtime, not just the collectors.
 *
 * The request id is minted inside the orchestrator and never leaves it, so before [AgentOrchestrator.cancel]
 * existed the only lever a caller had was cancelling the coroutine — and a runtime parked in a blocking read
 * does not necessarily notice that. This pins the handle: cancelling the orchestrator must call the runtime's
 * own cancellation, with the id the runtime actually saw.
 */
class AgentCancellationTest {

    @Test
    fun `cancelling the orchestrator cancels the runtime request in flight`() = runBlocking {
        val runtime = RecordingRuntime()
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = ContextWindowManager(),
            memoryStore = InMemoryMemoryStore(),
            toolRegistry = StaticToolRegistry(),
            approvalGate = ReadOnlyApprovalGate(),
        )

        val run = launch(Dispatchers.Default) {
            orchestrator.run(
                request = AgentRunRequest(
                    conversationId = ConversationId("cancel"),
                    messages = listOf(ConversationMessage(role = MessageRole.USER, content = "Hello")),
                    identity = AgentIdentity(id = "test", version = "1", displayName = "Test", systemPrompt = ""),
                ),
                runtime = runtime,
            ).collect {}
        }
        // The runtime records the id it was handed, so the assertion compares like with like.
        while (runtime.seenRequestId.get() == null) kotlinx.coroutines.delay(10)

        orchestrator.cancel()

        assertNotNull("cancel() must reach the runtime", runtime.cancelledRequestId.get())
        assertEquals(
            "the runtime must be cancelled with the id it was actually given",
            runtime.seenRequestId.get(),
            runtime.cancelledRequestId.get(),
        )
        run.cancel()
    }
}

/** A runtime that never finishes generating, so a cancellation has something to interrupt. */
private class RecordingRuntime : ModelRuntime {
    val seenRequestId = AtomicReference<String?>(null)
    val cancelledRequestId = AtomicReference<String?>(null)

    override val model = ModelDescriptor(
        id = ModelId("recording"),
        displayName = "Recording",
        providerName = "Test",
        modelName = "recording",
        location = ModelLocation.LOCAL,
        contextWindowTokens = 4_096,
        capabilities = setOf(ModelCapability.TEXT),
    )

    override suspend fun availability() = RuntimeAvailability(available = true, summary = "Ready")

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        seenRequestId.set(request.requestId)
        emit(GenerationEvent.Started("Recording"))
        // Then silence: the run stays live until something cancels it.
        awaitCancellation()
    }

    override suspend fun cancel(requestId: String) {
        cancelledRequestId.set(requestId)
    }
}
