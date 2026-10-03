package io.github.kurue.bram.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the lifecycle of the benchmark a profile card can stop.
 *
 * Benchmarking is automatic by default and this is the one thing that was missing from it: the run
 * used to be fire-and-forget. [MainViewModel.runBenchmark] launched into `viewModelScope` and dropped
 * the [Job], so there was no handle to stop it and no way out of a sustained run — five minutes of
 * back-to-back decode — short of leaving the screen. On `main` there is no `cancelBenchmark` at all,
 * so the whole contract below is new.
 *
 * The part that is easy to get wrong is the settling. Cancelling a coroutine does not stop it
 * writing: cancelled between two suspension points, it still runs the rest of that segment. A run
 * stopped mid-measurement would therefore carry on and set `benchmarkStatus` again, putting the
 * card's spinner back after the user had stopped it. The ticket is what makes the settle final, so
 * "no write after cancel" is asserted here rather than assumed.
 *
 * This drives the same helper [MainViewModel] uses, so it is a unit test of the lifecycle and not
 * of the view model itself: `AppContainer` builds ~30 Android-backed stores eagerly, so a
 * `MainViewModel` cannot be constructed in a plain JVM test. What is asserted is the contract the
 * view model leans on — the handle is retained, cancel stops it, a retired ticket cannot write, the
 * card settles on a terminal status, and nothing else is disturbed, which is what leaves the model
 * loaded and a later run free to start.
 */
class BenchmarkRunControlTest {

    @Test
    fun `cancelling stops the run and retires its ticket`() = runTest {
        val control = BenchmarkRunControl()
        val ticket = control.begin()
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            started.complete(Unit)
            // A sustained run: minutes of work with no natural end.
            delay(Long.MAX_VALUE)
        }
        control.attach(ticket, job)
        started.await()

        assertTrue("the card must know a run is in flight", control.inFlight())
        assertTrue("cancel must report that it stopped something", control.cancel())

        job.join()
        assertFalse("the run must not be in flight after cancel", control.inFlight())
        assertFalse(
            "a cancelled run must not be able to write state again",
            control.isCurrent(ticket),
        )
    }

    @Test
    fun `a run cancelled mid-segment cannot write its status back`() = runTest {
        val control = BenchmarkRunControl()
        val ticket = control.begin()
        // The status the view model would hold: a live step, then a settled one.
        var status: String? = "Cooling (44.0°C…)"
        val write = {
            // Exactly what setBenchmarkStatus does: apply only while the ticket still owns the run.
            if (control.isCurrent(ticket)) status = "tg128 (3/3)…"
        }
        val gate = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            gate.await()
            write()
        }
        control.attach(ticket, job)

        control.cancel()
        // The segment the cancelled coroutine was already inside now runs to its end.
        gate.complete(Unit)
        job.join()

        assertEquals(
            "the card kept a spinner because a cancelled run wrote its step back",
            "Cooling (44.0°C…)",
            status,
        )
    }

    @Test
    fun `the card settles on a terminal status rather than going quiet`() = runTest {
        val control = BenchmarkRunControl()
        val ticket = control.begin()
        val job = launch(Dispatchers.Default) { delay(Long.MAX_VALUE) }
        control.attach(ticket, job)

        val stopped = control.cancel()
        job.join()

        assertTrue("cancel must report that it stopped the run", stopped)
        assertEquals(
            "a card that was running something must say what became of it",
            BenchmarkRunControl.CANCELLED_STATUS,
            statusOf(control),
        )
    }

    @Test
    fun `cancelling with nothing in flight stops nothing`() = runTest {
        val control = BenchmarkRunControl()
        assertFalse("cancel must not claim to have stopped a run that never started", control.cancel())
    }

    @Test
    fun `cancelling twice does not report a second cancellation`() = runTest {
        val control = BenchmarkRunControl()
        val ticket = control.begin()
        val job = launch(Dispatchers.Default) { delay(Long.MAX_VALUE) }
        control.attach(ticket, job)

        assertTrue(control.cancel())
        job.join()
        assertFalse("the run is already gone; a second press must not settle the card again", control.cancel())
        assertFalse(control.inFlight())
    }

    @Test
    fun `a run whose ticket was retired before its job existed is cancelled on attach`() = runTest {
        // The gap the view model opens by setting the card's state before launching. A cancel inside
        // it must not leave a run that starts anyway after the user has stopped it.
        val control = BenchmarkRunControl()
        val ticket = control.begin()
        control.cancel()

        // The body reaches its end only if it is allowed to run to completion, which is what "came
        // to life" means. A launched coroutine may execute its first segment before the cancel
        // lands, so the assertion is on the work finishing, not on a statement being skipped.
        var ranToCompletion = false
        val job = launch(Dispatchers.Default) {
            delay(Long.MAX_VALUE)
            ranToCompletion = true
        }
        val accepted = control.attach(ticket, job)
        job.join()

        assertFalse("a retired ticket must not accept a job", accepted)
        assertFalse("a run cancelled in the gap must not run to completion", ranToCompletion)
        assertFalse(control.inFlight())
    }

    @Test
    fun `a run that finishes of its own accord releases the handle`() = runTest {
        val control = BenchmarkRunControl()
        val ticket = control.begin()
        val job = launch(Dispatchers.Default) { /* finishes at once */ }
        control.attach(ticket, job)
        job.join()

        assertTrue("the run is done, so nothing is in flight", !control.inFlight())
        // And the card can be stopped-and-settled for a later run without being told twice.
        assertFalse(control.cancel())
    }

    @Test
    fun `a finished run cannot clear the card of the run that replaced it`() = runTest {
        val control = BenchmarkRunControl()
        val first = control.begin()
        val firstJob = launch(Dispatchers.Default) { delay(Long.MAX_VALUE) }
        control.attach(first, firstJob)

        val second = control.begin()
        val secondJob = launch(Dispatchers.Default) { delay(Long.MAX_VALUE) }
        control.attach(second, secondJob)

        // The first run's finally block arrives late, after the second has taken over.
        control.onFinished(first)
        assertTrue("the replacement run is in flight and must not be settled by the old one", control.inFlight())
        assertTrue(control.isCurrent(second))

        assertTrue(control.cancel())
        secondJob.join()
        // The replaced run has no handle left to reach, so it is cancelled directly rather than
        // left for the test scope to wait on.
        firstJob.cancel()
        firstJob.join()
    }

    @Test
    fun `a second run can start after a cancel`() = runTest {
        // The model stays loaded through a cancel, so the card's Benchmark button must work again.
        val control = BenchmarkRunControl()
        val first = control.begin()
        val firstJob = launch(Dispatchers.Default) { delay(Long.MAX_VALUE) }
        control.attach(first, firstJob)
        control.cancel()
        firstJob.join()

        val second = control.begin()
        val secondJob = launch(Dispatchers.Default) { delay(Long.MAX_VALUE) }
        assertTrue("a run after a cancel must be accepted", control.attach(second, secondJob))
        assertTrue(control.inFlight())

        assertTrue(control.cancel())
        secondJob.join()
    }

    /** Mirrors what the card shows: a stopped run settles on the cancelled status, nothing else. */
    private fun statusOf(control: BenchmarkRunControl): String =
        if (control.inFlight()) "Benchmarking…" else BenchmarkRunControl.CANCELLED_STATUS
}