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

        // Calibrated against bge-small-en-v1.5 (the designated embedder) with the pairs in
        // tools/threshold-calibration: true nudges scored 0.589-0.878 (median 0.874), unrelated
        // pairs 0.386-0.519. 0.55 clears the noisiest unrelated pair by 0.03 and catches the
        // weakest true match the earlier 0.60 guess would have missed.
        const val DEFAULT_DRAFT_HINT_THRESHOLD = 0.55f

        // Same measurement, skill description against the best of an offered tool's named/bare
        // text: genuine duplicates scored 0.710-0.895, distinct skills 0.516-0.657. 0.68 sits in
        // that gap: 0.02 above the highest distinct pair, 0.03 below the lowest duplicate.
        const val DEFAULT_COVERAGE_THRESHOLD = 0.68f
    }
}

/**
 * A tool selector that also offers what the active skills declare they need.
 *
 * Ranking drops tools an ask does not seem to need, but a skill is a procedure the user approved
 * and its front matter names the tools it is written against. Following a skill whose steps call
 * tools the model cannot see would strand it mid-procedure, so those tools are added back while
 * the skill is active. Declaring a tool only offers it: the approval gate still decides each call.
 */
class SkillAwareToolSelector(
    private val delegate: ToolSelector,
    private val skillStore: SkillStore,
) : ToolSelector {

    override suspend fun select(
        query: String,
        contextWindowTokens: Int,
        available: List<ToolDefinition>,
    ): List<ToolDefinition> {
        val selected = delegate.select(query, contextWindowTokens, available)
        val declared = runCatching { skillStore.activeSkills() }.getOrDefault(emptyList())
            .flatMapTo(mutableSetOf()) { it.tools }
        if (declared.isEmpty()) return selected
        val selectedNames = selected.mapTo(mutableSetOf()) { it.name }
        val missing = available.filter { it.name in declared && it.name !in selectedNames }
        return if (missing.isEmpty()) selected else selected + missing
    }
}
