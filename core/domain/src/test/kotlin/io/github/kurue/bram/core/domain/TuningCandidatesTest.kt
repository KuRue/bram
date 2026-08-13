package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TuningCandidatesTest {

    @Test
    fun `thread candidates are distinct, in range, and keep the current choice`() {
        // 8 cores, tuned to 6 already: current first, then all, then half. The all-minus-two
        // candidate collapses onto the current choice and is dropped.
        val candidates = TuningCandidates.threadCandidates(cores = 8, current = 6)
        assertEquals(listOf(6, 8, 4), candidates)
        assert(candidates.all { it in 1..8 })
    }

    @Test
    fun `thread candidates never go below one or duplicate the defaults`() {
        assertEquals(listOf(2, 1), TuningCandidates.threadCandidates(cores = 2, current = 0))
        val single = TuningCandidates.threadCandidates(cores = 1, current = 0)
        assertEquals(listOf(1), single)
    }

    @Test
    fun `mask for cores maps bit positions to hex`() {
        assertEquals("3f", TuningCandidates.maskForCores(listOf(0, 1, 2, 3, 4, 5)))
        assertEquals("1", TuningCandidates.maskForCores(listOf(0)))
        assertEquals("80", TuningCandidates.maskForCores(listOf(7)))
        assertEquals("", TuningCandidates.maskForCores(emptyList()))
    }

    @Test
    fun `a single cluster offers no mask candidates`() {
        assertEquals(listOf(""), TuningCandidates.maskCandidates(listOf(8)))
    }

    @Test
    fun `two clusters offer all, top, and all-strict masks`() {
        // 2 fast cores + 6 slow ones. Default affinity (""), the fast pair only ("3"), and the
        // whole device pinned strictly ("ff") are three genuinely different placements.
        assertEquals(listOf("", "3", "ff"), TuningCandidates.maskCandidates(listOf(2, 6)))
    }

    @Test
    fun `three clusters offer top and top-two masks`() {
        assertEquals(
            listOf("", "f", "ff"),
            TuningCandidates.maskCandidates(listOf(4, 4, 8)),
        )
    }

    @Test
    fun `the reference hexagon flag set is the only non-default candidate`() {
        val candidates = TuningCandidates.hexFlagCandidates()
        assertEquals(2, candidates.size)
        assertEquals(HexFlags(), candidates.first())
        assertEquals(
            HexFlags(useHmx = true, disableNhvx = true, hostBuf = true, opBatch = 1),
            candidates.last(),
        )
    }

    @Test
    fun `load mode and poll candidates are the two endpoints`() {
        assertEquals(listOf(LoadMode.MMAP, LoadMode.NO_MMAP), TuningCandidates.loadModeCandidates())
        assertEquals(listOf(0, 100), TuningCandidates.pollCandidates())
    }

    @Test
    fun `dimension notes persist their wire name and unknown names are dropped`() {
        assertEquals(TuningDimension.THREADS, TuningDimension.fromWire("threads"))
        assertEquals(TuningDimension.HEX_FLAGS, TuningDimension.fromWire("hexFlags"))
        assertEquals(null, TuningDimension.fromWire("banana"))
    }
}
