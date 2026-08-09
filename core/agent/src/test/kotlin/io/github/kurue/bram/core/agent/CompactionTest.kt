package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.AgentEvent
import io.github.kurue.bram.core.domain.AgentIdentity
import io.github.kurue.bram.core.domain.AgentRunRequest
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.MemoryKind
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ModelDescriptor
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.core.domain.ModelLocation
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.RunJournal
import io.github.kurue.bram.core.domain.RunJournalEntry
import io.github.kurue.bram.core.domain.RunStatus
import io.github.kurue.bram.core.domain.RuntimeAvailability
import io.github.kurue.bram.core.domain.TokenUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionTest {
    @Test
    fun `messages that fall out of the context are summarised into working memory`() = runBlocking {
        val runtime = CompactingRuntime()
        val memory = InMemoryMemoryStore()
        val journal = CapturingJournal()
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = ContextWindowManager(),
            memoryStore = memory,
            toolRegistry = StaticToolRegistry(),
            approvalGate = ReadOnlyApprovalGate(),
            journal = journal,
        )

        // A long transcript: roughly 30 turns against a 640-token window guarantees omission.
        val transcript = (0 until 30).flatMap { turn ->
            listOf(
                ConversationMessage(
                    role = MessageRole.USER,
                    content = "Question $turn: what was decided about the ${"subject".repeat(30)}?",
                ),
                ConversationMessage(
                    role = MessageRole.ASSISTANT,
                    content = "Answer $turn: the ${"subject".repeat(30)} matters because of the details.",
                ),
            )
        }
        val events = orchestrator.run(
            request = AgentRunRequest(
                conversationId = ConversationId("compact"),
                messages = transcript,
                identity = AgentIdentity(
                    id = "bram",
                    version = "test",
                    displayName = "Bram",
                    systemPrompt = "You are Bram.",
                ),
            ),
            runtime = runtime,
        ).toList()

        // The compaction ran and said so.
        assertTrue(events.any { it is AgentEvent.Status && it.text.startsWith("Compacting ") })
        assertTrue(events.any { it is AgentEvent.Status && it.text.startsWith("Compacted ") })

        // The summary was written with the provenance of what it replaced.
        val summary = memory.workingSummary(ConversationId("compact"))
        assertNotNull(summary)
        assertEquals(MemoryKind.WORKING_SUMMARY, summary!!.kind)
        assertEquals("Summary of the earlier conversation", summary.text)
        assertTrue(summary.sourceMessageIds.isNotEmpty())

        // The main generation ran with the summary in the fixed head of the prompt.
        val mainRequest = runtime.mainRequest
        assertNotNull(mainRequest)
        assertTrue(mainRequest!!.messages.any { it.content.contains("Working conversation summary") })

        // The turn that generated the reply had nothing omitted after compaction.
        assertTrue(events.any { it is AgentEvent.Completed })

        // The journal recorded the run as one upserted entry that ended successful, with the
        // token totals from the Usage events.
        assertEquals(1, journal.entries.size)
        val recorded = journal.entries.single()
        assertEquals(RunStatus.SUCCEEDED, recorded.status)
        assertEquals(100, recorded.inputTokens)
        assertEquals(50, recorded.outputTokens)
        assertTrue(recorded.finishedAtEpochMillis!! >= recorded.startedAtEpochMillis)
    }

    @Test
    fun `a failed compaction does not fail the run`() = runBlocking {
        val runtime = FailingCompactionRuntime()
        val memory = InMemoryMemoryStore()
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = ContextWindowManager(),
            memoryStore = memory,
            toolRegistry = StaticToolRegistry(),
            approvalGate = ReadOnlyApprovalGate(),
        )
        val transcript = (0 until 30).flatMap { turn ->
            listOf(
                ConversationMessage(role = MessageRole.USER, content = "Q $turn with a long ${"word".repeat(40)}"),
                ConversationMessage(role = MessageRole.ASSISTANT, content = "A $turn with a long ${"word".repeat(40)}"),
            )
        }
        val events = orchestrator.run(
            request = AgentRunRequest(
                conversationId = ConversationId("compact-fail"),
                messages = transcript,
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
        assertEquals(null, memory.workingSummary(ConversationId("compact-fail")))
    }
}

private class CapturingJournal : RunJournal {
    val entries = mutableListOf<RunJournalEntry>()
    override suspend fun upsert(entry: RunJournalEntry) {
        entries += entry
    }
}

/** Replies "Hello back" to the run, and "Summary of the earlier conversation" to compaction. */
private open class CompactingRuntime : ModelRuntime {
    override val model = ModelDescriptor(
        id = ModelId("compact-test"),
        displayName = "Compact",
        providerName = "Test",
        modelName = "compact",
        location = ModelLocation.LOCAL,
        contextWindowTokens = 640,
    )

    var mainRequest: GenerationRequest? = null

    override suspend fun availability() = RuntimeAvailability(available = true, summary = "Ready")

    private val compactionPrompt = "Write a dense factual summary"

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        if (request.messages.any { it.content.contains(compactionPrompt) }) {
            emit(GenerationEvent.Started("Compact"))
            emit(GenerationEvent.TextDelta("Summary of the earlier conversation"))
            emit(GenerationEvent.Finished("stop"))
        } else {
            mainRequest = request
            emit(GenerationEvent.Started("Test"))
            emit(GenerationEvent.Usage(TokenUsage(inputTokens = 100, outputTokens = 50)))
            emit(GenerationEvent.TextDelta("Hello back"))
            emit(GenerationEvent.Finished("stop"))
        }
    }
}

private class FailingCompactionRuntime : CompactingRuntime() {
    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        if (request.messages.any { it.content.contains("Write a dense factual summary") }) {
            emit(GenerationEvent.Failed("Summarizer is down", recoverable = true))
        } else {
            emit(GenerationEvent.Started("Test"))
            emit(GenerationEvent.TextDelta("Hello back"))
            emit(GenerationEvent.Finished("stop"))
        }
    }
}
