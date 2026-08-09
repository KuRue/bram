package io.github.kurue.bram.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.kurue.bram.core.domain.ConversationId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The task queue: runs one task at a time, keeps its state on disk, and wakes the app when a
 * scheduled task comes due.
 *
 * The queue holds the agent work, but does not know how to do it — the ViewModel installs an
 * [executor] at startup. A task whose time has come while no model is loaded is marked
 * [TaskState.DEFERRED] rather than failed: an unattended schedule should not lose work because the
 * app happened not to be ready, so deferred tasks start as soon as a model is loaded again.
 */
class AgentTaskRunner(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val store: AgentTaskStore,
) {
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val mutableTasks = MutableStateFlow<List<AgentTask>>(emptyList())
    val tasks: StateFlow<List<AgentTask>> = mutableTasks.asStateFlow()

    /** Installed by the ViewModel: runs one task and reports its outcome. Null = never runnable. */
    @Volatile
    var executor: (suspend (AgentTask) -> TaskOutcome)? = null

    /** Installed by the ViewModel: true when a runtime is ready to run a task right now. */
    @Volatile
    var canRun: () -> Boolean = { false }

    /**
     * Installed by the ViewModel, which knows whether the app is foregrounded and whether the
     * user wants alerts. Called when a task finishes or becomes deferred.
     */
    @Volatile
    var notifier: (AgentTask) -> Unit = {}

    private var currentJob: Job? = null

    init {
        scope.launch {
            mutableTasks.value = runCatching { store.load() }.getOrDefault(emptyList())
                .sortedByDescending(AgentTask::createdAtEpochMillis)
        }
    }

    fun enqueue(displayName: String, prompt: String, scheduledAtEpochMillis: Long? = null): AgentTask {
        val task = AgentTask(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            prompt = prompt,
            conversationId = ConversationId(UUID.randomUUID().toString()),
            createdAtEpochMillis = System.currentTimeMillis(),
            scheduledAtEpochMillis = scheduledAtEpochMillis,
            state = TaskState.QUEUED,
        )
        val updated = listOf(task) + mutableTasks.value
        mutableTasks.value = updated
        scope.launch { store.save(updated) }
        if (scheduledAtEpochMillis != null) scheduleAlarm(task)
        processNow()
        return task
    }

    /**
     * Starts any queued task whose time has come. Called when enqueuing, when a model loads, and
     * from the alarm receiver when a scheduled task comes due while the app is closed.
     */
    fun processNow() {
        if (currentJob?.isActive == true) return
        val now = System.currentTimeMillis()
        val due = mutableTasks.value
            .filter { it.state == TaskState.QUEUED }
            .filter { it.scheduledAtEpochMillis == null || it.scheduledAtEpochMillis <= now }
            .firstOrNull()
            ?: return
        if (!canRun()) {
            mark(due, TaskState.DEFERRED)
            notifier(mutableTasks.value.first { it.id == due.id })
            return
        }
        currentJob = scope.launch {
            val running = mutableTasks.value
                .filterNot { it.id == due.id }
                .plus(due.copy(state = TaskState.RUNNING, startedAtEpochMillis = System.currentTimeMillis()))
                .sortedByDescending(AgentTask::createdAtEpochMillis)
            mutableTasks.value = running
            store.save(running)
            val outcome = try {
                executor?.invoke(due) ?: TaskOutcome.Failed("No executor installed")
            } catch (e: CancellationException) {
                val state = mutableTasks.value
                store.save(state.map { if (it.id == due.id) it.copy(state = TaskState.CANCELLED, finishedAtEpochMillis = System.currentTimeMillis()) else it })
                throw e
            } catch (e: Throwable) {
                TaskOutcome.Failed(e.message ?: "Unknown error")
            }
            val finished = when (outcome) {
                is TaskOutcome.Succeeded -> mutableTasks.value.map { task ->
                    if (task.id == due.id) {
                        task.copy(
                            state = TaskState.SUCCEEDED,
                            finishedAtEpochMillis = System.currentTimeMillis(),
                            resultSummary = outcome.summary,
                            error = null,
                            activityLog = outcome.activity,
                        )
                    } else {
                        task
                    }
                }
                is TaskOutcome.Failed -> mutableTasks.value.map { task ->
                    if (task.id == due.id) {
                        task.copy(
                            state = TaskState.FAILED,
                            finishedAtEpochMillis = System.currentTimeMillis(),
                            resultSummary = null,
                            error = outcome.message,
                        )
                    } else {
                        task
                    }
                }
            }
            mutableTasks.value = finished
            store.save(finished)
            notifier(finished.first { it.id == due.id })
            currentJob = null
            processNow()
        }
    }

    fun cancel(id: String) {
        val snapshot = mutableTasks.value
        val task = snapshot.firstOrNull { it.id == id } ?: return
        currentJob?.cancel()
        val updated = snapshot.map {
            if (it.id == id) {
                it.copy(state = TaskState.CANCELLED, finishedAtEpochMillis = System.currentTimeMillis())
            } else {
                it
            }
        }
        mutableTasks.value = updated
        scope.launch { store.save(updated) }
    }

    fun retry(id: String) {
        val updated = mutableTasks.value.map {
            if (it.id == id) {
                it.copy(
                    state = TaskState.QUEUED,
                    startedAtEpochMillis = null,
                    finishedAtEpochMillis = null,
                    resultSummary = null,
                    error = null,
                    activityLog = emptyList(),
                )
            } else {
                it
            }
        }
        mutableTasks.value = updated
        scope.launch { store.save(updated) }
        processNow()
    }

    fun delete(id: String) {
        val updated = mutableTasks.value.filterNot { it.id == id }
        mutableTasks.value = updated
        scope.launch { store.save(updated) }
    }

    private fun mark(task: AgentTask, state: TaskState) {
        val updated = mutableTasks.value.map {
            if (it.id == task.id) it.copy(state = state) else it
        }
        mutableTasks.value = updated
        scope.launch { store.save(updated) }
    }

    private fun scheduleAlarm(task: AgentTask) {
        val pending = PendingIntent.getBroadcast(
            appContext,
            task.id.hashCode(),
            Intent(appContext, TaskAlarmReceiver::class.java)
                .putExtra(EXTRA_TASK_ID, task.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                task.scheduledAtEpochMillis ?: return,
                pending,
            )
        }
    }

    private companion object {
        const val EXTRA_TASK_ID = "taskId"
    }
}
