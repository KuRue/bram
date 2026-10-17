package io.github.kurue.bram.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TuningLimitsTest {
    @Test fun `padded prefill is not judged by the short reference threshold`() {
        val referenceMillis = 80_000L
        assertTrue(referenceMillis > TuningLimits.slowReferenceMillis(0))
        assertTrue(referenceMillis < TuningLimits.slowReferenceMillis(1024))
        assertEquals(30_000L, TuningLimits.slowReferenceMillis(-1))
    }

    @Test fun `every rejected attempt counts and there is no wait after the final failure`() = runTest {
        var calls = 0
        val retries = mutableListOf<Int>()
        val result = runCatching {
            TuningLimits.reference<String>(attempt = { calls++; null }, beforeRetry = { retries += it })
        }
        assertEquals(3, calls)
        assertEquals(listOf(2, 3), retries)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("previous settings kept"))
    }

    @Test fun `last allowed reference can succeed without another attempt`() = runTest {
        var calls = 0
        val result = TuningLimits.reference(
            attempt = { if (++calls == 3) "reference" else null },
            beforeRetry = {},
        )
        assertEquals("reference", result)
        assertEquals(3, calls)
    }

    @Test fun `cancel during retry wait prevents another native reference`() = runTest {
        var calls = 0
        val job = async {
            TuningLimits.reference<String>(attempt = { calls++; null }, beforeRetry = { awaitCancellation() })
        }
        runCurrent()
        job.cancel()
        job.join()
        assertEquals(1, calls)
    }

    @Test fun `run deadline bounds repeated cooling and preserves completed work`() = runTest {
        val stopped = mutableListOf<String>()
        val completed = mutableListOf<String>()
        var restored = false
        val control = TuningRunControl(this, timeoutMillis = 100, dispatcher = StandardTestDispatcher(testScheduler), onStopped = { stopped += it })
        control.start(onCompleted = { restored = true }) {
            completed += "Threads"
            while (true) delay(60)
        }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("Threads"), completed)
        assertTrue(stopped.single().contains("time limit"))
        assertFalse(restored)
        assertFalse(control.inFlight())
    }

    @Test fun `replacement cannot start before cancelled native cleanup finishes`() = runTest {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val control = TuningRunControl(this, dispatcher = StandardTestDispatcher(testScheduler), onStopped = {
            cleanupStarted.complete(Unit)
            releaseCleanup.await()
        })
        assertTrue(control.start { awaitCancellation() })
        runCurrent()
        control.cancel()
        runCurrent()
        assertTrue(cleanupStarted.isCompleted)
        assertTrue(control.inFlight())
        assertFalse(control.start { error("Must not overlap cleanup") })
        releaseCleanup.complete(Unit)
        runCurrent()
        assertFalse(control.inFlight())
        assertTrue(control.start { })
        runCurrent()
    }

    @Test fun `successful pass restores only once after ownership is released`() = runTest {
        var restored = 0
        lateinit var control: TuningRunControl
        control = TuningRunControl(this, dispatcher = StandardTestDispatcher(testScheduler), onStopped = { fail(it) })
        control.start(onCompleted = { assertFalse(control.inFlight()); restored++ }) { }
        runCurrent()
        assertEquals(1, restored)
    }

    @Test fun `failure settles through cleanup instead of leaving the UI active`() = runTest {
        val stopped = mutableListOf<String>()
        val control = TuningRunControl(this, dispatcher = StandardTestDispatcher(testScheduler), onStopped = { stopped += it })
        var restored = false
        control.start(onCompleted = { restored = true }) { error("Cooldown exceeded") }
        runCurrent()
        assertTrue(stopped.single().contains("Cooldown exceeded"))
        assertFalse(restored)
        assertFalse(control.inFlight())
    }
}
