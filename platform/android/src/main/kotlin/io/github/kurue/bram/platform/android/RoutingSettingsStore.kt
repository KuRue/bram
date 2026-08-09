package io.github.kurue.bram.platform.android

import android.content.Context
import io.github.kurue.bram.core.domain.PrivacyClass
import io.github.kurue.bram.core.domain.RoutingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Global routing policy: which routing mode turns use by default, and what privacy class a brand
 * new conversation starts with. Per-conversation privacy lives on the conversation itself in
 * [ConversationStore]; this store is only the app-wide defaults.
 */
class RoutingSettingsStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    suspend fun routingMode(): RoutingMode = withContext(Dispatchers.IO) {
        RoutingMode.fromWire(preferences.getString(ROUTING_MODE_KEY, null))
    }

    suspend fun setRoutingMode(mode: RoutingMode) = withContext(Dispatchers.IO) {
        preferences.edit().putString(ROUTING_MODE_KEY, mode.wire).apply()
    }

    suspend fun defaultPrivacyClass(): PrivacyClass = withContext(Dispatchers.IO) {
        PrivacyClass.fromWire(preferences.getString(DEFAULT_PRIVACY_KEY, null))
    }

    suspend fun setDefaultPrivacyClass(privacyClass: PrivacyClass) = withContext(Dispatchers.IO) {
        preferences.edit().putString(DEFAULT_PRIVACY_KEY, privacyClass.wire).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "bram-routing-v1"
        const val ROUTING_MODE_KEY = "routingMode"
        const val DEFAULT_PRIVACY_KEY = "defaultPrivacyClass"
    }
}
