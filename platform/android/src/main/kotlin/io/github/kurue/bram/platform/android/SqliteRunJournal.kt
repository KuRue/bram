package io.github.kurue.bram.platform.android

import android.content.Context
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.RunJournal
import io.github.kurue.bram.core.domain.RunJournalEntry
import io.github.kurue.bram.core.domain.RunStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The persistent run journal: every agent run, from the orchestrator's start and finish calls,
 * stored in the same database as memory. Each entry records what the run did (tool turns) and
 * cost (tokens), plus why it ended, so agent activity can be inspected after the fact.
 */
class SqliteRunJournal(context: Context) : RunJournal {
    private val database = MemoryDatabase(context)

    override suspend fun upsert(entry: RunJournalEntry) = withContext(Dispatchers.IO) {
        database.writableDatabase.insertWithOnConflict(
            "run_journal",
            null,
            android.content.ContentValues().apply {
                put("id", entry.id)
                put("conversation_id", entry.conversationId.value)
                put("started_at", entry.startedAtEpochMillis)
                put("finished_at", entry.finishedAtEpochMillis)
                put("status", entry.status.name)
                put("tool_turns", entry.toolTurns)
                put("input_tokens", entry.inputTokens)
                put("output_tokens", entry.outputTokens)
                put("error", entry.error)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    /** The most recent runs, newest first — what the Settings diagnostics card shows. */
    suspend fun recent(limit: Int): List<RunJournalEntry> = withContext(Dispatchers.IO) {
        database.readableDatabase.query(
            "run_journal",
            null,
            null,
            null,
            null,
            null,
            "started_at DESC LIMIT $limit",
        ).use { cursor ->
            val out = mutableListOf<RunJournalEntry>()
            while (cursor.moveToNext()) {
                val finishedAtIndex = cursor.getColumnIndex("finished_at")
                out += RunJournalEntry(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    conversationId = ConversationId(cursor.getString(cursor.getColumnIndexOrThrow("conversation_id"))),
                    startedAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
                    finishedAtEpochMillis = if (cursor.isNull(finishedAtIndex)) {
                        null
                    } else {
                        cursor.getLong(finishedAtIndex)
                    },
                    status = runCatching {
                        RunStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status")))
                    }.getOrDefault(RunStatus.FAILED),
                    toolTurns = cursor.getInt(cursor.getColumnIndexOrThrow("tool_turns")),
                    inputTokens = cursor.getColumnIndex("input_tokens")
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { cursor.getInt(it) },
                    outputTokens = cursor.getColumnIndex("output_tokens")
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { cursor.getInt(it) },
                    error = cursor.getColumnIndex("error")
                        .takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { cursor.getString(it) },
                )
            }
            out
        }
    }
}
