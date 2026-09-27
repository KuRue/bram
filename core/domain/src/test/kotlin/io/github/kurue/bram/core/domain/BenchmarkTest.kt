package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkTest {

    @Test
    fun `labels read like llama-bench`() {
        assertEquals("pp512", BenchTest(BenchTest.Kind.PROMPT, 512).label)
        assertEquals("tg128", BenchTest(BenchTest.Kind.GENERATION, 128).label)
        assertEquals("tg128@4096", BenchTest(BenchTest.Kind.GENERATION, 128, depth = 4_096).label)
    }

    @Test
    fun `the standard plan goes as deep as the context allows`() {
        assertEquals(listOf("pp512", "tg128", "tg128@4096"), BenchPlan.standard(8_192).map { it.label })
        assertEquals(listOf("pp512", "tg128", "tg128@3968"), BenchPlan.standard(4_096).map { it.label })
        assertEquals("too shallow to be worth it", listOf("pp512", "tg128"), BenchPlan.standard(1_024).map { it.label })
    }

    @Test
    fun `mean, spread, and energy per token`() {
        val result = BenchResult(
            test = BenchTest(BenchTest.Kind.GENERATION, 128),
            tokPerSec = listOf(19.0, 21.0),
            energy = EnergySample(averageWatts = 5.0, baselineWatts = 1.0, samples = 10),
        )
        assertEquals(20.0, result.mean, 1e-9)
        assertEquals(1.4142, result.stdDev, 1e-3)
        // 4 W of work at 20 tokens a second.
        assertEquals(0.2, result.joulesPerToken!!, 1e-9)
    }

    @Test
    fun `no reading means no energy figure`() {
        val result = BenchResult(BenchTest(BenchTest.Kind.PROMPT, 512), tokPerSec = listOf(100.0))
        assertNull(result.joulesPerToken)
        assertEquals(0.0, result.stdDev, 1e-9)
    }

    @Test
    fun `battery current is read in either unit`() {
        // 1.2 A at 4.0 V, reported as microamperes (platform) and as milliamperes (some vendors).
        assertEquals(4.8, BatteryPower.watts(-1_200_000, 4_000)!!, 1e-9)
        assertEquals(4.8, BatteryPower.watts(1_200, 4_000)!!, 1e-9)
        assertNull(BatteryPower.watts(0, 4_000))
        assertNull(BatteryPower.watts(Long.MIN_VALUE, 4_000))
        assertNull(BatteryPower.watts(1_200, 0))
    }

    @Test
    fun `a sustained run reports how far decode fell`() {
        fun tg(rate: Double) = BenchResult(BenchTest(BenchTest.Kind.GENERATION, 128, repetitions = 1), listOf(rate))
        val run = BenchRun("r", "p", "n", "CPU", 0, "f", listOf(tg(24.0), tg(21.0), tg(18.0)), sustained = true)
        assertEquals(0.25, run.sustainedDrop!!, 1e-9)
        assertNull("an ordinary run has no curve", run.copy(sustained = false).sustainedDrop)
    }

    @Test
    fun `a test waits until the phone is back near the starting temperature`() {
        assertEquals(true, Cooldown.ready(currentCelsius = 31.5, runStartCelsius = 31.0))
        assertEquals(false, Cooldown.ready(currentCelsius = 33.0, runStartCelsius = 31.0))
        assertEquals("no reading never blocks", true, Cooldown.ready(null, 31.0))
    }

    @Test
    fun `a config label names the prompt and decode threads only when they differ`() {
        assertEquals("CPU · 6/4 threads · batch 256/128", BenchConfig.label("CPU", 6, 4, 256, 128))
        assertEquals("NPU · 4 threads · batch 256/128", BenchConfig.label("NPU", 4, 4, 256, 128))
        assertEquals("CPU · 4 threads · batch 512/128", BenchConfig.label("CPU", 4, 0, 512, 128))
    }
}
