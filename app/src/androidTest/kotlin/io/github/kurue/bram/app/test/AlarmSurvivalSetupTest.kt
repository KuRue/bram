package io.github.kurue.bram.app.test

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.app.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verification harness for the alarm survival path: enqueues a task scheduled a few minutes out
 * and reports its id. The test runner then kills the process, reinstalls the app (which clears
 * alarms and re-arms them through MY_PACKAGE_REPLACED), and the task must fire on its own and
 * become deferred — a notification appears, since no model is loaded in that process. The
 * interesting half of the assertion happens outside this process, so this test only sets the
 * state up; it is @Ignore'd and driven from a session's manual checks (un-ignore it to run).
 */
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore("Manual verification harness; see class kdoc.")
class AlarmSurvivalSetupTest {

    @Test
    fun enqueueScheduledTask() {
        val app = ApplicationProvider.getApplicationContext<BramApplication>()
        val task = app.container.taskRunner.enqueue(
            displayName = "Alarm-survival probe",
            prompt = "Say ready.",
            scheduledAtEpochMillis = System.currentTimeMillis() + SCHEDULE_DELAY_MILLIS,
        )
        assertEquals(TaskState.QUEUED, task.state)
        assertNotNull(task.scheduledAtEpochMillis)
        android.util.Log.i(TAG, "ENQUEUED id=${task.id} at=${task.scheduledAtEpochMillis}")
    }

    private companion object {
        const val SCHEDULE_DELAY_MILLIS = 4 * 60_000L
        const val TAG = "AlarmSurvival"
    }
}
