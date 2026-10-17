package io.github.kurue.bram.app

import android.content.Context

/**
 * Whether the first-run guide has been finished or skipped.
 *
 * Set once a model or server exists, or when the guide is skipped, so a person who later removes
 * every model is sent to the Models panel rather than shown the introduction again. The guide can
 * still be reopened from Settings.
 */
class OnboardingStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun completed(): Boolean = preferences.getBoolean(KEY_COMPLETED, false)

    fun markCompleted() {
        preferences.edit().putBoolean(KEY_COMPLETED, true).apply()
    }

    companion object {
        const val PREFERENCES = "bram-onboarding-v1"
        const val KEY_COMPLETED = "completed"
    }
}
