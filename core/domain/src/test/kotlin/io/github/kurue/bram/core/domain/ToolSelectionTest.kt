package io.github.kurue.bram.core.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSelectionTest {

    private fun tool(
        name: String,
        description: String = "$name tool",
        schemaChars: Int = 100,
    ) = ToolDefinition(
        name = name,
        description = description,
        inputSchemaJson = "x".repeat(schemaChars),
    )

    /** Maps exact text to a vector so tests can place tools near or far from the query. */
    private class FakeEmbedder(private val vectors: Map<String, FloatArray>) : Embedder {
        var calls = 0
        override suspend fun embed(text: String): FloatArray? {
            calls++
            return vectors[text]
        }
    }

    @Test
    fun `core tools are always offered and never need an embedding`() = runBlocking {
        val query = floatArrayOf(1f, 0f)
        val embedder = FakeEmbedder(
            mapOf(
                "go online" to query,
                "web_search: online" to floatArrayOf(1f, 0f),
                // No vector for the core tool's text: it is not embedded at all.
            ),
        )
        val selector = RankingToolSelector(embedder, coreToolNames = setOf("write_note"), budgetChars = 10_000)
        val available = listOf(
            tool("write_note"),
            tool("web_search", description = "online"),
        )
        assertEquals(
            listOf("write_note", "web_search"),
            selector.select("go online", 4_096, available).map { it.name },
        )
    }

    @Test
    fun `relevant tools are admitted first and the budget trims the rest`() = runBlocking {
        val query = floatArrayOf(0f, 1f)
        val embedder = FakeEmbedder(
            mapOf(
                "what is the weather tomorrow" to query,
                "get_weather: weather" to query, // identical direction: cosine 1.0
                "termux_exec: shell" to floatArrayOf(1f, 0f), // orthogonal: cosine 0.0
                "web_search: search" to floatArrayOf(1f, 0f), // orthogonal: cosine 0.0
            ),
        )
        val selector = RankingToolSelector(embedder, coreToolNames = emptySet(), budgetChars = 200)
        val available = listOf(
            tool("termux_exec", description = "shell", schemaChars = 100),
            tool("get_weather", description = "weather", schemaChars = 100),
            tool("web_search", description = "search", schemaChars = 100),
        )
        // get_weather costs 120 chars, so the 200-char budget leaves no room for the other two.
        assertEquals(listOf("get_weather"), selector.select("what is the weather tomorrow", 4_096, available).map { it.name })
    }

    @Test
    fun `several tools fit when the budget allows, best first`() = runBlocking {
        val query = floatArrayOf(1f, 0f)
        val near = floatArrayOf(1f, 0f)
        val mid = floatArrayOf(0.9f, 0.1f).also {
            val norm = kotlin.math.sqrt(it[0] * it[0] + it[1] * it[1]).toFloat()
            it[0] /= norm; it[1] /= norm
        }
        val embedder = FakeEmbedder(
            mapOf(
                "fix my code" to query,
                "a: near" to near,
                "b: mid" to mid,
                "c: orthogonal" to floatArrayOf(0f, 1f),
            ),
        )
        val selector = RankingToolSelector(embedder, coreToolNames = emptySet(), budgetChars = 10_000)
        val available = listOf(
            tool("c", description = "orthogonal"),
            tool("b", description = "mid"),
            tool("a", description = "near"),
        )
        assertEquals(listOf("a", "b", "c"), selector.select("fix my code", 4_096, available).map { it.name })
    }

    @Test
    fun `a single embedding failure declines to the full list`() = runBlocking {
        val embedder = FakeEmbedder(
            mapOf(
                "query" to floatArrayOf(1f, 0f),
                "a: fine" to floatArrayOf(1f, 0f),
                // "b: broken" deliberately absent -> null
            ),
        )
        val selector = RankingToolSelector(embedder, coreToolNames = emptySet())
        val available = listOf(tool("a", description = "fine"), tool("b", description = "broken"))
        assertEquals(available, selector.select("query", 4_096, available))
    }

    @Test
    fun `a large context skips the embedder entirely`() = runBlocking {
        val embedder = FakeEmbedder(emptyMap())
        val selector = RankingToolSelector(embedder)
        val available = listOf(tool("a"), tool("b"))
        assertEquals(available, selector.select("query", 128_000, available))
        assertEquals(0, embedder.calls)
    }

    @Test
    fun `core tools count against the budget first`() = runBlocking {
        val query = floatArrayOf(1f, 0f)
        val embedder = FakeEmbedder(
            mapOf(
                "search the web" to query,
                "web_search: search the web" to floatArrayOf(1f, 0f),
                "files: storage" to floatArrayOf(0f, 1f),
            ),
        )
        val selector = RankingToolSelector(
            embedder = embedder,
            coreToolNames = setOf("write_note"),
            // write_note and web_search each cost 114 chars: exactly the budget, nothing more.
            budgetChars = ("write_note tool".length + 100) + ("web_search: search the web".length + 100),
        )
        val available = listOf(
            tool("write_note"),
            tool("files", description = "storage"),
            tool("web_search", description = "search the web"),
        )
        assertEquals(listOf("write_note", "web_search"), selector.select("search the web", 4_096, available).map { it.name })
    }

    @Test
    fun `tool embeddings are cached across selections`() = runBlocking {
        val query = floatArrayOf(1f, 0f)
        val embedder = FakeEmbedder(
            mapOf(
                "one query" to query,
                "another query" to floatArrayOf(0f, 1f),
                "a: same" to floatArrayOf(1f, 0f),
            ),
        )
        val selector = RankingToolSelector(embedder, coreToolNames = emptySet())
        val available = listOf(tool("a", description = "same"))
        selector.select("one query", 4_096, available)
        val callsAfterFirst = embedder.calls
        selector.select("another query", 4_096, available)
        // Only the second query was embedded again; the tool's vector came from the cache.
        assertEquals(callsAfterFirst + 1, embedder.calls)
    }
}
