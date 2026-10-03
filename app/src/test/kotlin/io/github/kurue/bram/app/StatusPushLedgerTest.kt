package io.github.kurue.bram.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins which status pushes reach the foreground service, because every one that does is a
 * `startForegroundService` and Android kills the app process when such a start does not reach
 * `startForeground` within ten seconds.
 *
 * The turn that proved it was a remote one against the harness mock. The suite reported
 * `ForegroundServiceDidNotStartInTimeException` on `AgentTaskService` out of
 * `RemoteToolLoopSmokeTest`, and the device logcat showed why there was so much to miss: a single
 * turn started the service several times, and a `stopForeground` sat between two of those starts.
 * Two separate causes, both pinned below — a phase that does not change re-pushed, and a settled
 * remote turn stopping the service and starting it again immediately.
 */
class StatusPushLedgerTest {
    @Test
    fun aFirstPushIsAlwaysWorthMaking() {
        assertTrue(StatusPushLedger().shouldPush(ModelPhase.GENERATING, null))
    }

    @Test
    fun repeatingThePhaseTheServiceIsAlreadyShowingIsNot() {
        // This is the common case: every `AgentEvent.Status` re-pushes the phase the turn is
        // already in, so a turn pushes far more often than it changes state.
        val ledger = StatusPushLedger()
        assertTrue(ledger.shouldPush(ModelPhase.GENERATING, null))
        assertFalse(ledger.shouldPush(ModelPhase.GENERATING, null))
        assertFalse(ledger.shouldPush(ModelPhase.GENERATING, null))
    }

    @Test
    fun aChangedPhaseIsWorthMakingAgain() {
        val ledger = StatusPushLedger()
        assertTrue(ledger.shouldPush(ModelPhase.GENERATING, null))
        assertTrue(ledger.shouldPush(ModelPhase.THINKING, null))
        assertTrue(ledger.shouldPush(ModelPhase.GENERATING, null))
    }

    @Test
    fun aDifferentDetailIsADifferentNotification() {
        // The approval phase is the only one carrying detail, and two approvals saying different
        // things are two notifications worth posting.
        val ledger = StatusPushLedger()
        assertTrue(ledger.shouldPush(ModelPhase.CALLING_TOOL, "Awaiting approval"))
        assertFalse(ledger.shouldPush(ModelPhase.CALLING_TOOL, "Awaiting approval"))
        assertTrue(ledger.shouldPush(ModelPhase.CALLING_TOOL, "Reading file"))
    }

    @Test
    fun forgettingMakesTheSamePhaseWorthPushingAgain() {
        // A stopped service has to be started again, not merely updated: the last push said nothing
        // about the service still running. Without this the phase after a stop would be dropped and
        // the notification would never come back.
        val ledger = StatusPushLedger()
        assertTrue(ledger.shouldPush(ModelPhase.GENERATING, null))
        ledger.forget()
        assertTrue(ledger.shouldPush(ModelPhase.GENERATING, null))
    }
}