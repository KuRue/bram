package io.github.kurue.bram.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when a scheduled task comes due, or when an automation's cron time arrives. Wakes the
 * app's process if it was killed, and asks the queue to start the task; if no model is loaded the
 * task becomes deferred and a notification says it is waiting.
 */
class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? BramApplication ?: return
        val automationId = intent.getStringExtra(EXTRA_AUTOMATION_ID)
        if (automationId != null) {
            app.container.automationRunner.fireDue(automationId)
        } else {
            app.container.taskRunner.processNow()
        }
    }

    companion object {
        const val EXTRA_AUTOMATION_ID = "automationId"
    }
}
