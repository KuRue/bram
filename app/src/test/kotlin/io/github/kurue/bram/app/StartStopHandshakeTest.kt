package io.github.kurue.bram.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins that a stop can never be issued while a start is still on its way to the foreground.
 *
 * Doing so is fatal, not merely untidy: Android tears the service record down while it is still
 * waiting for the `startForeground` it was promised, and answers the app with
 * `ForegroundServiceDidNotStartInTimeException`. That arrives on a binder thread as an uncaught
 * exception and kills the process, so a turn dies and the next test in the suite inherits a dead
 * app. It was caught on the cold AVD with the service brought down 17ms after a start whose healthy
 * window is 11-16ms wide.
 */
class StartStopHandshakeTest {
    @Test
    fun `a stop with no start in flight is issued now`() {
        // The ordinary settle of a turn: nothing has been started, so the stop is due at once.
        assertTrue(StartStopHandshake.shouldStopNow())
    }

    @Test
    fun `a stop issued while a start is pending is deferred, not dropped`() {
        StartStopHandshake.onStartRequested()
        assertFalse(
            "issuing this stop is what killed the app; it must wait for the foreground",
            StartStopHandshake.shouldStopNow(),
        )
        assertTrue(
            "the deferred stop must still be carried out once the start lands",
            StartStopHandshake.onReachedForeground(),
        )
    }

    @Test
    fun `a start that reaches the foreground with nothing owed leaves the service running`() {
        StartStopHandshake.onStartRequested()
        assertFalse(StartStopHandshake.onReachedForeground())
        // And the service is now safe to stop outright.
        assertTrue(StartStopHandshake.shouldStopNow())
    }

    @Test
    fun `a start supersedes a stop owed to an earlier start`() {
        StartStopHandshake.onStartRequested()
        StartStopHandshake.shouldStopNow()
        // A new phase arrives before the service got there: the service is wanted after all, so the
        // owed stop must not fire the moment the start lands.
        StartStopHandshake.onStartRequested()
        assertFalse(StartStopHandshake.onReachedForeground())
    }

    @Test
    fun `a start that never left the process leaves the next stop due`() {
        // Otherwise every later stop is deferred to a service that is never coming, and the
        // foreground service can never be taken down again for the life of the process.
        StartStopHandshake.onStartRequested()
        StartStopHandshake.shouldStopNow()
        StartStopHandshake.onStartFailed()
        assertTrue(StartStopHandshake.shouldStopNow())
    }

    @Test
    fun `repeated stops while a start is pending are still honoured once`() {
        StartStopHandshake.onStartRequested()
        assertFalse(StartStopHandshake.shouldStopNow())
        assertFalse(StartStopHandshake.shouldStopNow())
        assertTrue(StartStopHandshake.onReachedForeground())
        assertFalse("the owed stop is carried out exactly once", StartStopHandshake.onReachedForeground())
    }
}