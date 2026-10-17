package io.github.kurue.bram.app.test

import android.app.ActivityManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kurue.bram.runtime.llamacpp.inference.LlamaCppServiceClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Checks real process replacement even with a second binding retaining the service. */
class InferenceRestartOnDeviceTest {
    @Test fun restartTerminatesTheOldProcessRatherThanJustRebinding() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = LlamaCppServiceClient(context)
        val other = LlamaCppServiceClient(context)
        val processes = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        fun pid(): Int = processes.runningAppProcesses
            .first { it.processName == "${context.packageName}:inference" }.pid
        try {
            withTimeout(30_000L) {
                client.probe()
                other.probe()
                val before = pid()
                client.restartInferenceProcess()
                // The other binding cannot keep the native process alive after shutdown.
                client.probe()
                val after = pid()
                assertNotEquals(before, after)
            }
        } finally {
            client.close()
            other.close()
        }
    }
}
