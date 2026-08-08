package io.github.kurue.bram.app

import android.content.Context

/**
 * The app-level notification preferences.
 *
 * The other stores are per-domain (models, profiles, endpoints); these are settings about Bram
 * itself, so they get a small store of their own in the same SharedPreferences pattern.
 */
class NotificationSettingsStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Whether a finished turn posts a completion alert when the app is backgrounded. */
    fun completionAlertsEnabled(): Boolean = preferences.getBoolean(KEY_COMPLETION_ALERTS, false)

    fun setCompletionAlerts(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_COMPLETION_ALERTS, enabled).apply()
    }

    private companion object {
        const val PREFERENCES = "bram-notifications-v1"
        const val KEY_COMPLETION_ALERTS = "completionAlerts"
    }
}
