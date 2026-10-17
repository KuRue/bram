package io.github.kurue.bram.app.test

import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import io.github.kurue.bram.app.OnboardingStore

/**
 * Marks the first-run guide as finished before any test starts, so a suite that begins with no
 * models sees the chat screen rather than the guide covering it. Written before the runner starts
 * its test thread, because the guide is read when the first activity's view model is created.
 * [OnboardingOnDeviceTest] clears the mark for itself and puts it back.
 */
class BramTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        targetContext.getSharedPreferences(OnboardingStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(OnboardingStore.KEY_COMPLETED, true).commit()
        super.onCreate(arguments)
    }
}
