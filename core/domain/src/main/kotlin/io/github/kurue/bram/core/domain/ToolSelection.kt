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
    /**
     * A fixed character budget, overriding the context-proportional one; tests pin this to make
     * the arithmetic visible.
     */
    private val budgetChars: Int? = null,
    /** Below this cosine a non-core tool is left out however much budget remains. */
    private val minSimilarity: Float = DEFAULT_MIN_SIMILARITY,
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

        val budget = budgetChars ?: budgetFor(contextWindowTokens)
        val selected = core.toMutableList()
        var spent = core.sumOf { it.costChars() }
        for ((tool, score) in scored.sortedByDescending { it.second }) {
            if (score < minSimilarity) continue
            val cost = tool.costChars()
            if (spent + cost > budget) continue
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
         * skill-improvement loop needs. read_skill is core because the prompt names it as the way
         * to load a skill — offering that instruction without the tool would strand the model.
         */
        val DEFAULT_CORE_TOOLS: Set<String> = setOf(
            "write_note",
            "memory_search",
            "device_status",
            "propose_skill",
            "get_weather",
            "read_skill",
            "tool_search",
        )

        /**
         * How much tool text a context may spend, in characters.
         *
         * A fifth of the window in approximate characters: a 4K-context model is offered a couple
         * of dozen lines of tool prose, not a fixed 6,000 characters that would be a third of its
         * whole budget. Bigger contexts get more, up to the ceiling where the core set plus a few
         * relevant tools is all a run ever needs.
         */
        fun budgetFor(contextWindowTokens: Int): Int =
            (contextWindowTokens * CHARS_PER_TOKEN / 5).coerceIn(MIN_BUDGET_CHARS, MAX_BUDGET_CHARS)

        private const val CHARS_PER_TOKEN = 3

        /** Enough for the core set on the smallest supported context. */
        private const val MIN_BUDGET_CHARS = 1_500

        /** ~2,000 tokens of tool text; past this the list is longer than it is useful. */
        private const val MAX_BUDGET_CHARS = 6_000

        /**
         * The relevance bar for a non-core tool. Below it the tool is not obviously about the ask,
         * and a long tail of barely-related tools measurably degrades choice; the core set carries
         * the always-useful capabilities instead.
         */
        private const val DEFAULT_MIN_SIMILARITY = 0.35f

        /** Beyond this, every definition fits comfortably and triage is skipped. */
        const val LARGE_CONTEXT_TOKENS = 32_000

        private const val DEFAULT_CACHE_LIMIT = 128
    }
}
