package io.github.kurue.bram.core.domain

/**
 * Reorders active skills so the ones whose description matches the task come first, letting the
 * prompt's character budget trim the least relevant instead of the alphabetically last.
 *
 * Description embeddings are cached per (id, version): a skill keeps its vector across turns, and
 * a re-versioned skill re-embeds once. The cache is bounded, so a skill churned through many
 * versions cannot grow it without end.
 *
 * Best-effort by design: with no embedder (no embedding model designated) or if any embedding call
 * fails, the input order is returned unchanged, so skills still join the prompt — just unranked,
 * the way they did before ranking existed.
 */
class SkillSelection(private val cacheLimit: Int = DEFAULT_CACHE_LIMIT) {
    private val cache = LinkedHashMap<CacheKey, FloatArray>()

    suspend fun rank(
        skills: List<ActiveSkill>,
        query: String,
        embedder: Embedder?,
    ): List<ActiveSkill> {
        if (skills.size <= 1) return skills
        val queryVector = embedder?.embed(query) ?: return skills
        val scored = ArrayList<Pair<ActiveSkill, Float>>(skills.size)
        for (skill in skills) {
            // If even one description cannot be embedded, keep the input order rather than
            // presenting a partial ranking that silently drops the failure.
            val vector = embeddingFor(skill.id, skill.version, skill.description, embedder) ?: return skills
            scored += skill to VectorSearch.cosine(queryVector, vector)
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    /**
     * The drafted skill whose description best matches [query], when it clears [threshold]; else null.
     *
     * Surfaces an inactive (drafted, unreviewed) skill relevant to the task so the caller can nudge
     * the user toward activating it. A draft is never followed — only the *existence* of a match is
     * reported, never its instructions — because a draft is text the user has not approved.
     *
     * [threshold] is tuned conservatively: over-nagging about the user's own drafts trains them to
     * ignore the nudge, and the caller dedups per conversation so a match is suggested at most once.
     */
    suspend fun topDraft(
        packages: List<SkillPackage>,
        query: String,
        embedder: Embedder?,
        threshold: Float = DEFAULT_DRAFT_HINT_THRESHOLD,
    ): SkillPackage? {
        val candidates = packages.mapNotNull { pkg ->
            val draftVersion = pkg.draftVersion ?: return@mapNotNull null
            val draft = pkg.versions.firstOrNull { it.version == draftVersion } ?: return@mapNotNull null
            Triple(pkg, draftVersion, draft.description)
        }
        if (candidates.isEmpty()) return null
        val queryVector = embedder?.embed(query) ?: return null
        var best: Pair<SkillPackage, Float>? = null
        for ((pkg, version, description) in candidates) {
            // As with rank: a single embedding failure declines rather than half-ranking.
            val vector = embeddingFor(pkg.id, version, description, embedder) ?: return null
            val score = VectorSearch.cosine(queryVector, vector)
            if (best == null || score > best!!.second) best = pkg to score
        }
        return best?.takeIf { it.second >= threshold }?.first
    }

    /**
     * Drops active skills whose description is already covered by a tool the run is offering.
     *
     * Proven on device: a small model reliably abandons a purpose-built, pre-granted tool the
     * moment the prompt also names a skill that sounds like it — it chases the skill through
     * web_search, read_file on an invented path, or a hallucinated tool instead. Telling the
     * model "the tool wins" did not fix it; removing the duplicate advertisement does. A skill
     * that duplicates nothing keeps its advertisement, and a suppressed skill reappears the
     * moment its covering tool is not offered, so this is steering, not deletion.
     *
     * Best-effort like [rank]: no embedder or any failed embedding returns the skills unchanged.
     */
    suspend fun withoutCovered(
        skills: List<ActiveSkill>,
        offeredTools: List<ToolDefinition>,
        embedder: Embedder?,
        threshold: Float = DEFAULT_COVERAGE_THRESHOLD,
    ): List<ActiveSkill> {
        if (skills.isEmpty() || offeredTools.isEmpty()) return skills
        if (embedder == null) return skills
        return skills.filter { skill ->
            (toolCoverage(skill, offeredTools, embedder) ?: return skills) < threshold
        }
    }

    /**
     * The highest cosine between one skill's description and any offered tool's text, or null
     * when the comparison cannot run. Public so the caller can log the number — the threshold
     * is empirical, and field data is how it gets set honestly.
     *
     * Compares against both the bare description and the name-prefixed text: a tool's name
     * ("get_weather") carries meaning the skill text will not match, and steering prose in a
     * description dilutes it, so the bare form is the fairer of the two.
     */
    suspend fun toolCoverage(
        skill: ActiveSkill,
        offeredTools: List<ToolDefinition>,
        embedder: Embedder?,
    ): Float? {
        if (offeredTools.isEmpty() || embedder == null) return null
        val skillVector = embeddingFor(skill.id, skill.version, skill.description, embedder) ?: return null
        var best = -1f
        for (tool in offeredTools) {
            val named = embeddingFor("tool:${tool.name}", "", "${tool.name}: ${tool.description}", embedder) ?: return null
            val bare = embeddingFor("tool:${tool.name}:bare", "", tool.description, embedder) ?: return null
            best = maxOf(best, VectorSearch.cosine(skillVector, named), VectorSearch.cosine(skillVector, bare))
        }
        return best
    }

    private suspend fun embeddingFor(
        id: String,
        version: String,
        text: String,
        embedder: Embedder,
    ): FloatArray? {
        val key = CacheKey(id, version)
        synchronized(cache) {
            val cached = cache[key]
            if (cached != null) {
                touch(key, cached)
                return cached
            }
        }
        val vector = runCatching { embedder.embed(text) }.getOrNull() ?: return null
        synchronized(cache) {
            cache[key] = vector
            // Evict the oldest entry (the description most likely to have been re-versioned away)
            // rather than letting history grow the cache without end.
            while (cache.size > cacheLimit) {
                val firstKey = cache.keys.iterator().next()
                if (firstKey == key) break
                cache.remove(firstKey)
            }
        }
        return vector
    }

    /** Reinserts a cached entry so the access-order map keeps the freshest descriptions. */
    private fun touch(key: CacheKey, value: FloatArray) {
        cache.remove(key)
        cache[key] = value
    }

    private data class CacheKey(val id: String, val version: String)

    private companion object {
        const val DEFAULT_CACHE_LIMIT = 64

        // A draft has to be clearly about the task to nudge: cosine for normalized embedders sits
        // around 0 for unrelated text and climbs past 0.5 only for genuinely related descriptions,
        // so 0.60 favors precision over recall and the caller's per-conversation dedup bounds the
        // rare loose match.
        const val DEFAULT_DRAFT_HINT_THRESHOLD = 0.60f

        // Empirical: with bge-small-en-v1.5 the weather-fetcher stub ("Automatically fetches
        // current weather data for a specified location") scores 0.73 against get_weather's
        // description — a true duplicate pair. Unrelated skills (git-helper vs weather) sit near
        // 0.4. The bar lands just under the measured duplicate; when a covering tool is offered,
        // suppressing a same-topic skill is the safe direction — the skill survives in
        // list_skills and read_skill, only its passive advertisement waits.
        const val DEFAULT_COVERAGE_THRESHOLD = 0.70f
    }
}
