package io.github.kurue.bram.app.test

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.MemoryKind
import io.github.kurue.bram.core.domain.MemoryRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real on-device SQLite memory store: that [MemoryStore.recent] returns memories
 * newest-first across conversations and hides working summaries, and that [MemoryStore.remove]
 * drops only the matching row (the FTS trigger cleans the index). The in-memory fake is covered
 * in core:agent; this is the SQLite half that needs Android.
 */
@RunWith(AndroidJUnit4::class)
class MemoryStoreOnDeviceTest {

    @Test
    fun recentAndRemoveAgainstTheRealStore() = runBlocking {
        val store = ApplicationProvider.getApplicationContext<BramApplication>().container.memoryStore
        val conversation = ConversationId("memory-store-verification")
        val older = MemoryRecord("verify-older", MemoryKind.SEMANTIC_FACT, "Older fact", createdAtEpochMillis = 1_000)
        val newer = MemoryRecord("verify-newer", MemoryKind.USER_INSTRUCTION, "Newer instruction", createdAtEpochMillis = 9_000)
        val summary = MemoryRecord("verify-summary", MemoryKind.WORKING_SUMMARY, "Hidden working summary", createdAtEpochMillis = 20_000)

        try {
            store.put(conversation, older)
            store.put(conversation, newer)
            store.put(conversation, summary)

            val recent = store.recent(10)
            assertTrue("older should be present", recent.any { it.id == "verify-older" })
            assertTrue("newer should be present", recent.any { it.id == "verify-newer" })
            assertFalse("working summaries must be hidden", recent.any { it.id == "verify-summary" })
            assertEquals("newest first", "verify-newer", recent.first().id)

            store.remove("verify-older")
            val after = store.recent(10)
            assertFalse("removed id must be gone", after.any { it.id == "verify-older" })
            assertTrue("unrelated id must remain", after.any { it.id == "verify-newer" })
        } finally {
            store.remove("verify-older")
            store.remove("verify-newer")
            store.remove("verify-summary")
        }
    }

    @Test
    fun mostImportantAgainstTheRealStore() = runBlocking {
        val store = ApplicationProvider.getApplicationContext<BramApplication>().container.memoryStore
        val conversation = ConversationId("memory-importance-verification")
        val low = MemoryRecord("verify-low", MemoryKind.SEMANTIC_FACT, "Low-importance fact", importance = 0.1, createdAtEpochMillis = 1_000)
        val high = MemoryRecord("verify-high", MemoryKind.USER_INSTRUCTION, "High-importance instruction", importance = 0.95, createdAtEpochMillis = 2_000)
        val summary = MemoryRecord("verify-imp-summary", MemoryKind.WORKING_SUMMARY, "Hidden summary", importance = 1.0, createdAtEpochMillis = 3_000)

        try {
            store.put(conversation, low)
            store.put(conversation, high)
            store.put(conversation, summary)

            val top = store.mostImportant(10)
            // Highest importance first, working summaries hidden.
            assertEquals("verify-high", top.first().id)
            assertTrue(top.any { it.id == "verify-low" })
            assertFalse("working summaries must not be injected", top.any { it.id == "verify-imp-summary" })
        } finally {
            store.remove("verify-low")
            store.remove("verify-high")
            store.remove("verify-imp-summary")
        }
    }

    @Test
    fun aQueryWithOnlyShortTermsReturnsEmptyRatherThanCrashing() = runBlocking {
        val store = ApplicationProvider.getApplicationContext<BramApplication>().container.memoryStore
        val conversation = ConversationId("memory-short-query-verification")
        val fact = MemoryRecord(
            "verify-short-fact",
            MemoryKind.SEMANTIC_FACT,
            "The user lives in Tokyo",
            importance = 0.8,
        )
        try {
            store.put(conversation, fact)
            // A query whose every term is two characters or shorter collapses to an empty FTS
            // expression, which SQLite rejects. The store must short-circuit rather than throw.
            val shortHits = store.search(conversation, "hi", 10)
            assertTrue("a too-short query recalls nothing, quietly", shortHits.isEmpty())
            // A real word still matches, so the guard did not break ordinary search.
            val tokyoHits = store.search(conversation, "Tokyo", 10)
            assertTrue("a real term still recalls", tokyoHits.any { it.id == "verify-short-fact" })
        } finally {
            store.remove("verify-short-fact")
        }
    }
}
