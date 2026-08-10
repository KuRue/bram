package io.github.kurue.bram.core.domain

import java.security.MessageDigest
import org.json.JSONArray

/**
 * One durable memory pulled out of a completed exchange, before it has the bookkeeping a stored
 * [MemoryRecord] carries (id, provenance). The [GeneratingMemoryExtractor] returns these; the
 * orchestrator turns each into a record via [toRecord] and persists it.
 */
data class ExtractedMemory(
    val kind: MemoryKind,
    val text: String,
    val importance: Double,
)

/**
 * Inspects one completed exchange and returns whatever durable memories should outlive it.
 *
 * Called by the orchestrator after a turn's reply is produced (the visible text has already
 * streamed) but before it emits [AgentEvent.Completed], so a record is in place for the next turn
 * without delaying the reply past its last token. Implementations must be best-effort: any failure
 * to extract should yield an empty list, never raise — extraction is an enhancement to recall, not
 * a step a run depends on finishing.
 */
interface MemoryExtractor {
    suspend fun extract(
        conversationId: ConversationId,
        userMessage: ConversationMessage?,
        assistantReply: String,
        runtime: ModelRuntime,
    ): List<ExtractedMemory>
}

/** The no-op default: extracts nothing, so a run with no extractor wired behaves exactly as before. */
object NoopMemoryExtractor : MemoryExtractor {
    override suspend fun extract(
        conversationId: ConversationId,
        userMessage: ConversationMessage?,
        assistantReply: String,
        runtime: ModelRuntime,
    ): List<ExtractedMemory> = emptyList()
}

/**
 * The importance a record inherits when the model did not name one. Instructions rank above facts:
 * a standing directive about how to respond applies to every future turn, while a fact is recalled
 * only when it is relevant, so the two sit at different floors in [ContextWindowManager]'s
 * importance-ordered injection.
 */
internal fun defaultImportance(kind: MemoryKind): Double = when (kind) {
    MemoryKind.USER_INSTRUCTION -> 0.8
    MemoryKind.SEMANTIC_FACT -> 0.6
    MemoryKind.WORKING_SUMMARY -> 1.0
    MemoryKind.EPISODE -> 0.5
}

/**
 * Builds the [MemoryRecord] for an extracted memory, with a content-derived id: the same fact
 * extracted again produces the same id, and [PersistentMemoryStore.put] upserts on id, so
 * re-extraction replaces instead of duplicating. Phrasing differences still create distinct ids —
 * this is gross-dedup, not fuzzy dedup.
 */
fun ExtractedMemory.toRecord(sourceMessageId: MessageId?): MemoryRecord {
    val normalized = text.lowercase().trim()
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
    val hash = digest.joinToString("") { "%02x".format(it) }.take(16)
    val importance = importance.coerceIn(0.0, 1.0)
    return MemoryRecord(
        id = "memory-${kind.name.lowercase()}-$hash",
        kind = kind,
        text = text,
        importance = importance,
        sourceMessageIds = listOfNotNull(sourceMessageId),
        metadata = mapOf("source" to "extraction"),
    )
}

/**
 * Parses the model's extraction output into memories.
 *
 * The output is expected to be a JSON array of `{"kind": "fact"|"instruction", "text": "...",
 * "importance"?: number}`. Real models wrap the array in prose or a code fence, emit unknown kind
 * strings, leave fields blank, or return malformed JSON; the parser is defensive on all of it and
 * never throws — one bad row is skipped, not the whole batch. Returns at most [MAX_ITEMS].
 */
fun parseExtractedMemories(raw: String): List<ExtractedMemory> {
    val arrayBody = extractJsonArray(raw) ?: return emptyList()
    val array = runCatching { JSONArray(arrayBody) }.getOrNull() ?: return emptyList()
    val out = mutableListOf<ExtractedMemory>()
    for (index in 0 until array.length()) {
        if (out.size >= MAX_ITEMS) break
        val element = array.optJSONObject(index) ?: continue
        val kind = parseKind(element.optString("kind")) ?: continue
        val text = element.optString("text").trim()
        if (text.length < MIN_TEXT_CHARS || text.length > MAX_TEXT_CHARS) continue
        val importance = element.optDouble("importance", defaultImportance(kind))
        out += ExtractedMemory(kind = kind, text = text, importance = importance)
    }
    return out
}

/**
 * Finds the JSON payload in [raw], tolerating a ``` fence and surrounding prose. Small models
 * often emit a single object instead of the requested array, so a leading `{…}` is accepted and
 * wrapped as a one-element array rather than discarded.
 */
private fun extractJsonArray(raw: String): String? {
    val arrStart = raw.indexOf('[')
    val arrEnd = raw.lastIndexOf(']')
    if (arrStart >= 0 && arrEnd > arrStart) return raw.substring(arrStart, arrEnd + 1)
    val objStart = raw.indexOf('{')
    val objEnd = raw.lastIndexOf('}')
    if (objStart >= 0 && objEnd > objStart) return "[" + raw.substring(objStart, objEnd + 1) + "]"
    return null
}

private fun parseKind(value: String): MemoryKind? = when (value.trim().lowercase()) {
    "fact", "semantic_fact", "semanticfact" -> MemoryKind.SEMANTIC_FACT
    "instruction", "user_instruction", "userinstruction", "preference", "directive"
    -> MemoryKind.USER_INSTRUCTION
    else -> null
}

private const val MIN_TEXT_CHARS = 3
internal const val MAX_TEXT_CHARS = 500
internal const val MAX_ITEMS = 8
