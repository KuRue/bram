package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.MemoryStore
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import org.json.JSONArray
import org.json.JSONObject

/**
 * The agent's own recall: searches every conversation's stored memory — the working summaries
 * compaction produces, plus anything else recorded — so a task can draw on what earlier
 * conversations learned. Read-only, no permissions: memory is Bram's own, not a platform surface.
 */
class MemorySearchTool(
    private val memoryStore: MemoryStore,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "memory_search",
        description = "Search Bram's persistent memory across all conversations for facts, " +
            "decisions, and summaries from earlier work.",
        inputSchemaJson = """{"type":"object","properties":{"query":{"type":"string","description":"What to look for in plain words"},"limit":{"type":"integer","description":"How many matches to return (default 5)"}},"required":["query"],"additionalProperties":false}""",
        readOnly = true,
        approvalScopeKeys = listOf(),
    )

    override suspend fun execute(argumentsJson: String): String {
        val args = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return "{\"error\":{\"code\":\"bad_arguments\",\"message\":\"Arguments were not valid JSON\"}}"
        val query = args.optString("query").trim()
        if (query.isEmpty()) {
            return "{\"error\":{\"code\":\"bad_arguments\",\"message\":\"query must not be empty\"}}"
        }
        val limit = args.optInt("limit", 5).coerceIn(1, 20)
        val matches = memoryStore.searchAll(query, limit)
        val array = JSONArray()
        matches.forEach { memory ->
            array.put(
                JSONObject()
                    .put("kind", memory.kind.name.lowercase())
                    .put("text", memory.text)
                    .put("importance", memory.importance)
                    .put("source", memory.metadata["source"].orEmpty()),
            )
        }
        return JSONObject()
            .put("matches", array)
            .put("note", if (matches.isEmpty()) {
                "No memory matched. Say so plainly rather than inventing facts."
            } else {
                "Matches are ranked by importance and text similarity. Quote facts as found, and say when a match is partial."
            })
            .toString()
    }
}
