package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCeilingTest {

    private fun bandwidth(gbPerSec: Double) = MemoryBandwidth(
        peakBytesPerSecond = gbPerSec * 1e9,
        peakThreads = 8,
        byThreads = mapOf(8 to gbPerSec * 1e9),
        measuredAtEpochMillis = 0,
        fingerprint = "f",
    )

    @Test
    fun `the ceiling is bandwidth over weight bytes`() {
        // 60 GB/s over a 1.5 GB model: every token reads 1.5 GB, so at most 40 tokens a second.
        val result = MemoryCeiling.forDenseModel(1_500_000_000, decodeTokPerSec = 20.0, bandwidth(60.0))!!
        assertEquals(40.0, result.ceilingTokPerSec, 1e-9)
        assertEquals(0.5, result.share, 1e-9)
    }

    @Test
    fun `unusable inputs give no ceiling rather than a wrong one`() {
        assertNull(MemoryCeiling.forDenseModel(0, 10.0, bandwidth(60.0)))
        assertNull(MemoryCeiling.forDenseModel(1_000, 0.0, bandwidth(60.0)))
        assertNull(MemoryCeiling.forDenseModel(1_000, 10.0, bandwidth(0.0)))
    }

    @Test
    fun `the sweep keeps its best thread count as the peak`() {
        val parsed = MemoryBandwidth.fromNative(
            results = listOf(1 to 18.0, 4 to 55.5, 8 to 52.0, 0 to 99.0, 6 to Double.NaN),
            measuredAtEpochMillis = 7,
            fingerprint = "f",
        )!!
        assertEquals(55.5, parsed.peakGbPerSecond, 1e-9)
        assertEquals(4, parsed.peakThreads)
        assertEquals("invalid rows are dropped", setOf(1, 4, 8), parsed.byThreads.keys)
        assertNull(MemoryBandwidth.fromNative(emptyList(), 0, "f"))
    }

    @Test
    fun `mixture-of-experts models are excluded`() {
        assertTrue(MemoryCeiling.appliesTo("llama"))
        assertTrue(MemoryCeiling.appliesTo("qwen3"))
        assertTrue(MemoryCeiling.appliesTo("lfm2"))
        assertFalse(MemoryCeiling.appliesTo("qwen3moe"))
        assertFalse(MemoryCeiling.appliesTo("deepseek2"))
        assertFalse(MemoryCeiling.appliesTo("gpt-oss"))
        assertFalse(MemoryCeiling.appliesTo("granitemoe"))
        assertFalse("unknown architecture", MemoryCeiling.appliesTo(""))
    }
}
