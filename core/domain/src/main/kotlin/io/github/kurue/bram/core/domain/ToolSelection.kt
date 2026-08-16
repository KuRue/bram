package io.github.kurue.bram.core.domain

/**
 * Picks which of the registry's tools a run is actually offered.
 *
 * Shipping every tool on every run costs a model ~two thousand tokens of prose before the
 * conversation starts — a tax a small local model pays in attention and a streamed giant pays in
 * prefill time — and a long list of overlapping tools measurably degrades tool choice. The
 * selector keeps a small core of always-cheap, always-useful tools and admits the rest by
 * relevance to the turn's ask, until a character budget is spent.
 *
 * The same rules as skill ranking: best-effort, never a new failure mode. With no embedder
 * available the full list is returned unchanged (today's behavior), and a single embedding
 * failure declines the whole ranking rather than half-ranking.
 *
 * Description embeddings are cached per tool name. Built-in tools are static, so the cache is
 * effectively permanent for them; an MCP server that swaps a tool's schema under the same name
 * re-ranks on the description change's next embed.
 */
fun interface ToolSelector {
    suspend fun select(query: String, contextWindowTokens: Int, available: List<ToolDefinition>): List<ToolDefinition>
}

/** The identity selector: everything, in registry order. The default when no embedder is wired. */
object AllToolsSelector : ToolSelector {
    override suspend fun select(
        query: String,
        contextWindowTokens: Int,
        available: List<ToolDefinition>,
    ): List<ToolDefinition> = available
}

class RankingToolSelector(
    private val embedder: Embedder,
    /** Tools offered on every run regardless of the ask, in registry order. */
    private val coreToolNames: Set<String> = DEFAULT_CORE_TOOLS,
    /** Characters of description + schema the whole selection may cost on a small-context model. */
    private val budgetChars: Int = DEFAULT_BUDGET_CHARS,
    private val cacheLimit: Int = DEFAULT_CACHE_LIMIT,
) : ToolSelector {

    override suspend fun select(
        query: String,
        contextWindowTokens: Int,
        available: List<ToolDefinition>,
    ): List<ToolDefinition> {
        // A context that comfortably holds every definition needs no triage; skip the embedder
        // entirely so a big-context run does not pay for embeddings it will not use.
        if (contextWindowTokens >= LARGE_CONTEXT_TOKENS) return available
        val queryVector = embedder.embed(query) ?: return available

        val core = available.filter { it.name in coreToolNames }
        val rest = available.filter { it.name !in coreToolNames }
        if (rest.isEmpty()) return core.ifEmpty { available }

        val scored = ArrayList<Pair<ToolDefinition, Float>>(rest.size)
        for (tool in rest) {
            val vector = embeddingFor(tool.name, "${tool.name}: ${tool.description}", embedder) ?: return available
            scored += tool to VectorSearch.cosine(queryVector, vector)
        }

        val selected = core.toMutableList()
        var spent = core.sumOf { it.costChars() }
        for ((tool, _) in scored.sortedByDescending { it.second }) {
            val cost = tool.costChars()
            if (spent + cost > budgetChars) continue
            selected += tool
            spent += cost
        }
        return selected
    }

    private fun ToolDefinition.costChars(): Int = description.length + inputSchemaJson.length

    private suspend fun embeddingFor(
        name: String,
        text: String,
        embedder: Embedder,
    ): FloatArray? {
        synchronized(cache) {
            cache[name]?.let {
                cache.remove(name)
                cache[name] = it
                return it
            }
        }
        val vector = runCatching { embedder.embed(text) }.getOrNull() ?: return null
        synchronized(cache) {
            cache[name] = vector
            while (cache.size > cacheLimit) {
                val oldest = cache.keys.iterator().next()
                if (oldest == name) break
                cache.remove(oldest)
            }
        }
        return vector
    }

    private val cache = LinkedHashMap<String, FloatArray>()

    companion object {
        /**
         * The always-on set: small descriptions, useful in almost any turn, and the pieces the
         * skill-improvement loop needs. Everything else waits for relevance.
         */
        val DEFAULT_CORE_TOOLS: Set<String> = setOf(
            "write_note",
            "memory_search",
            "device_status",
            "propose_skill",
            "get_weather",
        )

        /**
         * ~1,500 tokens of tool text: enough for the core set plus the few tools the ask actually
         * needs, small enough that a 4K-context local model still has room for the conversation.
         */
        const val DEFAULT_BUDGET_CHARS = 6_000

        /** Beyond this, every definition fits comfortably and triage is skipped. */
        const val LARGE_CONTEXT_TOKENS = 32_000

        private const val DEFAULT_CACHE_LIMIT = 128
    }
}
