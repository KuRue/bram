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
