package io.github.kurue.bram.platform.android

import android.content.Context
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.RunJournal
import io.github.kurue.bram.core.domain.RunJournalEntry
import io.github.kurue.bram.core.domain.RunOrigin
import io.github.kurue.bram.core.domain.RunStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The persistent run journal: every agent run, from the orchestrator's start and finish calls,
 * stored in the same database as memory. Each entry records what the run did (tool turns) and
 * cost (tokens), plus why it ended, so agent activity can be inspected after the fact.
 */
class SqliteRunJournal(
    context: Context,
    /** The database file; tests pass their own so they never touch the app's real journal. */
    databaseName: String = MemoryDatabase.DB_NAME,
) : RunJournal {
    private val database = MemoryDatabase(context, databaseName)

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
                put("root_run_id", entry.rootRunId)
                put("origin", entry.origin.name)
                put("model_name", entry.modelName)
                entry.remote?.let { put("remote", if (it) 1 else 0) }
                entry.toolCalls?.let { put("tool_calls", it) }
                put("usage_measured", if (entry.usageMeasured) 1 else 0)
                put("journal_version", JOURNAL_VERSION)
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    /** The most recent runs, newest first — what the Settings diagnostics card shows. */
    suspend fun recent(limit: Int): List<RunJournalEntry> = query(null, null, "started_at DESC LIMIT $limit")

    /** Runs started in `[fromMillis, toMillis)`, oldest first: the input to Home's totals. */
    suspend fun between(fromMillis: Long, toMillis: Long): List<RunJournalEntry> =
        query("started_at >= ? AND started_at < ?", arrayOf(fromMillis.toString(), toMillis.toString()), "started_at ASC")

    /**
     * When complete records begin: the first run written with usage, origin, and tool counts.
     * Null when every row predates them (or there are none).
     */
    suspend fun recordedSince(): Long? = withContext(Dispatchers.IO) {
        database.readableDatabase.rawQuery(
            "SELECT MIN(started_at) FROM run_journal WHERE journal_version >= ?",
            arrayOf(JOURNAL_VERSION.toString()),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }

    private suspend fun query(selection: String?, args: Array<String>?, order: String): List<RunJournalEntry> =
        withContext(Dispatchers.IO) {
            database.readableDatabase.query("run_journal", null, selection, args, null, null, order).use { cursor ->
                fun int(name: String): Int? = cursor.getColumnIndex(name)
                    .takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getInt(it) }
                fun long(name: String): Long? = cursor.getColumnIndex(name)
                    .takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getLong(it) }
                fun string(name: String): String? = cursor.getColumnIndex(name)
                    .takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getString(it) }
                val out = mutableListOf<RunJournalEntry>()
                while (cursor.moveToNext()) {
                    val input = int("input_tokens")
                    val output = int("output_tokens")
                    val version = int("journal_version") ?: 1
                    out += RunJournalEntry(
                        id = string("id").orEmpty(),
                        conversationId = ConversationId(string("conversation_id").orEmpty()),
                        startedAtEpochMillis = long("started_at") ?: 0L,
                        finishedAtEpochMillis = long("finished_at"),
                        status = runCatching { RunStatus.valueOf(string("status").orEmpty()) }
                            .getOrDefault(RunStatus.FAILED),
                        toolTurns = int("tool_turns") ?: 0,
                        inputTokens = input,
                        outputTokens = output,
                        error = string("error"),
                        rootRunId = string("root_run_id"),
                        origin = runCatching { RunOrigin.valueOf(string("origin").orEmpty()) }
                            .getOrDefault(RunOrigin.UNKNOWN),
                        modelName = string("model_name"),
                        remote = int("remote")?.let { it == 1 },
                        toolCalls = int("tool_calls"),
                        // Old rows stored a zero total as null, so for them "has a number" is the
                        // best available signal; newer rows say so explicitly.
                        usageMeasured = if (version >= JOURNAL_VERSION) int("usage_measured") == 1 else input != null || output != null,
                    )
                }
                out
            }
        }

    private companion object {
        const val JOURNAL_VERSION = 2
    }
}
