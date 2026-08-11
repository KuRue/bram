package io.github.kurue.bram.app.test

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.Embedder
import io.github.kurue.bram.core.domain.MemoryKind
import io.github.kurue.bram.core.domain.MemoryRecord
import io.github.kurue.bram.platform.android.PersistentMemoryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Maps appearance-related text to one direction and everything else to an orthogonal one. */
private val fakeAppearanceEmbedder = object : Embedder {
    override suspend fun embed(text: String): FloatArray {
        val lower = text.lowercase()
        return if ("dark mode" in lower || "display" in lower || "appearance" in lower ||
            "settings" in lower || "theme" in lower || "reading" in lower
        ) {
            floatArrayOf(1f, 0f)
        } else {
            floatArrayOf(0f, 1f)
        }
    }
}

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
        // Use near-current timestamps so the test records are genuinely the newest in a shared DB
        // that may already hold real memories from prior runs.
        val base = System.currentTimeMillis()
        val older = MemoryRecord("verify-older", MemoryKind.SEMANTIC_FACT, "Older fact", createdAtEpochMillis = base)
        val newer = MemoryRecord("verify-newer", MemoryKind.USER_INSTRUCTION, "Newer instruction", createdAtEpochMillis = base + 60_000)
        val summary = MemoryRecord("verify-summary", MemoryKind.WORKING_SUMMARY, "Hidden working summary", createdAtEpochMillis = base + 120_000)

        try {
            store.put(conversation, older)
            store.put(conversation, newer)
            store.put(conversation, summary)

            val recent = store.recent(100)
            val mine = recent.filter { it.id in setOf("verify-older", "verify-newer") }
            assertTrue("older should be present", mine.any { it.id == "verify-older" })
            assertTrue("newer should be present", mine.any { it.id == "verify-newer" })
            assertFalse("working summaries must be hidden", recent.any { it.id == "verify-summary" })
            assertTrue("newest first among the test records", mine.indexOfFirst { it.id == "verify-newer" } < mine.indexOfFirst { it.id == "verify-older" })

            store.remove("verify-older")
            val after = store.recent(100)
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

            val top = store.mostImportant(100)
            val mine = top.filter { it.id in setOf("verify-low", "verify-high") }
            // Highest importance first among the test records, working summaries hidden.
            assertTrue("high-importance record present", mine.any { it.id == "verify-high" })
            assertTrue(
                "higher importance ranks before lower",
                mine.indexOfFirst { it.id == "verify-high" } < mine.indexOfFirst { it.id == "verify-low" },
            )
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

    @Test
    fun aSemanticQueryRecallsAMemoryWithNoSharedWords() = runBlocking {
        val store = PersistentMemoryStore(
            ApplicationProvider.getApplicationContext<BramApplication>(),
            fakeAppearanceEmbedder,
        )
        val conversation = ConversationId("memory-semantic-verification")
        val fact = MemoryRecord(
            "verify-semantic-fact",
            MemoryKind.SEMANTIC_FACT,
            "The user prefers dark mode for reading at night",
            importance = 0.8,
        )
        try {
            store.put(conversation, fact)
            // "appearance settings" shares no words with the stored memory, so keyword (FTS) recall
            // misses it entirely; the embedding pass must surface it instead.
            val hits = store.search(conversation, "appearance settings", 10)
            assertTrue(
                "a semantic match must be recalled even with no shared words",
                hits.any { it.id == "verify-semantic-fact" },
            )
        } finally {
            store.remove("verify-semantic-fact")
        }
    }
}
