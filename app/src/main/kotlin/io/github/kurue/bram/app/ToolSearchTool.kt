package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import io.github.kurue.bram.core.domain.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds a tool by what it does.
 *
 * Selection offers the core set plus the tools an ask seems to need, so a registry with hundreds of
 * MCP tools will always contain some the model was not offered and cannot see. This meta-tool closes
 * that gap: it reports matching names, descriptions, and argument schemas, and the model can call a
 * discovered tool by name — execution looks tools up in the registry, not in what was offered, so
 * discovery needs no second selection pass.
 *
 * Ranking is keyword overlap rather than embeddings: the result is a short list of exact matches,
 * the whole registry may be MCP tools whose descriptions do not embed well, and a deterministic
 * score keeps this tool free of the embedder's availability.
 */
class ToolSearchTool(
    /** Resolved per call: the registry is itself being built when this handler is constructed. */
    private val registry: () -> ToolRegistry,
    private val limit: Int = DEFAULT_LIMIT,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "tool_search",
        description = "Find tools by what they do when the one you need is not offered; returns names, " +
            "descriptions, and schemas.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "query":{"type":"string","description":"What you need to do, in a few words."}},
             "required":["query"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = true,
    )

    override suspend fun execute(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return toolError("invalid_arguments", "Arguments were not valid JSON")
        val query = arguments.optString("query").trim()
        if (query.isEmpty()) return toolError("invalid_query", "A search query is required")

        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        val matches = registry().definitions()
            .asSequence()
            .filter { it.name != definition.name }
            .map { tool -> tool to score(tool, terms) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .take(limit)
            .toList()

        return JSONObject()
            .put(
                "tools",
                JSONArray().apply {
                    matches.forEach { (tool, _) ->
                        put(
                            JSONObject()
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("schema", tool.inputSchemaJson),
                        )
                    }
                },
            )
            .put("count", matches.size)
            .put(
                "note",
                if (matches.isEmpty()) {
                    "No tool matched those words. Try the operation you want (fetch, search, write, run)."
                } else {
                    "Call a tool by its name; its arguments must satisfy the schema shown."
                },
            )
            .toString()
    }

    private fun score(tool: ToolDefinition, terms: Set<String>): Int {
        val name = tool.name.lowercase()
        val description = tool.description.lowercase()
        return terms.sumOf { term ->
            when {
                term in name -> 3
                term in description -> 1
                else -> 0
            }
        }
    }

    private companion object {
        const val DEFAULT_LIMIT = 8
    }
}
