package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.MessageRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowManagerTest {
    @Test
    fun `reserves output and keeps newest coherent turn`() = runBlocking {
        val messages = buildList {
            repeat(10) { index ->
                add(ConversationMessage(role = MessageRole.USER, content = "old question $index " + "x".repeat(180)))
                add(ConversationMessage(role = MessageRole.ASSISTANT, content = "old answer $index " + "y".repeat(180)))
            }
            add(ConversationMessage(role = MessageRole.USER, content = "newest question"))
            add(ConversationMessage(role = MessageRole.ASSISTANT, content = "newest answer"))
        }

        val plan = ContextWindowManager().plan(
            systemPrompt = "Be helpful.",
            transcript = messages,
            contextWindowTokens = 512,
            requestedOutputTokens = 128,
            workingSummary = null,
            retrievedMemories = emptyList(),
        )

        assertEquals(128, plan.reservedOutputTokens)
        assertTrue(plan.messages.any { it.content == "newest question" })
        assertTrue(plan.messages.any { it.content == "newest answer" })
        assertTrue(plan.omittedMessageIds.isNotEmpty())
        assertTrue(plan.estimatedInputTokens <= plan.inputBudgetTokens)
    }
}
