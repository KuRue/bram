package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorSearchTest {

    @Test
    fun cosineIsOneForIdenticalVectors() {
        val v = floatArrayOf(1f, 2f, 3f)
        assertEquals(1f, VectorSearch.cosine(v, v), 1e-5f)
    }

    @Test
    fun cosineIsZeroForOrthogonalVectors() {
        assertEquals(0f, VectorSearch.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-5f)
    }

    @Test
    fun cosineIsMinusOneForOppositeVectors() {
        assertEquals(-1f, VectorSearch.cosine(floatArrayOf(1f, 1f), floatArrayOf(-1f, -1f)), 1e-5f)
    }

    @Test
    fun cosineHandlesMismatchedAndEmptyVectors() {
        assertEquals(0f, VectorSearch.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f)), 0f)
        assertEquals(0f, VectorSearch.cosine(FloatArray(0), FloatArray(0)), 0f)
    }

    @Test
    fun cosineIsCorrectForUnnormalizedInputs() {
        // cosine((3,4),(3,4)) = 1 regardless of magnitude; (3,4) and (6,8) point the same way.
        assertEquals(1f, VectorSearch.cosine(floatArrayOf(3f, 4f), floatArrayOf(6f, 8f)), 1e-5f)
    }

    @Test
    fun fuseRanksAnItemHighInBothListsAboveOneHighInOnlyOne() {
        val fts = listOf("a", "b", "c")
        val vec = listOf("a", "c", "b")
        val fused = VectorSearch.fuseRanked(listOf(fts, vec))
        assertEquals("a", fused.first())
        // "a" appears at rank 0 in both and so must beat "b" and "c", which each trade a rank.
        assertTrue(fused.indexOf("a") < fused.indexOf("b"))
        assertTrue(fused.indexOf("a") < fused.indexOf("c"))
    }

    @Test
    fun fuseDeduplicatesItems() {
        val fused = VectorSearch.fuseRanked(listOf(listOf("a", "b"), listOf("b", "a")))
        assertEquals(listOf("a", "b"), fused)
    }

    @Test
    fun fuseReturnsTheUnionInScoreOrder() {
        val fused = VectorSearch.fuseRanked(listOf(listOf("only-fts"), listOf("only-vec")))
        assertEquals(2, fused.size)
        // Both contribute identical single-list scores, so order between them is by insertion.
        assertTrue(fused.contains("only-fts"))
        assertTrue(fused.contains("only-vec"))
    }

    @Test
    fun fuseHandlesEmptyRankings() {
        assertTrue(VectorSearch.fuseRanked<String>(emptyList()).isEmpty())
        val withOneEmpty: List<List<String>> = listOf(emptyList(), listOf("a"))
        assertEquals(listOf("a"), VectorSearch.fuseRanked(withOneEmpty))
    }
}
