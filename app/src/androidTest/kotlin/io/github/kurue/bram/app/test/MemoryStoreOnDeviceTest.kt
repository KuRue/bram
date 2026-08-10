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
}
