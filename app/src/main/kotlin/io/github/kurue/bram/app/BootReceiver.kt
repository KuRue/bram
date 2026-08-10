package io.github.kurue.bram.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch

/**
 * Re-arms the alarms Android clears on reboot and on app update, so automations and scheduled
 * tasks survive the phone restarting. Called before the app is ever opened: the queue loads its
 * store, re-arms every future alarm, and starts anything whose time passed while the phone was
 * off — which defers it until a model is loaded and says so, exactly as if the app had been open.
 *
 * The process is started for the broadcast and may be killed as soon as [onReceive] returns, so
 * the result window is held open (goAsync) until both runners have done their work.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? BramApplication ?: return
        val pendingResult = goAsync()
        app.container.appScope.launch {
            try {
                app.container.automationRunner.rescheduleAllNow()
                app.container.taskRunner.reschedulePendingNow()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
