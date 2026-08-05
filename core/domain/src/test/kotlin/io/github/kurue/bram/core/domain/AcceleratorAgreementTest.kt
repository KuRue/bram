package io.github.kurue.bram.core.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceleratorAgreementTest {
    @Test
    fun `identical sequences score perfectly`() {
        val tokens = listOf(597, 4695, 10966, 267)
        assertEquals(1.0, AcceleratorAgreement.score(tokens, tokens), 1e-9)
    }

    @Test
    fun `a single near-tie difference still counts as usable`() {
        // The measured Hexagon NPU result: 23 of 24 positions agreed.
        val reference = (1..24).toList()
        val predicted = reference.toMutableList().apply { this[18] = 99_999 }
        val score = AcceleratorAgreement.score(reference, predicted)

        assertEquals(23.0 / 24.0, score, 1e-9)
        assertTrue(AcceleratorAgreement.isUsable(score))
    }

    @Test
    fun `a broken backend falls below the usable threshold`() {
        // The measured Vulkan result at one offloaded layer: 18 of 24 positions agreed.
        val reference = (1..24).toList()
        val predicted = reference.toMutableList()
        listOf(0, 2, 11, 13, 18, 20).forEach { position -> predicted[position] = -position }

        val score = AcceleratorAgreement.score(reference, predicted)
        assertEquals(18.0 / 24.0, score, 1e-9)
        assertFalse(AcceleratorAgreement.isUsable(score))
    }

    @Test
    fun `a collapsed backend scores near zero`() {
        // Zero logits after greedy sampling look like one repeated token id.
        val reference = listOf(597, 4695, 10966, 267, 1815)
        val collapsed = listOf(47186, 0, 0, 0, 0)

        assertEquals(0.0, AcceleratorAgreement.score(reference, collapsed), 1e-9)
        assertFalse(AcceleratorAgreement.isUsable(0.0))
    }

    @Test
    fun `a truncated prediction is only scored over what it produced`() {
        val reference = listOf(1, 2, 3, 4, 5, 6, 7, 8)
        val predicted = listOf(1, 2, 3, 4)

        // Four of four overlapping positions agree; the missing tail is not credited as agreement,
        // but neither is it counted as disagreement.
        assertEquals(1.0, AcceleratorAgreement.score(reference, predicted), 1e-9)
    }

    @Test
    fun `empty predictions score zero rather than dividing by zero`() {
        assertEquals(0.0, AcceleratorAgreement.score(listOf(1, 2, 3), emptyList()), 1e-9)
        assertEquals(0.0, AcceleratorAgreement.score(emptyList(), emptyList()), 1e-9)
    }

    @Test
    fun `a fully working accelerator reports no boundary`() = runBlocking {
        val probed = mutableListOf<Int>()
        val boundary = findOffloadBoundary(31) { layers ->
            probed += layers
            true
        }

        assertEquals(OffloadBoundary(31, null), boundary)
        // Probing the top is enough to rule out a boundary.
        assertEquals(listOf(31), probed)
    }

    @Test
    fun `a threshold failure is narrowed to adjacent layer counts`() = runBlocking {
        val boundary = findOffloadBoundary(31) { layers -> layers < 7 }

        assertEquals(6, boundary.lastGoodLayers)
        assertEquals(7, boundary.firstBadLayers)
    }

    @Test
    fun `a backend broken at every layer reports a zero boundary`() = runBlocking {
        // The measured Vulkan case: even one offloaded layer disagrees, which implicates an
        // operation shared by every layer rather than one specific layer.
        val boundary = findOffloadBoundary(31) { false }

        assertEquals(0, boundary.lastGoodLayers)
        assertEquals(1, boundary.firstBadLayers)
    }

    @Test
    fun `the search reloads the model a logarithmic number of times`() = runBlocking {
        val probed = mutableListOf<Int>()
        findOffloadBoundary(31) { layers ->
            probed += layers
            layers < 7
        }

        // Each probe is a full model reload, so the count matters, not just the answer.
        assertTrue("expected at most 6 probes, got ${probed.size}: $probed", probed.size <= 6)
    }

    @Test
    fun `a model with no layers is not probed at all`() = runBlocking {
        var probes = 0
        val boundary = findOffloadBoundary(0) { probes++; true }

        assertEquals(OffloadBoundary(0, null), boundary)
        assertEquals(0, probes)
    }
}
