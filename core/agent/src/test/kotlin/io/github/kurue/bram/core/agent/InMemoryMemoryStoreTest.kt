package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.MemoryKind
import io.github.kurue.bram.core.domain.MemoryRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryMemoryStoreTest {
    private val conversation = ConversationId("chat")

    @Test
    fun `recent returns memories newest first across conversations`() = runBlocking {
        val store = InMemoryMemoryStore()
        store.put(conversation, memory("a", "older", createdAt = 1_000))
        store.put(ConversationId("other"), memory("b", "newer", createdAt = 5_000))

        val recent = store.recent(10)

        assertEquals(listOf("newer", "older"), recent.map { it.text })
    }

    @Test
    fun `recent hides working summaries which are an internal scratchpad`() = runBlocking {
        val store = InMemoryMemoryStore()
        store.put(conversation, MemoryRecord("ws", MemoryKind.WORKING_SUMMARY, "summary", createdAtEpochMillis = 9_000))
        store.put(conversation, memory("fact", "a real fact", createdAt = 1_000))

        val recent = store.recent(10)

        assertEquals(listOf("a real fact"), recent.map { it.text })
    }

    @Test
    fun `recent honors the limit`() = runBlocking {
        val store = InMemoryMemoryStore()
        store.put(conversation, memory("a", "first", createdAt = 1_000))
        store.put(conversation, memory("b", "second", createdAt = 2_000))
        store.put(conversation, memory("c", "third", createdAt = 3_000))

        val recent = store.recent(2)

        assertEquals(listOf("third", "second"), recent.map { it.text })
    }

    @Test
    fun `remove drops only the matching id wherever it lives`() = runBlocking {
        val store = InMemoryMemoryStore()
        store.put(ConversationId("one"), memory("a", "keep", createdAt = 1_000))
        store.put(ConversationId("two"), memory("b", "forget", createdAt = 2_000))

        store.remove("b")

        val recent = store.recent(10)
        assertTrue(recent.any { it.id == "a" })
        assertFalse(recent.any { it.id == "b" })
    }

    @Test
    fun `remove is a no-op for an id that is not present`() = runBlocking {
        val store = InMemoryMemoryStore()
        store.put(conversation, memory("a", "keep", createdAt = 1_000))

        store.remove("missing")

        assertEquals(listOf("keep"), store.recent(10).map { it.text })
    }

    private fun memory(id: String, text: String, createdAt: Long) =
        MemoryRecord(id, MemoryKind.SEMANTIC_FACT, text, importance = 0.5, createdAtEpochMillis = createdAt)
}
