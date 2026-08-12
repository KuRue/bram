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

    @Test
    fun `mostImportant ranks by importance then recency`() = runBlocking {
        val store = InMemoryMemoryStore()
        store.put(conversation, memory("low", "minor", createdAt = 1_000, importance = 0.2))
        store.put(conversation, memory("high", "major", createdAt = 2_000, importance = 0.9))
        store.put(conversation, memory("mid", "medium", createdAt = 3_000, importance = 0.5))

        assertEquals(listOf("major", "medium", "minor"), store.mostImportant(10).map { it.text })
    }

    @Test
    fun `mostImportant breaks ties newest first`() = runBlocking {
        val store = InMemoryMemoryStore()
        store.put(conversation, memory("a", "older", createdAt = 1_000, importance = 0.8))
        store.put(conversation, memory("b", "newer", createdAt = 5_000, importance = 0.8))

        assertEquals(listOf("newer", "older"), store.mostImportant(10).map { it.text })
    }

    @Test
    fun `mostImportant hides working summaries`() = runBlocking {
        val store = InMemoryMemoryStore()
        store.put(conversation, MemoryRecord("ws", MemoryKind.WORKING_SUMMARY, "summary", importance = 1.0, createdAtEpochMillis = 9_000))
        store.put(conversation, memory("fact", "real fact", createdAt = 1_000, importance = 0.5))

        val top = store.mostImportant(10)
        assertEquals(listOf("real fact"), top.map { it.text })
    }

    private fun memory(id: String, text: String, createdAt: Long, importance: Double = 0.5) =
        MemoryRecord(id, MemoryKind.SEMANTIC_FACT, text, importance = importance, createdAtEpochMillis = createdAt)

    private fun episode(id: String, text: String, createdAt: Long) =
        MemoryRecord(id, MemoryKind.EPISODE, text, importance = 0.5, createdAtEpochMillis = createdAt)

    @Test
    fun `episodes are capped to the newest N per conversation`() = runBlocking {
        val store = InMemoryMemoryStore()
        // N + 5 episodes, oldest first; the cap should keep only the newest N.
        val cap = io.github.kurue.bram.core.domain.MAX_EPISODES_PER_CONVERSATION
        for (i in 0 until cap + 5) {
            store.put(conversation, episode("ep-$i", "episode $i", createdAt = 1_000L + i))
        }

        val episodes = store.recent(100).filter { it.kind == MemoryKind.EPISODE }
        assertEquals(cap, episodes.size)
        // The oldest five (ep-0..ep-4) are gone; the newest N remain.
        assertTrue(episodes.none { it.id == "ep-0" })
        assertTrue(episodes.none { it.id == "ep-4" })
        assertTrue(episodes.any { it.id == "ep-${cap + 4}" })
    }

    @Test
    fun `the episode cap does not touch facts or instructions in the same conversation`() = runBlocking {
        val store = InMemoryMemoryStore()
        val cap = io.github.kurue.bram.core.domain.MAX_EPISODES_PER_CONVERSATION
        store.put(conversation, memory("fact", "a real fact", createdAt = 500))
        for (i in 0 until cap + 1) {
            store.put(conversation, episode("ep-$i", "episode $i", createdAt = 1_000L + i))
        }

        val records = store.recent(100)
        assertEquals(cap, records.count { it.kind == MemoryKind.EPISODE })
        assertTrue(records.any { it.kind == MemoryKind.SEMANTIC_FACT && it.id == "fact" })
    }
}
