package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.AgentActivity
import io.github.kurue.bram.core.domain.AgentEvent
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a silent turn is detected and classified.
 *
 * The failure this pins was seen on the phone: a turn sat on PREPARING / "System prompt…" for
 * 21 minutes with zero events and no fallback. The watchdog fires after [TURN_STALL_TIMEOUT_MILLIS]
 * of non-Status silence, pauses while a tool/approval/permission wait is live, and the exception
 * it throws must settle as a failure (not a cancellation) so the fallback loop still runs.
 */
class TurnStallWatchdogTest {

    private companion object {
        private val TOOL_ROW =
            AgentActivity.ToolInvocation(id = "call_1", name = "write_note", argumentsJson = "{}")
    }

    private var now = 0L
    private val watchdog = TurnStallWatchdog(timeoutMillis = 300_000L, clock = { now })

    @Test
    fun `a turn that has not reached the timeout is not stalled`() {
        now = 299_999L
        assertFalse(watchdog.stalled(paused = false))
    }

    @Test
    fun `a turn at the timeout is stalled`() {
        now = 300_000L
        assertTrue(watchdog.stalled(paused = false))
    }

    @Test
    fun `a progress event restarts the clock`() {
        now = 200_000L
        watchdog.onEvent(AgentEvent.TextDelta("hi"))
        now = 450_000L
        // 250ms past the event, under the 300s timeout measured from the event.
        assertFalse(watchdog.stalled(paused = false))
        now = 500_000L
        assertTrue(watchdog.stalled(paused = false))
    }

    @Test
    fun `a status label does not restart the clock`() {
        // Status is narration: the observed hang could cycle identical labels while wedged.
        now = 200_000L
        watchdog.onEvent(AgentEvent.Status("System prompt…"))
        now = 300_000L
        assertTrue(watchdog.stalled(paused = false))
    }

    @Test
    fun `a paused turn never stalls and gets a fresh clock when unpaused`() {
        now = 10_000_000L
        assertFalse(watchdog.stalled(paused = true))
        // The paused check refreshed the clock, so the full timeout applies from there.
        now = 10_299_999L
        assertFalse(watchdog.stalled(paused = false))
        now = 10_300_000L
        assertTrue(watchdog.stalled(paused = false))
    }

    @Test
    fun `a backwards clock jump restarts the budget instead of suppressing the stall`() {
        // A source that moved backwards says nothing about elapsed time. Reading the negative
        // span as silence would leave the watchdog unable to fire for the rest of the turn —
        // the one failure it exists to prevent — so the budget must restart from the new reading
        // rather than pin or trip.
        now = 299_000L
        assertFalse(watchdog.stalled(paused = false))
        now = -3_600_000L
        assertFalse(watchdog.stalled(paused = false))
        // A full budget of forward time after the jump, measured from the jump.
        now = -3_300_001L
        assertFalse(watchdog.stalled(paused = false))
        now = -3_300_000L
        assertTrue(watchdog.stalled(paused = false))
    }

    @Test
    fun `any produced output keeps the turn`() {
        assertTrue(stallProducedOutput(hasCompletedMessage = true, hasAssistantText = false, activity = emptyList(), hasRemoteReasoning = false))
        assertTrue(stallProducedOutput(hasCompletedMessage = false, hasAssistantText = true, activity = emptyList(), hasRemoteReasoning = false))
        assertTrue(stallProducedOutput(hasCompletedMessage = false, hasAssistantText = false, activity = listOf(TOOL_ROW), hasRemoteReasoning = false))
        assertTrue(stallProducedOutput(hasCompletedMessage = false, hasAssistantText = false, activity = emptyList(), hasRemoteReasoning = true))
    }

    @Test
    fun `thinking rows alone are not output`() {
        // The failure this whole path exists for: a reasoning model that spent its budget inside
        // an unclosed thinking block left rows and no answer. Counting that row as output would
        // settle the stall as a success — still no answer on screen, and no fallback left to
        // provide one.
        assertFalse(
            stallProducedOutput(
                hasCompletedMessage = false,
                hasAssistantText = false,
                activity = listOf(AgentActivity.Thinking(text = "a long chain of thought", durationMillis = 39_000L)),
                hasRemoteReasoning = false,
            ),
        )
    }

    @Test
    fun `a stall with nothing produced settles as a failure`() {
        assertFalse(stallProducedOutput(hasCompletedMessage = false, hasAssistantText = false, activity = emptyList(), hasRemoteReasoning = false))
    }

    @Test
    fun `the stall exception is a failure, not a cancellation`() {
        // Cancellation maps to Cancelled upstream, which stops the fallback loop — a stall must
        // not take that path or the turn dies with no second runtime tried. Checked reflectively
        // because a direct `is` against a non-subtype is a compile-time constant (KTLC-365).
        assertFalse(
            CancellationException::class.java.isAssignableFrom(TurnStalledException::class.java),
        )
    }
}
