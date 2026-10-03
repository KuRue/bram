package io.github.kurue.bram.app

/**
 * What a loaded model is doing right now, for the persistent status notification.
 *
 * Mirrors the phases the transcript already shows, so the notification and the app agree: the
 * same events drive both, only the surface changes. Wire values are the protocol for the
 * notification extras; labels are what the user reads.
 */
enum class ModelPhase(val wire: String, val label: String) {
    IDLE("idle", "Idle"),
    PREPARING("preparing", "Preparing context"),
    GENERATING("generating", "Generating"),
    THINKING("thinking", "Thinking"),
    CALLING_TOOL("tool", "Running a tool"),
}

/**
 * Whether a turn is in flight, which is what the composer's button keys off to offer Stop.
 *
 * The phases are the right source because they are the ones the screen renders as an in-turn state
 * ("System prompt", "Writing", "Thinking", "Using tool") instead of the idle "Ready" label. Keying the
 * button off a separate `isGenerating` flag instead let the two disagree: during a silent remote turn
 * the screen said "Writing…" while the button was disabled, so Stop could not be invoked at all.
 */
internal fun ModelPhase.hasTurnRunning(): Boolean = this != ModelPhase.IDLE

/**
 * Whether a Stop request should be honoured.
 *
 * This is deliberately the same predicate as [hasTurnRunning] and not a separate `isGenerating`
 * flag. They can disagree — during a remote turn the phase is `GENERATING` while `isGenerating` is
 * false — and when they did, `stopGeneration()` returned at its guard with an orchestrator in hand
 * and the turn simply ran on. Keying both the button and the guard to the phase keeps a stop from
 * being offered in one place and refused in the other.
 */
internal fun canStop(phase: ModelPhase): Boolean = phase.hasTurnRunning()

/**
 * What the status service has already been told, so a repeat push costs nothing.
 *
 * Telling the service is not free: each push is a `startForegroundService`, and Android kills the
 * app process if such a start does not reach `startForeground` within ten seconds. A turn pushes
 * far more often than it changes state — every `AgentEvent.Status` re-pushes the phase the turn is
 * already in — so an undeduplicated turn is a burst of identical notifications, and the more of
 * them land while the main thread is busy the likelier one of them is the one that misses the
 * window. Remembering the last push makes every repeat free, which is all a repeat was ever worth:
 * the notification it would post is the one already on screen.
 *
 * [forget] exists because a stopped service has to be started again, not merely updated: the last
 * push said nothing about the service still running.
 */
internal class StatusPushLedger {
    private var last: Pair<ModelPhase, String?>? = null

    /** Whether this push would tell the service something it is not already showing. */
    fun shouldPush(phase: ModelPhase, detail: String?): Boolean {
        val next = phase to detail
        if (next == last) return false
        last = next
        return true
    }

    /** Forgets the last push, so the next one starts the service afresh. */
    fun forget() {
        last = null
    }
}
