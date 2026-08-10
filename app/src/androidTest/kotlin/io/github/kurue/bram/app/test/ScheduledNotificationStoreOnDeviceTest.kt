package io.github.kurue.bram.app.test

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.app.ScheduledNotificationStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The reminder store is what makes agent-set notifications survive a reboot. Verifies the
 * add/load/remove round trip against the real app files dir, since the file is the only thing
 * standing between a scheduled reminder and a cleared-alarm reboot.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledNotificationStoreOnDeviceTest {

    @Test
    fun addAndRemoveRoundTripsAgainstTheRealFile() = runBlocking {
        val store = ScheduledNotificationStore(
            ApplicationProvider.getApplicationContext<BramApplication>(),
        )
        val entry = ScheduledNotificationStore.Entry(
            id = "verify-roundtrip",
            triggerAtEpochMillis = 9_000,
            title = "verify title",
            body = "verify body",
        )
        try {
            store.add(entry)
            val loaded = store.load()
            assertTrue("entry persisted", loaded.any { it.id == "verify-roundtrip" })
            val match = loaded.first { it.id == "verify-roundtrip" }
            assertEquals("verify title", match.title)
            assertEquals("verify body", match.body)
            assertEquals(9_000L, match.triggerAtEpochMillis)

            // Adding again with the same id replaces rather than duplicating.
            store.add(entry.copy(title = "updated"))
            assertEquals(1, store.load().count { it.id == "verify-roundtrip" })
            assertEquals("updated", store.load().first { it.id == "verify-roundtrip" }.title)

            store.remove("verify-roundtrip")
            assertTrue("entry removed", store.load().none { it.id == "verify-roundtrip" })
        } finally {
            store.remove("verify-roundtrip")
        }
    }
}
