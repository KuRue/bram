package io.github.kurue.bram.runtime.llamacpp.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuRequirementsTest {
    private val modern = "processor : 0\nFeatures : fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp\n"
    private val a53 = "processor : 0\nFeatures : fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid\n"

    @Test
    fun `a modern core passes`() {
        assertTrue(CpuRequirements.missingFeatures(modern).isEmpty())
    }

    @Test
    fun `an armv8 core is missing both`() {
        assertEquals(listOf("asimdhp", "asimddp"), CpuRequirements.missingFeatures(a53))
    }

    @Test
    fun `every core must have them`() {
        assertEquals(listOf("asimdhp", "asimddp"), CpuRequirements.missingFeatures(modern + a53))
    }

    @Test
    fun `an unreadable cpuinfo does not block`() {
        assertTrue(CpuRequirements.missingFeatures("").isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `an old arm64 phone is refused with a reason`() {
        CpuRequirements.check("arm64-v8a") { a53 }
    }

    @Test
    fun `other abis are not checked`() {
        CpuRequirements.check("x86_64") { a53 }
    }
}
