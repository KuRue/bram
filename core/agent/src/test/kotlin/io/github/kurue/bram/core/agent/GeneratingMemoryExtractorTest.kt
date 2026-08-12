package io.github.kurue.bram.core.agent

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
import io.github.kurue.bram.core.domain.RuntimeAvailability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratingMemoryExtractorTest {
    @Test
    fun `parses memories from the extraction generation`() = runBlocking {
        val runtime = ScriptedRuntime(
            extractionReply = """[{"kind":"fact","text":"The user's dog is named Pip"},{"kind":"instruction","text":"Keep answers short"}]""",
        )
        val out = GeneratingMemoryExtractor().extract(
            conversationId = ConversationId("c1"),
            userMessage = ConversationMessage(role = MessageRole.USER, content = "My dog Pip is hungry."),
            assistantReply = "Feed Pip a small meal.",
            runtime = runtime,
        )
        assertEquals(2, out.size)
        assertEquals(MemoryKind.SEMANTIC_FACT, out[0].kind)
        assertEquals("The user's dog is named Pip", out[0].text)
        assertEquals(MemoryKind.USER_INSTRUCTION, out[1].kind)
        assertTrue(runtime.extractionCalls == 1)
    }

    @Test
    fun `parses an episode alongside facts`() = runBlocking {
        val runtime = ScriptedRuntime(
            extractionReply = """[{"kind":"fact","text":"Uses Kotlin"},{"kind":"episode","text":"Walked through enabling cookie replay in web_fetch"}]""",
        )
        val out = GeneratingMemoryExtractor().extract(
            conversationId = ConversationId("c1"),
            userMessage = ConversationMessage(role = MessageRole.USER, content = "How do I fix the loop?"),
            assistantReply = "Add cookie replay across redirects.",
            runtime = runtime,
        )
        assertEquals(2, out.size)
        assertEquals(MemoryKind.SEMANTIC_FACT, out[0].kind)
        assertEquals(MemoryKind.EPISODE, out[1].kind)
        assertEquals("Walked through enabling cookie replay in web_fetch", out[1].text)
    }

    @Test
    fun `a null or blank user message extracts nothing without calling the runtime`() = runBlocking {
        val runtime = ScriptedRuntime(extractionReply = """[{"kind":"fact","text":"should not happen"}]""")
        val extractor = GeneratingMemoryExtractor()

        assertTrue(
            extractor.extract(ConversationId("c"), userMessage = null, assistantReply = "reply", runtime = runtime).isEmpty(),
        )
        assertTrue(
            extractor.extract(
                ConversationId("c"),
                userMessage = ConversationMessage(role = MessageRole.USER, content = "   "),
                assistantReply = "reply",
                runtime = runtime,
            ).isEmpty(),
        )
        assertEquals(0, runtime.extractionCalls)
    }

    @Test
    fun `a failed extraction generation yields nothing and does not throw`() = runBlocking {
        val runtime = ScriptedRuntime(extractionReply = "", fail = true)
        val out = GeneratingMemoryExtractor().extract(
            conversationId = ConversationId("c1"),
            userMessage = ConversationMessage(role = MessageRole.USER, content = "remember this"),
            assistantReply = "ok",
            runtime = runtime,
        )
        assertTrue(out.isEmpty())
    }
}

private class ScriptedRuntime(
    private val extractionReply: String,
    private val fail: Boolean = false,
) : ModelRuntime {
    var extractionCalls = 0
    private var mainCalls = 0

    override val model = ModelDescriptor(
        id = ModelId("extractor-test"),
        displayName = "Extractor",
        providerName = "Test",
        modelName = "extractor",
        location = ModelLocation.LOCAL,
        contextWindowTokens = 4_096,
    )

    override suspend fun availability() = RuntimeAvailability(available = true, summary = "Ready")

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        val isExtraction = request.messages.any { it.role == MessageRole.SYSTEM && it.content.startsWith("Read the exchange") }
        if (!isExtraction) {
            mainCalls++
            emit(GenerationEvent.Started("Test"))
            emit(GenerationEvent.Finished("stop"))
            return@flow
        }
        extractionCalls++
        emit(GenerationEvent.Started("Extract"))
        if (fail) {
            emit(GenerationEvent.Failed("Model is down", recoverable = true))
        } else {
            emit(GenerationEvent.TextDelta(extractionReply))
            emit(GenerationEvent.Finished("stop"))
        }
    }
}
