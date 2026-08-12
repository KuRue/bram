package io.github.kurue.bram.platform.android

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.Embedder
import io.github.kurue.bram.core.domain.MAX_EPISODES_PER_CONVERSATION
import io.github.kurue.bram.core.domain.MemoryKind
import io.github.kurue.bram.core.domain.MemoryRecord
import io.github.kurue.bram.core.domain.MemoryStore
import io.github.kurue.bram.core.domain.MessageId
import io.github.kurue.bram.core.domain.VectorSearch
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Memory that survives the app: one SQLite file with an FTS4 index over the memory text.
 *
 * Room was considered and set aside — the schema below is exactly what Room would generate, but
 * pulling Room and KSP into the build for two tables would add a compiler-plugin dependency for
 * nothing this project needs yet, and the record of what is stored (conversation, kind, provenance
 * of where it came from) is what matters for retrieval, not the ORM.
 *
 * The FTS4 index is kept in sync by triggers on the content table, which is the pattern SQLite
 * documents for external-content FTS; a query runs against the index and joins the row back for
 * its conversation and provenance columns.
 */
class PersistentMemoryStore(
    context: Context,
    private val embedder: Embedder? = null,
) : MemoryStore {
    private val database = MemoryDatabase(context)

    override suspend fun workingSummary(conversationId: ConversationId): MemoryRecord? =
        withContext(Dispatchers.IO) {
            database.readableDatabase.query(
                "memory_records",
                null,
                "conversation_id = ? AND kind = ?",
                arrayOf(conversationId.value, MemoryKind.WORKING_SUMMARY.name),
                null,
                null,
                "created_at DESC LIMIT 1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.toMemoryRecord()
            }
        }

    override suspend fun search(
        conversationId: ConversationId,
        query: String,
        limit: Int,
    ): List<MemoryRecord> = withContext(Dispatchers.IO) {
        val ftsHits = ftsRanked(
            SEARCH_BY_CONVERSATION_SQL,
            arrayOf(fuzzyMatchQuery(query), conversationId.value, (limit * 2).toString()),
        )
        recall(query, ftsHits, limit) { loadScopedVectors(conversationId.value) }
    }

    override suspend fun searchAll(query: String, limit: Int): List<MemoryRecord> =
        withContext(Dispatchers.IO) {
            val ftsHits = ftsRanked(
                SEARCH_ALL_SQL,
                arrayOf(fuzzyMatchQuery(query), (limit * 2).toString()),
            )
            recall(query, ftsHits, limit) { loadScopedVectors(null) }
        }

    /**
     * Runs the keyword ranking, short-circuiting to nothing when the query has no usable terms: a
     * too-short message collapses to an empty FTS MATCH expression that SQLite rejects.
     */
    private fun ftsRanked(sql: String, args: Array<String>): List<MemoryRecord> {
        if (args.firstOrNull().isNullOrBlank()) return emptyList()
        return database.readableDatabase.rawQuery(sql, args).use { cursor -> cursor.rows() }
    }

    /**
     * Fuses the keyword ranking with an embedding ranking when an embedder is configured, falling
     * back to keyword recall alone otherwise. The embedding pass is best-effort throughout: a
     * failed query embed keeps the run going on keywords rather than taking the turn down.
     */
    private suspend fun recall(
        query: String,
        ftsHits: List<MemoryRecord>,
        limit: Int,
        candidates: () -> List<Pair<MemoryRecord, FloatArray>>,
    ): List<MemoryRecord> {
        val active = embedder ?: return ftsHits.take(limit)
        val queryVector = runCatching { active.embed(query) }.getOrNull()
            ?: return ftsHits.take(limit)
        val vectorHits = candidates()
            .sortedByDescending { (_, vector) -> VectorSearch.cosine(queryVector, vector) }
            .map { (record, _) -> record }
        return VectorSearch.fuseRanked(listOf(ftsHits, vectorHits)).take(limit)
    }

    /** Loads every memory in scope that has a stored embedding, for the cosine pass. */
    private fun loadScopedVectors(conversationId: String?): List<Pair<MemoryRecord, FloatArray>> {
        val sql = if (conversationId != null) {
            "SELECT r.id, r.conversation_id, r.kind, r.text, r.importance, r.created_at, " +
                "r.source, r.source_message_ids, v.embedding FROM memory_records r " +
                "JOIN memory_vectors v ON r.id = v.memory_id " +
                "WHERE r.conversation_id = ? AND r.kind != ?"
        } else {
            "SELECT r.id, r.conversation_id, r.kind, r.text, r.importance, r.created_at, " +
                "r.source, r.source_message_ids, v.embedding FROM memory_records r " +
                "JOIN memory_vectors v ON r.id = v.memory_id WHERE r.kind != ?"
        }
        val args = if (conversationId != null) {
            arrayOf(conversationId, MemoryKind.WORKING_SUMMARY.name)
        } else {
            arrayOf(MemoryKind.WORKING_SUMMARY.name)
        }
        return database.readableDatabase.rawQuery(sql, args).use { cursor ->
            val out = mutableListOf<Pair<MemoryRecord, FloatArray>>()
            while (cursor.moveToNext()) {
                val record = cursor.toMemoryRecord()
                val vector = runCatching { decodeVector(cursor.getBlob(cursor.getColumnIndexOrThrow("embedding"))) }
                    .getOrDefault(FloatArray(0))
                if (vector.isNotEmpty()) out += record to vector
            }
            out
        }
    }

    private fun storeVector(id: String, vector: FloatArray) {
        database.writableDatabase.insertWithOnConflict(
            "memory_vectors",
            null,
            android.content.ContentValues().apply {
                put("memory_id", id)
                put("embedding", encodeVector(vector))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun encodeVector(vector: FloatArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(vector.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    private fun decodeVector(blob: ByteArray): FloatArray {
        if (blob.isEmpty() || blob.size % 4 != 0) return FloatArray(0)
        val buffer = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(blob.size / 4)
        buffer.asFloatBuffer().get(out)
        return out
    }

    override suspend fun put(conversationId: ConversationId, memory: MemoryRecord) =
        withContext(Dispatchers.IO) {
            database.writableDatabase.insertWithOnConflict(
                "memory_records",
                null,
                android.content.ContentValues().apply {
                    put("id", memory.id)
                    put("conversation_id", conversationId.value)
                    put("kind", memory.kind.name)
                    put("text", memory.text)
                    put("importance", memory.importance)
                    put("created_at", memory.createdAtEpochMillis)
                    put("source", memory.metadata["source"].orEmpty())
                    put(
                        "source_message_ids",
                        memory.sourceMessageIds.joinToString(",") { it.value },
                    )
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            // Working summaries are a per-conversation scratchpad excluded from recall, so spending
            // an embedding on them is wasted work — search filters them out regardless.
            embedder?.let { active ->
                if (memory.kind != MemoryKind.WORKING_SUMMARY) {
                    runCatching { active.embed(memory.text) }.getOrNull()?.let { vector ->
                        if (vector.isNotEmpty()) runCatching { storeVector(memory.id, vector) }
                    }
                }
            }
            // Episodes are written every substantial turn; keep only the newest N for this
            // conversation so a long-running thread cannot let them grow the store and the vector
            // table without end.
            if (memory.kind == MemoryKind.EPISODE) {
                trimEpisodes(conversationId.value)
            }
            Unit
        }

    /**
     * Deletes the oldest episodes beyond [MAX_EPISODES_PER_CONVERSATION] for [conversationId], along
     * with their embedding vectors. `LIMIT -1 OFFSET keep` selects everything after the newest N.
     */
    private fun trimEpisodes(conversationId: String) {
        val removable = database.readableDatabase.rawQuery(
            "SELECT id FROM memory_records WHERE kind = ? AND conversation_id = ? " +
                "ORDER BY created_at DESC LIMIT -1 OFFSET ?",
            arrayOf(MemoryKind.EPISODE.name, conversationId, MAX_EPISODES_PER_CONVERSATION.toString()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        for (id in removable) {
            // The AFTER DELETE trigger on memory_records keeps the FTS index in sync.
            database.writableDatabase.delete("memory_records", "id = ?", arrayOf(id))
            database.writableDatabase.delete("memory_vectors", "memory_id = ?", arrayOf(id))
        }
    }

    override suspend fun recent(limit: Int): List<MemoryRecord> = withContext(Dispatchers.IO) {
        // Working summaries are an internal scratchpad, not something to browse: hide them so the
        // panel shows only facts, instructions, and episodes.
        database.readableDatabase.query(
            "memory_records",
            null,
            "kind != ?",
            arrayOf(MemoryKind.WORKING_SUMMARY.name),
            null,
            null,
            "created_at DESC LIMIT $limit",
        ).use { cursor ->
            cursor.rows()
        }
    }

    override suspend fun mostImportant(limit: Int): List<MemoryRecord> = withContext(Dispatchers.IO) {
        database.readableDatabase.query(
            "memory_records",
            null,
            "kind != ?",
            arrayOf(MemoryKind.WORKING_SUMMARY.name),
            null,
            null,
            "importance DESC, created_at DESC LIMIT $limit",
        ).use { cursor ->
            cursor.rows()
        }
    }

    override suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        // The AFTER DELETE trigger on memory_records keeps the FTS index in sync.
        database.writableDatabase.delete("memory_records", "id = ?", arrayOf(id))
        database.writableDatabase.delete("memory_vectors", "memory_id = ?", arrayOf(id))
        Unit
    }

    private fun Cursor.rows(): List<MemoryRecord> {
        val out = mutableListOf<MemoryRecord>()
        while (moveToNext()) out += toMemoryRecord()
        return out
    }

    private fun Cursor.toMemoryRecord(): MemoryRecord = MemoryRecord(
        id = getString(getColumnIndexOrThrow("id")),
        kind = runCatching { MemoryKind.valueOf(getString(getColumnIndexOrThrow("kind"))) }
            .getOrDefault(MemoryKind.SEMANTIC_FACT),
        text = getString(getColumnIndexOrThrow("text")),
        importance = getDouble(getColumnIndexOrThrow("importance")),
        createdAtEpochMillis = getLong(getColumnIndexOrThrow("created_at")),
        sourceMessageIds = getString(getColumnIndexOrThrow("source_message_ids"))
            .split(",")
            .filter(String::isNotBlank)
            .map { MessageId(it) },
        metadata = mapOf("source" to getString(getColumnIndexOrThrow("source"))),
    )

    companion object {
        /**
         * Turns a natural-language query into an FTS4 OR-of-prefix terms expression. The prefix
         * wildcard is deliberate: "read the airport notes" should match "note" and "notes".
         */
        fun fuzzyMatchQuery(query: String): String = query
            .lowercase()
            .split(Regex("\\W+"))
            .filter { it.length > 2 }
            .distinct()
            .joinToString(" OR ") { "\"$it\"*" }

        private const val SEARCH_BY_CONVERSATION_SQL = """
            SELECT r.id, r.conversation_id, r.kind, r.text, r.importance, r.created_at,
                   r.source, r.source_message_ids
            FROM memory_fts f JOIN memory_records r ON r.rowid = f.docid
            WHERE memory_fts MATCH ? AND r.conversation_id = ?
            ORDER BY r.importance DESC, r.created_at DESC LIMIT ?
        """
        private const val SEARCH_ALL_SQL = """
            SELECT r.id, r.conversation_id, r.kind, r.text, r.importance, r.created_at,
                   r.source, r.source_message_ids
            FROM memory_fts f JOIN memory_records r ON r.rowid = f.docid
            WHERE memory_fts MATCH ?
            ORDER BY r.importance DESC, r.created_at DESC LIMIT ?
        """
    }
}

internal class MemoryDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE memory_records (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                text TEXT NOT NULL,
                importance REAL NOT NULL,
                created_at INTEGER NOT NULL,
                source TEXT NOT NULL DEFAULT '',
                source_message_ids TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE VIRTUAL TABLE memory_fts USING fts4(content=\"memory_records\", text)",
        )
        db.execSQL(
            "CREATE TRIGGER memory_fts_insert AFTER INSERT ON memory_records BEGIN " +
                "INSERT INTO memory_fts(docid, text) VALUES (new.rowid, new.text); END",
        )
        db.execSQL(
            "CREATE TRIGGER memory_fts_delete AFTER DELETE ON memory_records BEGIN " +
                "DELETE FROM memory_fts WHERE docid = old.rowid; END",
        )
        db.execSQL(
            "CREATE TRIGGER memory_fts_update AFTER UPDATE OF text ON memory_records BEGIN " +
                "UPDATE memory_fts SET text = new.text WHERE docid = old.rowid; END",
        )
        db.execSQL(
            """
            CREATE TABLE run_journal (
                id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                finished_at INTEGER,
                status TEXT NOT NULL,
                tool_turns INTEGER NOT NULL DEFAULT 0,
                input_tokens INTEGER,
                output_tokens INTEGER,
                error TEXT
            )
            """.trimIndent(),
        )
        // One embedding per memory (L2-normalized floats, little-endian). Joined to memory_records
        // by id for the cosine pass; kept out of the FTS/content table so keyword and semantic
        // retrieval are independent.
        db.execSQL(
            """
            CREATE TABLE memory_vectors (
                memory_id TEXT PRIMARY KEY,
                embedding BLOB NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL(
                """
                CREATE TABLE memory_vectors (
                    memory_id TEXT PRIMARY KEY,
                    embedding BLOB NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    private companion object {
        const val DB_NAME = "bram_memory.db"
        const val DB_VERSION = 2
    }
}
