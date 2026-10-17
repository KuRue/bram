package io.github.kurue.bram.app.test

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.RunJournalEntry
import io.github.kurue.bram.core.domain.RunOrigin
import io.github.kurue.bram.core.domain.RunStatus
import io.github.kurue.bram.platform.android.SqliteRunJournal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The run journal's upgrade on a real SQLite: a database written by the previous version (schema 2,
 * as on phones today) keeps its rows, and new runs record the fields Home's totals need. Uses its
 * own database file, so the app's journal and the other tests' state are never touched.
 */
class RunJournalMigrationOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun cleanUp() {
        context.deleteDatabase(DB)
    }

    @Test
    fun aVersionTwoJournalUpgradesInPlaceAndKeepsItsRows() = runBlocking {
        context.deleteDatabase(DB)
        val file = context.getDatabasePath(DB).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            // The previous schema, exactly as MemoryDatabase version 2 created it.
            db.execSQL(
                "CREATE TABLE memory_records (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, " +
                    "kind TEXT NOT NULL, text TEXT NOT NULL, importance REAL NOT NULL, created_at INTEGER NOT NULL, " +
                    "source TEXT NOT NULL DEFAULT '', source_message_ids TEXT NOT NULL DEFAULT '')",
            )
            db.execSQL(
                "CREATE TABLE run_journal (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL, " +
                    "started_at INTEGER NOT NULL, finished_at INTEGER, status TEXT NOT NULL, " +
                    "tool_turns INTEGER NOT NULL DEFAULT 0, input_tokens INTEGER, output_tokens INTEGER, error TEXT)",
            )
            db.execSQL("CREATE TABLE memory_vectors (memory_id TEXT PRIMARY KEY, embedding BLOB NOT NULL)")
            db.execSQL(
                "INSERT INTO run_journal (id, conversation_id, started_at, finished_at, status, tool_turns, " +
                    "input_tokens, output_tokens) VALUES ('old', 'c', 1000, 2000, 'SUCCEEDED', 2, 300, 40)",
            )
            db.execSQL(
                "INSERT INTO run_journal (id, conversation_id, started_at, finished_at, status) " +
                    "VALUES ('old-unmeasured', 'c', 1500, 2500, 'SUCCEEDED')",
            )
            db.version = 2
        }

        val journal = SqliteRunJournal(context, DB)
        val old = journal.recent(10).associateBy { it.id }
        assertEquals("rows written before the upgrade survive", setOf("old", "old-unmeasured"), old.keys)
        assertTrue("an old row with token numbers counts as measured", old.getValue("old").usageMeasured)
        assertFalse("an old row without them is unknown", old.getValue("old-unmeasured").usageMeasured)
        assertNull("old rows never counted tool executions", old.getValue("old").toolCalls)
        assertNull("no complete records yet", journal.recordedSince())

        journal.upsert(
            RunJournalEntry(
                id = "new",
                conversationId = ConversationId("c"),
                startedAtEpochMillis = 5000,
                finishedAtEpochMillis = 6000,
                status = RunStatus.SUCCEEDED,
                inputTokens = 0,
                outputTokens = 0,
                rootRunId = "turn",
                origin = RunOrigin.CHAT,
                modelName = "Test",
                remote = true,
                toolCalls = 3,
                usageMeasured = true,
            ),
        )
        val fresh = journal.between(4000, 7000).single()
        assertEquals("turn", fresh.rootRunId)
        assertEquals(RunOrigin.CHAT, fresh.origin)
        assertEquals(true, fresh.remote)
        assertEquals(3, fresh.toolCalls)
        assertTrue("a measured zero stays measured", fresh.usageMeasured)
        assertEquals(0, fresh.outputTokens)
        assertEquals(5000L, journal.recordedSince())
    }

    private companion object {
        const val DB = "run-journal-migration-test.db"
    }
}
