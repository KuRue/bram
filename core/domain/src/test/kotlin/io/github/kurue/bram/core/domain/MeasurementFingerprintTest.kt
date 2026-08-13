package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MeasurementFingerprintTest {

    @Test
    fun `the key is stable for the same parts`() {
        val fingerprint = MeasurementFingerprint(
            device = "abc123",
            appBuild = "0.2.0/2",
            engineBuild = "132753bf",
            cpuFeatures = "NEON=1|DOTPROD=1|MATMUL_INT8=1|SVE=1|SME=0",
        )
        assertEquals(
            "d=abc123|app=0.2.0/2|engine=132753bf|cpu=NEON=1|DOTPROD=1|MATMUL_INT8=1|SVE=1|SME=0",
            fingerprint.key,
        )
        assertEquals(fingerprint.key, fingerprint.copy().key)
    }

    @Test
    fun `any part change breaks the key`() {
        val base = MeasurementFingerprint("d", "a", "e", "c")
        assertNotEquals(base.key, base.copy(device = "x").key)
        assertNotEquals(base.key, base.copy(appBuild = "x").key)
        assertNotEquals(base.key, base.copy(engineBuild = "x").key)
        assertNotEquals(base.key, base.copy(cpuFeatures = "x").key)
    }

    @Test
    fun `cpu feature flags parse the system info line format`() {
        val systemInfo = "CPU : NEON = 1 | ARM_FMA = 1 | FP16_VA = 1 | MATMUL_INT8 = 1 | " +
            "SVE = 1 | DOTPROD = 1 | SVE_CNT = 8 | OPENMP = 1 | REPACK = 1 | SME = 0 | KLEIDIAI = 1"
        assertEquals(
            "NEON=1|DOTPROD=1|MATMUL_INT8=1|SVE=1|SME=0",
            MeasurementFingerprint.cpuFeatureFlags(systemInfo),
        )
    }

    @Test
    fun `a device without extensions parses as all zeroes`() {
        val systemInfo = "CPU : NEON = 1 | ARM_FMA = 1 | OPENMP = 1 | SVE_CNT = 0"
        assertEquals(
            "NEON=1|DOTPROD=0|MATMUL_INT8=0|SVE=0|SME=0",
            MeasurementFingerprint.cpuFeatureFlags(systemInfo),
        )
        assertEquals(
            "NEON=0|DOTPROD=0|MATMUL_INT8=0|SVE=0|SME=0",
            MeasurementFingerprint.cpuFeatureFlags(""),
        )
    }
}
