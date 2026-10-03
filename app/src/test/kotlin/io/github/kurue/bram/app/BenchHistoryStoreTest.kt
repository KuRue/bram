package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.BenchResult
import io.github.kurue.bram.core.domain.BenchRun
import io.github.kurue.bram.core.domain.BenchTest
import io.github.kurue.bram.core.domain.EnergySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchHistoryStoreTest {

    private val run = BenchRun(
        id = "r1",
        profileId = "p1",
        profileName = "LFM",
        backend = "CPU",
        startedAtEpochMillis = 123,
        fingerprint = "fp",
        results = listOf(
            BenchResult(
                test = BenchTest(BenchTest.Kind.PROMPT, 512),
                tokPerSec = listOf(400.5, 410.0),
                energy = EnergySample(6.0, 1.5, 12),
                thermalBefore = "none",
                thermalAfter = "light",
                batteryTempBefore = 31.5,
                batteryTempAfter = 34.0,
                cooldownMillis = 12_000,
            ),
            BenchResult(
                test = BenchTest(BenchTest.Kind.GENERATION, 128, depth = 16_384, repetitions = 2),
                tokPerSec = emptyList(),
                skipped = "depth does not fit",
            ),
        ),
        sustained = true,
        config = "CPU · 6/4 threads · batch 256/128",
    )

    @Test
    fun `a run round-trips, energy and skips included`() {
        assertEquals(listOf(run), BenchHistoryStore.decode(BenchHistoryStore.encode(listOf(run))))
    }

    @Test
    fun `corrupt history reads as empty instead of crashing the screen`() {
        assertTrue(BenchHistoryStore.decode("{nope").isEmpty())
        assertTrue(BenchHistoryStore.decode("").isEmpty())
    }
}
