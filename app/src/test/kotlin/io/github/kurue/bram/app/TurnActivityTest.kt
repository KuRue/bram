package io.github.kurue.bram.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what "a turn is running" means, which is what the composer's button keys off.
 *
 * This exists because two fields disagreed. The stop button decided between Stop and Send using
 * `isGenerating`, while the screen showed a live turn from `modelPhase`. During a silent remote turn
 * those diverged: the phase was `GENERATING` and the composer read "Writing…", but the button came
 * out disabled, so a tap did nothing. No cancellation was ever requested — not by a person, and not
 * by the device gate. The screen's own in-turn phases are the authoritative signal, because they are
 * the ones the label function renders instead of "Ready".
 */
class TurnActivityTest {
    @Test
    fun anIdleScreenHasNoTurnRunning() {
        assertFalse(ModelPhase.IDLE.hasTurnRunning())
    }

    @Test
    fun everyPhaseTheScreenRendersAsAnInTurnPhaseCounts() {
        // These are exactly the phases `BramApp.kt` renders as "System prompt", "Writing", "Thinking"
        // and "Using tool" instead of the idle "Ready" label.
        assertTrue(ModelPhase.PREPARING.hasTurnRunning())
        assertTrue(ModelPhase.GENERATING.hasTurnRunning())
        assertTrue(ModelPhase.THINKING.hasTurnRunning())
        assertTrue(ModelPhase.CALLING_TOOL.hasTurnRunning())
    }
}
