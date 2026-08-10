package io.github.kurue.bram.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Re-arms the alarms Android clears on reboot and on app update, so automations and scheduled
 * tasks survive the phone restarting. Called before the app is ever opened: the queue loads its
 * store, re-arms every future alarm, and starts anything whose time passed while the phone was
 * off — which defers it until a model is loaded and says so, exactly as if the app had been open.
 *
 * The work runs to completion synchronously inside [onReceive]. The process is started for this
 * broadcast and some OEMs kill it the instant onReceive returns, so delegating to a coroutine on
 * the app scope can lose the work when the process dies between onReceive returning and the
 * coroutine landing. The work itself is two small file reads and a handful of alarm sets — a few
 * milliseconds — so running it inline (via runBlocking) is safe and cannot be killed mid-flight.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? BramApplication ?: return
        Log.i(TAG, "re-arming after ${intent.action}")
        runBlocking {
            app.container.automationRunner.rescheduleAllNow()
            app.container.taskRunner.reschedulePendingNow()
        }
        Log.i(TAG, "re-armed")
    }

    private companion object {
        const val TAG = "BramBoot"
    }
}
