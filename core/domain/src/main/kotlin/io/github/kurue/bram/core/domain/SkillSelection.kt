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
            val vector = embeddingFor(skill, embedder) ?: return skills
            scored += skill to VectorSearch.cosine(queryVector, vector)
        }
        return scored.sortedByDescending { it.second }.map { it.first }
    }

    private suspend fun embeddingFor(skill: ActiveSkill, embedder: Embedder): FloatArray? {
        val key = CacheKey(skill.id, skill.version)
        synchronized(cache) {
            val cached = cache[key]
            if (cached != null) {
                touch(key, cached)
                return cached
            }
        }
        val vector = runCatching { embedder.embed(skill.description) }.getOrNull() ?: return null
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
    }
}
