package io.github.kurue.bram.app.test

import androidx.test.platform.app.InstrumentationRegistry
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ToolCall
import io.github.kurue.bram.platform.android.ConversationStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The conversation file must carry the model's tool history, not just the display text, or the
 * next turn cannot see what it already ran. Tool calls, their recovered flag, and the tool
 * results keyed by call id all round-trip here.
 */
class ConversationHistoryOnDeviceTest {

    @Test
    fun conversationStoreRoundTripsToolHistory() = runBlocking {
        val store = ConversationStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val id = store.newId()
        val messages = listOf(
            ConversationMessage(role = MessageRole.USER, content = "check the device"),
            ConversationMessage(
                role = MessageRole.ASSISTANT,
                content = "Let me look.",
                toolCalls = listOf(
                    ToolCall(id = "call_1", name = "device_status", argumentsJson = "{}"),
                    ToolCall(
                        id = "call_2",
                        name = "web_fetch",
                        argumentsJson = """{"url":"https://example.com"}""",
                        recovered = true,
                    ),
                ),
            ),
            ConversationMessage(role = MessageRole.TOOL, content = """{"battery":"80%"}""", toolCallId = "call_1"),
            ConversationMessage(role = MessageRole.TOOL, content = """{"error":"permission_denied"}""", toolCallId = "call_2"),
            ConversationMessage(role = MessageRole.ASSISTANT, content = "Battery is 80%."),
        )
        try {
            val summary = store.save(id, messages)
            assertEquals(3, summary.messageCount)

            val loaded = store.load(id)
            assertEquals(messages.size, loaded.size)
            loaded.forEachIndexed { index, message ->
                assertEquals(messages[index].role, message.role)
                assertEquals(messages[index].content, message.content)
                assertEquals(messages[index].toolCalls, message.toolCalls)
                assertEquals(messages[index].toolCallId, message.toolCallId)
            }
        } finally {
            store.delete(id)
        }
    }
}
