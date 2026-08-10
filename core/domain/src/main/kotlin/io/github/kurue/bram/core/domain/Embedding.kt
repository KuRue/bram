package io.github.kurue.bram.core.domain

/**
 * Computes an embedding vector for a piece of text, so memories can be ranked by meaning rather
 * than by shared words. Returns null when no embedder is available, in which case the memory store
 * falls back to keyword (FTS) recall alone.
 *
 * Implementations should return L2-normalized vectors; [VectorSearch.cosine] handles either case
 * but normalization keeps the score range comparable across queries and models.
 */
interface Embedder {
    suspend fun embed(text: String): FloatArray?
}

/**
 * Pure ranking helpers for the embedding recall path, kept free of Android so the ranking can be
 * unit-tested without a device.
 */
object VectorSearch {
    /**
     * Cosine similarity between two vectors. Returns 0 for mismatched or empty vectors. Computes
     * the full cosine (not just a dot product) so it is correct whether or not the inputs are
     * L2-normalized.
     */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            normA += a[i].toDouble() * a[i].toDouble()
            normB += b[i].toDouble() * b[i].toDouble()
        }
        val denom = Math.sqrt(normA) * Math.sqrt(normB)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }

    /**
     * Reciprocal-rank fusion: each item gets `1 / (k + rank + 1)` from every list that ranked it,
     * summed. This is the standard way to blend retrieval signals whose raw scores are not
     * comparable — an FTS relevance score and a cosine similarity — without tuning weights.
     *
     * [k] dampens the very top of each list so a single high rank cannot dominate. 60 is the value
     * the IR literature uses; it is not sensitive to the exact count here.
     */
    fun <T> fuseRanked(rankings: List<List<T>>, k: Int = 60): List<T> {
        if (rankings.isEmpty()) return emptyList()
        val scores = LinkedHashMap<T, Double>()
        for (ranking in rankings) {
            ranking.forEachIndexed { rank, item ->
                scores.merge(item, 1.0 / (k + rank + 1)) { a, b -> a + b }
            }
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key }
    }
}
