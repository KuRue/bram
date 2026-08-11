package io.github.kurue.bram.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.kurue.bram.core.domain.PermissionMode
import io.github.kurue.bram.core.domain.ToolApprovalDecision
import io.github.kurue.bram.core.domain.ToolApprovalGate
import io.github.kurue.bram.core.domain.ToolDefinition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

/**
 * The permission tokens a tool can ask for, and which Android runtime permissions they map to.
 *
 * A token is what a [ToolDefinition.requiredPermissions] names; the Android permission is an
 * implementation detail of this app. Tokens with no mapping ([TOKEN_INTERNET],
 * [TOKEN_PRIVATE_STORAGE]) are never requested at runtime — they describe what the tool touches
 * for the approval card, and the gate treats them as granted.
 */
object RuntimePermissions {
    const val TOKEN_INTERNET = "internet"
    const val TOKEN_PRIVATE_STORAGE = "private_storage"
    const val TOKEN_CLIPBOARD = "clipboard"
    const val TOKEN_NOTIFICATIONS = "notifications"
    const val TOKEN_CONTACTS = "contacts"
    const val TOKEN_CALENDAR = "calendar"
    const val TOKEN_TERMUX = "termux"

    /** Token to the Android runtime permission it needs, for tokens that need one. */
    val androidPermissions: Map<String, String> = mapOf(
        TOKEN_NOTIFICATIONS to Manifest.permission.POST_NOTIFICATIONS,
        TOKEN_CONTACTS to Manifest.permission.READ_CONTACTS,
        TOKEN_CALENDAR to Manifest.permission.READ_CALENDAR,
        TOKEN_TERMUX to TERMUX_RUN_COMMAND_PERMISSION,
    )

    /** The permission Termux declares for third-party apps that run commands in its context. */
    const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"

    fun androidPermission(token: String): String? = androidPermissions[token]

    /** Human words for the system permission dialog's description, keyed by token. */
    val labels: Map<String, String> = mapOf(
        TOKEN_NOTIFICATIONS to "post notifications",
        TOKEN_CONTACTS to "read your contacts",
        TOKEN_CALENDAR to "read your calendar",
        TOKEN_TERMUX to "run commands in Termux",
    )

    fun label(token: String): String = labels[token] ?: token
}

/**
 * Suspends while an Activity shows the system permission dialog and answers.
 *
 * The gate runs on the application scope and must not touch the UI; the broker is the seam between
 * them. A tool call that was allowed by the gate and still needs a runtime permission asks through
 * the broker: the pending permission is published to the state flow, the Activity picks it up,
 * launches the system dialog, and calls [resolve] with the outcome.
 */
class RuntimePermissionBroker(context: Context) {
    private val appContext = context.applicationContext

    private val mutablePending = MutableStateFlow<String?>(null)
    private var pendingAnswer: CompletableDeferred<Boolean>? = null

    /** The Android permission the Activity should ask for right now, or null when nothing is pending. */
    val pendingPermission: StateFlow<String?> = mutablePending.asStateFlow()

    fun granted(androidPermission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, androidPermission) == PackageManager.PERMISSION_GRANTED

    /**
     * Requests the Android permissions for the given tokens that are not already granted.
     * Returns the tokens still missing after the user's answer. Asking several at once is
     * pointless — a dialog shows one permission at a time and the user is deciding one thing — so
     * a set is asked one after another, each answer fed back before the next question.
     */
    suspend fun requestMissing(tokens: Set<String>, timeoutMillis: Long): Set<String> {
        var missing = tokens.filterTo(mutableSetOf()) { token ->
            val android = RuntimePermissions.androidPermission(token)
            android != null && !granted(android)
        }
        for (token in missing.toList()) {
            val android = RuntimePermissions.androidPermission(token) ?: continue
            val granted = ask(android, timeoutMillis)
            if (granted) missing.remove(token)
        }
        return missing
    }

    private suspend fun ask(androidPermission: String, timeoutMillis: Long): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pendingAnswer = answer
        mutablePending.value = androidPermission
        return try {
            withTimeout(timeoutMillis) { answer.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // Silence is a denial, like the approval gate's own timeout.
            false
        } finally {
            pendingAnswer = null
            mutablePending.value = null
        }
    }

    /** The Activity calls this when the system permission dialog is answered. */
    fun resolve(granted: Boolean) {
        pendingAnswer?.complete(granted)
    }
}

/**
 * Wraps the approval gate so an allowed call that still needs a runtime permission asks for it
 * before running, and is denied if the user refuses.
 *
 * The wrapper sits outside the gate because the gate is UI-free by design: asking for a system
 * permission is a UI act and belongs to the app layer, not to a decision about trust. Refusal
 * comes back as [ToolApprovalDecision.DENY], so the model sees an ordinary `permission_denied`
 * tool result and can say what happened rather than the run ending.
 */
class PermissionAwareApprovalGate(
    private val delegate: InteractiveApprovalGate,
    private val broker: RuntimePermissionBroker,
    private val timeoutMillis: Long = 10 * 60 * 1_000,
) : ToolApprovalGate {
    /** The pending approval card, from the wrapped gate. */
    val pending: StateFlow<PendingToolApproval?> get() = delegate.pending

    /** Binds the wrapped gate to the active conversation's permission mode. */
    fun setMode(mode: PermissionMode) {
        delegate.setMode(mode)
    }

    /** Binds the wrapped gate to whether the UI is reachable. */
    fun setAttended(value: Boolean) {
        delegate.setAttended(value)
    }

    override suspend fun decide(
        tool: ToolDefinition,
        argumentsJson: String,
        recovered: Boolean,
        untrustedContext: Boolean,
    ): ToolApprovalDecision {
        val decision = delegate.decide(tool, argumentsJson, recovered, untrustedContext)
        if (decision == ToolApprovalDecision.DENY) return decision
        val stillMissing = broker.requestMissing(tool.requiredPermissions, timeoutMillis)
        return if (stillMissing.isEmpty()) decision else ToolApprovalDecision.DENY
    }
}
