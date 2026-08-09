package io.github.kurue.bram.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.kurue.bram.core.domain.Automation
import io.github.kurue.bram.core.domain.AutomationStore
import io.github.kurue.bram.core.domain.Cron
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Turns automations into alarms and tasks: every enabled automation gets a one-shot alarm at its
 * next cron fire, and when the alarm fires the automation's prompt is enqueued as a task — so the
 * run goes through the queue, gets journaled, defers when no model is loaded, and can be reopened
 * from the conversation library like any task. The next occurrence is then scheduled, chaining the
 * recurrence.
 *
 * Alarms are best-effort, exactly like scheduled tasks: they do not survive a reboot, and a
 * schedule whose fire time passed while the phone was off fires when the app next starts, because
 * [rescheduleAll] is called at startup and an alarm set for a past time fires immediately.
 */
class AutomationRunner(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val store: AutomationStore,
    private val taskRunner: AgentTaskRunner,
) {
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Called at startup and after any schedule change: one alarm per enabled automation. */
    fun rescheduleAll() {
        scope.launch {
            store.automations().forEach { automation ->
                val outcome = reschedule(automation)
                if (outcome is AutomationOutcome.Failed) {
                    // A stored schedule the parser no longer accepts (it was valid when saved, or
                    // the file was hand-edited) must not alarm forever: it just never fires.
                    store.save(automation.copy(nextRunAtEpochMillis = null))
                }
            }
        }
    }

    /**
     * Called by the alarm receiver when an automation's time comes: enqueue its prompt as a task
     * and chain the next fire.
     */
    fun fireDue(automationId: String) {
        scope.launch {
            val automation = store.automations().firstOrNull { it.id == automationId }
                ?: return@launch
            if (!automation.enabled) return@launch
            val now = System.currentTimeMillis()
            store.save(automation.copy(lastRunAtEpochMillis = now, updatedAtEpochMillis = now))
            // scheduledAt = now puts the task at the head of the queue; the queue defers it when
            // no model is loaded, exactly like a one-off scheduled task.
            taskRunner.enqueue(
                displayName = automation.name,
                prompt = automation.prompt,
                scheduledAtEpochMillis = now,
            )
            reschedule(automation.copy(lastRunAtEpochMillis = now))
        }
    }

    private sealed interface AutomationOutcome {
        data object Ok : AutomationOutcome
        data class Failed(val reason: String) : AutomationOutcome
    }

    private suspend fun reschedule(automation: Automation): AutomationOutcome {
        cancelAlarm(automation.id)
        if (!automation.enabled) {
            store.save(automation.copy(nextRunAtEpochMillis = null))
            return AutomationOutcome.Ok
        }
        val spec = when (val result = Cron.parse(automation.cron)) {
            is Cron.Result.Ok -> result.spec
            is Cron.Result.Rejected -> return AutomationOutcome.Failed(result.reason)
        }
        val next = Cron.nextRunAfter(spec, System.currentTimeMillis())
        store.save(automation.copy(nextRunAtEpochMillis = next))
        if (next != null) setAlarm(automation.id, next)
        return AutomationOutcome.Ok
    }

    private fun setAlarm(automationId: String, triggerAtEpochMillis: Long) {
        val pending = pendingIntent(automationId)
        runCatching {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtEpochMillis, pending)
        }
    }

    private fun cancelAlarm(automationId: String) {
        runCatching { alarmManager.cancel(pendingIntent(automationId)) }
    }

    private fun pendingIntent(automationId: String) = PendingIntent.getBroadcast(
        appContext,
        automationId.hashCode(),
        Intent(appContext, TaskAlarmReceiver::class.java)
            .putExtra(TaskAlarmReceiver.EXTRA_AUTOMATION_ID, automationId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
