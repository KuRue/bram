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
 * The work runs to completion inside this process rather than being delegated to a coroutine on
 * the app scope: the process is started for this broadcast and some OEMs kill it the instant
 * onReceive returns, so a coroutine could lose the work when the process dies between onReceive
 * returning and the coroutine landing. [goAsync] keeps the broadcast alive until [PendingResult.finish]
 * so the re-arm still cannot be dropped, while the background thread keeps a slow cold start
 * (ART settling, the inference process spinning up to run a task whose time passed) from holding
 * the main thread long enough to trip the broadcast ANR and have the system kill the process
 * mid-re-arm.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? BramApplication ?: return
        Log.i(TAG, "re-arming after ${intent.action}")
        val pendingResult = goAsync()
        Thread {
            try {
                runBlocking {
                    app.container.automationRunner.rescheduleAllNow()
                    app.container.taskRunner.reschedulePendingNow()
                }
                Log.i(TAG, "re-armed")
            } catch (t: Throwable) {
                Log.w(TAG, "re-arm failed", t)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "BramBoot"
    }
}
