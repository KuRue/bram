package io.github.kurue.bram.app.test

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kurue.bram.app.BramApplication
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The second half of the alarm-survival verification: run after [AlarmSurvivalSetupTest] has
 * enqueued a future task and the host has force-stopped the app (which cancels alarms, exactly
 * like the system does on reboot). Calls the same re-arm the boot receiver uses, so the alarm
 * must reappear in the system; the host checks `dumpsys alarm` and then watches the task fire
 * and defer with a notification. The interesting assertions happen outside this process; @Ignore'd
 * and driven from a session's manual checks (un-ignore it to run).
 */
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore("Manual verification harness; see class kdoc.")
class RescheduleProbeTest {

    @Test
    fun reschedulePendingReArmsAlarm() {
        val app = ApplicationProvider.getApplicationContext<BramApplication>()
        runBlocking { app.container.taskRunner.reschedulePendingNow() }
        android.util.Log.i(TAG, "RESCHEDULED")
    }

    private companion object {
        const val TAG = "AlarmSurvival"
    }
}
