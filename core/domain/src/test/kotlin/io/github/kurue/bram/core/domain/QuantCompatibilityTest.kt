package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantCompatibilityTest {

    @Test
    fun `an all-q4_0 file almost fully offloads to the NPU`() {
        val report = QuantCompatibility.report(mapOf("q4_0" to 129, "q8_0" to 54, "f32" to 3))
        // The q4_0 and q8_0 weights offload; the three f32 tensors (norms and friends) stay on
        // the CPU, which is the honest share.
        assertEquals(98, report.npu.sharePercent)
        assertEquals(100, report.gpu.sharePercent)
        assertEquals("q4_0", report.dominantType)
    }

    @Test
    fun `a q4_k_m file cannot offload to the NPU but can to the GPU`() {
        val report = QuantCompatibility.report(
            mapOf("q4_k" to 190, "q6_k" to 1, "q8_0" to 54, "f32" to 3, "f16" to 1),
        )
        // Only the q8_0 tensors are NPU-supported: 54 of 249.
        assertEquals(21, report.npu.sharePercent)
        assertEquals(100, report.gpu.sharePercent)
        assertEquals("q4_k", report.dominantType)
        assertTrue(report.npu.description.contains("21%"))
    }

    @Test
    fun `the cpu computes anything`() {
        val report = QuantCompatibility.report(mapOf("tq2_0" to 100))
        assertEquals(100, report.cpu.sharePercent)
        assertEquals(0, report.npu.sharePercent)
        assertEquals(0, report.gpu.sharePercent)
    }

    @Test
    fun `an empty count set still produces a valid report`() {
        val report = QuantCompatibility.report(emptyMap())
        assertEquals(100, report.cpu.sharePercent)
        assertEquals(0, report.npu.sharePercent)
        assertEquals(0, report.gpu.sharePercent)
    }
}
