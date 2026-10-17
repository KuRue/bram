package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAdvisorTest {

    private val snapdragon8EliteFeatures =
        "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma " +
            "lrcpc dcpop sha3 sm3 sm4 asimddp sha512 asimdfhm dit uscat ilrcpc flagm ssbs sb paca pacg " +
            "dcpodp flagm2 frint i8mm bf16 bti"

    private fun cpuinfo(vararg cores: String) =
        cores.mapIndexed { index, features -> "processor\t: $index\nFeatures\t: $features\n" }
            .joinToString("\n")

    private fun scan(
        ramGb: Double = 12.0,
        freeGb: Double = 100.0,
        abi: String = "arm64-v8a",
        cpu: CpuFeatures? = CpuFeatures.parse(cpuinfo(snapdragon8EliteFeatures)),
        accelerators: Set<AcceleratorKind> = emptySet(),
        socManufacturer: String = "QTI",
        socModel: String = "SM8750",
        ggufRuntimeLinked: Boolean? = true,
    ) = HardwareScan(
        deviceName = "Test phone",
        socManufacturer = socManufacturer,
        socModel = socModel,
        primaryAbi = abi,
        totalRamBytes = (ramGb * 1e9).toLong(),
        freeStorageBytes = (freeGb * 1e9).toLong(),
        cpu = cpu,
        runtimeAccelerators = accelerators,
        ggufRuntimeLinked = ggufRuntimeLinked,
    )

    private fun ModelAdvice.finding(title: String) = findings.single { it.title == title }

    @Test
    fun `cpuinfo features are parsed and a feature counts only if every core has it`() {
        val features = CpuFeatures.parse(cpuinfo(snapdragon8EliteFeatures, snapdragon8EliteFeatures))!!
        assertTrue(features.fp16 && features.dotProduct && features.int8Matmul && features.bf16)
        assertFalse(features.sve)

        // A little core without i8mm takes it away from the whole part.
        val mixed = CpuFeatures.parse(cpuinfo(snapdragon8EliteFeatures, "fp asimd asimdhp asimddp"))!!
        assertTrue(mixed.meetsArmBaseline)
        assertFalse(mixed.int8Matmul)
    }

    @Test
    fun `x86 cpuinfo has no Features lines and parses to null`() {
        assertNull(CpuFeatures.parse("processor\t: 0\nflags\t\t: fpu vme sse sse2 avx2\n"))
    }

    @Test
    fun `a 12 GB Snapdragon phone is told 4B comfortably, 8B at a push, as Q4_0`() {
        val advice = ModelAdvisor.advise(scan(ramGb = 11.5))
        assertTrue(advice.canRunLocally)
        assertEquals("about 4B", advice.comfortable?.label)
        assertEquals("about 8B", advice.largest?.label)
        assertEquals("GGUF, Q4_0", advice.recommendedFormat)
        assertEquals(FindingLevel.GOOD, advice.finding("Processor").level)
        assertTrue(advice.reasons.any { "int8 matrix" in it })
    }

    @Test
    fun `a Snapdragon without the Hexagon backend in the build says the NPU is not used`() {
        val npu = ModelAdvisor.advise(scan()).finding("NPU")
        assertEquals(FindingLevel.NEUTRAL, npu.level)
        assertTrue("Hexagon SDK" in npu.detail)
        assertFalse(ModelAdvisor.advise(scan()).reasons.any { "NPU" in it })
    }

    @Test
    fun `a build that enumerated the Hexagon NPU recommends Q4_0 for it`() {
        val advice = ModelAdvisor.advise(scan(accelerators = setOf(AcceleratorKind.HEXAGON_NPU)))
        assertEquals(FindingLevel.GOOD, advice.finding("NPU").level)
        assertTrue(advice.reasons.first().contains("NPU"))
    }

    @Test
    fun `a non-Qualcomm phone is not told about a Snapdragon NPU`() {
        val npu = ModelAdvisor.advise(scan(socManufacturer = "Google", socModel = "Tensor G4")).finding("NPU")
        assertTrue("Qualcomm Hexagon" in npu.detail)
        assertFalse("SDK" in npu.detail)
    }

    @Test
    fun `an older ARMv8 core without dot product can only use a server`() {
        val old = CpuFeatures.parse(cpuinfo("fp asimd aes pmull sha1 sha2 crc32"))
        val advice = ModelAdvisor.advise(scan(cpu = old))
        assertFalse(advice.canRunLocally)
        assertNull(advice.comfortable)
        assertEquals(FindingLevel.BLOCKER, advice.finding("Processor").level)
        assertTrue(advice.reasons.any { "OpenAI-compatible" in it })
    }

    @Test
    fun `a small phone gets a small tier, and a tiny one only a largest tier`() {
        val advice = ModelAdvisor.advise(scan(ramGb = 6.0))
        assertEquals("about 1B", advice.comfortable?.label)
        assertEquals("about 4B", advice.largest?.label)

        val tiny = ModelAdvisor.advise(scan(ramGb = 3.0))
        assertNull(tiny.comfortable)
        assertEquals("about 1B", tiny.largest?.label)
        assertTrue(tiny.canRunLocally)
        assertTrue(tiny.headline.contains("1B"))
    }

    @Test
    fun `free storage caps the tier even with plenty of RAM`() {
        val advice = ModelAdvisor.advise(scan(ramGb = 16.0, freeGb = 2.5))
        assertEquals("about 3B", advice.largest?.label)
        assertEquals(FindingLevel.LIMITED, advice.finding("Storage").level)

        val full = ModelAdvisor.advise(scan(freeGb = 0.5))
        assertFalse(full.canRunLocally)
        assertEquals(FindingLevel.BLOCKER, full.finding("Storage").level)
    }

    @Test
    fun `an emulator still gets advice and is told its speed is not a phone's`() {
        val advice = ModelAdvisor.advise(scan(abi = "x86_64", cpu = null, socManufacturer = "", socModel = ""))
        assertTrue(advice.canRunLocally)
        assertEquals(FindingLevel.NEUTRAL, advice.finding("Processor").level)
        assertTrue(advice.reasons.any { "x86_64" in it })
    }

    @Test
    fun `the GPU row follows what the runtime enumerated`() {
        assertEquals("OpenCL", ModelAdvisor.advise(scan(accelerators = setOf(AcceleratorKind.OPENCL_GPU))).finding("GPU").summary)
        assertEquals(FindingLevel.NEUTRAL, ModelAdvisor.advise(scan(accelerators = setOf(AcceleratorKind.VULKAN_GPU))).finding("GPU").level)
        assertTrue("LiteRT" in ModelAdvisor.advise(scan()).finding("GPU").summary)
    }

    @Test
    fun `every local recommendation mentions LiteRT packages and servers as alternatives`() {
        val advice = ModelAdvisor.advise(scan())
        assertNotNull(advice.alternatives.firstOrNull { ".litertlm" in it })
        assertNotNull(advice.alternatives.firstOrNull { "server" in it })
    }

    @Test
    fun `a build without the llama cpp library for this processor points to a server and LiteRT`() {
        val advice = ModelAdvisor.advise(
            scan(abi = "x86_64", cpu = null, socManufacturer = "", socModel = "", ggufRuntimeLinked = false),
        )
        assertFalse(advice.canRunLocally)
        assertTrue("LiteRT is a separate runtime and may still run", advice.canImportFiles)
        assertEquals(FindingLevel.BLOCKER, advice.finding("Processor").level)
        assertTrue(advice.reasons.first().contains("x86_64"))
        assertTrue(advice.alternatives.single().contains(".litertlm"))
    }

    @Test
    fun `an unknown runtime state is treated as available`() {
        assertTrue(ModelAdvisor.advise(scan(ggufRuntimeLinked = null)).canRunLocally)
    }

    @Test
    fun `an old CPU is not offered the file picker`() {
        val old = CpuFeatures.parse(cpuinfo("fp asimd aes pmull sha1 sha2 crc32"))
        assertFalse(ModelAdvisor.advise(scan(cpu = old)).canImportFiles)
    }

    @Test
    fun `the headline reads as a sentence`() {
        assertEquals("A model of about 4B, as a Q4_0 GGUF", ModelAdvisor.advise(scan(ramGb = 11.5)).headline)
    }

    @Test
    fun `a file's fit uses the same memory shares as the size tiers`() {
        val gb = 1_000_000_000L
        assertEquals(MemoryFit.COMFORTABLE, ModelAdvisor.fit(2 * gb, 12 * gb, 100 * gb).memory)
        assertEquals(MemoryFit.LARGE, ModelAdvisor.fit(5 * gb, 12 * gb, 100 * gb).memory)
        assertEquals(MemoryFit.TOO_LARGE, ModelAdvisor.fit(6 * gb, 12 * gb, 100 * gb).memory)
        assertEquals(MemoryFit.UNKNOWN, ModelAdvisor.fit(2 * gb, 0, 100 * gb).memory)
    }

    @Test
    fun `storage is checked with headroom, and a full disk says so first`() {
        val gb = 1_000_000_000L
        val fit = ModelAdvisor.fit(2 * gb, 12 * gb, 2 * gb)
        assertFalse(fit.storageOk)
        assertTrue(fit.summary.contains("storage"))
        assertTrue(ModelAdvisor.fit(2 * gb, 12 * gb, 3 * gb).storageOk)
    }
}
