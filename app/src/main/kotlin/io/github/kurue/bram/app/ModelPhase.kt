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
