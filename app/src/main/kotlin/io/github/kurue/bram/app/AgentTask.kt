package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ConversationId

/**
 * The state of a queued task, as it travels through the queue.
 *
 * DEFERRED is not an error: it is a task whose scheduled time has come while no model is loaded
 * to run it. It stays in the queue, and runs the moment a model is loaded again, so an unattended
 * schedule does not lose work to the app simply not being ready.
 */
enum class TaskState(val wire: String, val label: String) {
    QUEUED("queued", "Queued"),
    RUNNING("running", "Running"),
    SUCCEEDED("succeeded", "Succeeded"),
    FAILED("failed", "Failed"),
    CANCELLED("cancelled", "Cancelled"),
    DEFERRED("deferred", "Waiting for a model"),
}

/**
 * One unit of scheduled work.
 *
 * A task is a prompt, run by the agent in its own named conversation — the session the task's
 * reply lands in and can be reopened from. [activityLog] is the human-readable record of what the
 * run did (the tools it called, its phases), for the per-task UI's "what has it done" view.
 */
data class AgentTask(
    val id: String,
    val displayName: String,
    val prompt: String,
    val conversationId: ConversationId,
    val createdAtEpochMillis: Long,
    /** When to start, or null to start as soon as the queue gets to it. */
    val scheduledAtEpochMillis: Long?,
    val state: TaskState,
    val startedAtEpochMillis: Long? = null,
    val finishedAtEpochMillis: Long? = null,
    val resultSummary: String? = null,
    val error: String? = null,
    val activityLog: List<String> = emptyList(),
) {
    val isFinished: Boolean
        get() = state == TaskState.SUCCEEDED || state == TaskState.FAILED || state == TaskState.CANCELLED

    val isActionable: Boolean
        get() = state == TaskState.QUEUED || state == TaskState.DEFERRED
}

/** What a task run produced, returned by the executor to the queue. */
sealed interface TaskOutcome {
    data class Succeeded(val summary: String, val activity: List<String>) : TaskOutcome
    data class Failed(val message: String) : TaskOutcome
}
