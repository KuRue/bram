package io.github.kurue.bram.app

import android.content.Context
import io.github.kurue.bram.core.domain.ToolApprovalDecision
import io.github.kurue.bram.core.domain.ToolApprovalGate
import io.github.kurue.bram.core.domain.ToolDefinition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** A tool call waiting on the user, and the answer it is waiting for. */
data class PendingToolApproval(
    val toolName: String,
    val description: String,
    val argumentsJson: String,
    /** What the tool says it needs. Empty for a tool that only reads. */
    val requiredPermissions: Set<String>,
    val readOnly: Boolean,
    /** What an "always allow" would actually be granting, in words. */
    val scopeLabel: String,
    /** Read out of unmarked text rather than marked as a call by the model's format. */
    val recovered: Boolean = false,
    private val answer: CompletableDeferred<ToolApprovalDecision>,
) {
    fun resolve(decision: ToolApprovalDecision) {
        answer.complete(decision)
    }
}

/** The remembered allowances, behind an interface so the gate is testable without a Context. */
interface ToolPermissions {
    fun alwaysAllowed(): Set<String>
    fun allowAlways(toolName: String)
    fun withdraw(toolName: String)
}

/**
 * Remembers the tools the user has allowed for good.
 *
 * Deliberately only stores allowances. A denial is a decision about one call in one moment, and
 * remembering it would quietly turn a "not now" into a tool the model can never use again without
 * the user knowing why.
 */
class ToolPermissionStore(context: Context) : ToolPermissions {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun alwaysAllowed(): Set<String> = preferences.getStringSet(KEY_ALLOWED, emptySet()).orEmpty()

    override fun allowAlways(toolName: String) {
        preferences.edit().putStringSet(KEY_ALLOWED, alwaysAllowed() + toolName).apply()
    }

    override fun withdraw(toolName: String) {
        preferences.edit().putStringSet(KEY_ALLOWED, alwaysAllowed() - toolName).apply()
    }

    private companion object {
        const val PREFERENCES = "bram-tool-permissions-v1"
        const val KEY_ALLOWED = "alwaysAllowed"
    }
}

/**
 * Asks the user before a tool with effects runs.
 *
 * Replaces the stub that answered on the user's behalf — it allowed read-only tools and denied
 * everything else, which is why Bram had exactly one usable tool.
 *
 * A read-only tool that needs no permissions still runs without asking. Approving every reading of
 * the battery level teaches people to approve without reading, which is worse than not asking.
 */
class InteractiveApprovalGate(
    private val permissions: ToolPermissions,
    /**
     * How long to wait for an answer before treating silence as refusal. An unattended run — a
     * scheduled task, or the app in the background — has nobody to answer it, and hanging forever
     * holds the foreground service open with nothing happening.
     */
    private val timeoutMillis: Long = 2 * 60 * 1_000,
) : ToolApprovalGate {

    private val mutablePending = MutableStateFlow<PendingToolApproval?>(null)

    companion object {
        /**
         * The key an allowance is remembered under: the tool, plus the values of the arguments it
         * says identify its target.
         *
         * An argument the call omits is recorded as absent rather than skipped, so a later call
         * that supplies one cannot slip through an allowance granted without it.
         */
        fun approvalScope(tool: ToolDefinition, argumentsJson: String): String {
            if (tool.approvalScopeKeys.isEmpty()) return tool.name
            val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            val targets = tool.approvalScopeKeys.sorted().joinToString(",") { key ->
                "$key=" + (arguments?.opt(key)?.toString() ?: "<absent>")
            }
            return "${tool.name}($targets)"
        }

        /** The same thing said to a person rather than to a preferences file. */
        fun scopeLabel(tool: ToolDefinition, argumentsJson: String): String {
            if (tool.approvalScopeKeys.isEmpty()) return "every use of ${tool.name}"
            val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            val targets = tool.approvalScopeKeys.sorted().joinToString(", ") { key ->
                "$key = " + (arguments?.opt(key)?.toString() ?: "(not set)")
            }
            return "${tool.name} with $targets"
        }
    }
    val pending: StateFlow<PendingToolApproval?> = mutablePending.asStateFlow()

    override suspend fun decide(
        tool: ToolDefinition,
        argumentsJson: String,
        recovered: Boolean,
    ): ToolApprovalDecision {
        // A recovered call skips both shortcuts. It is text read as an intent, and the text could
        // have come from anywhere the model has read — including a tool's own output.
        if (!recovered && tool.readOnly && tool.requiredPermissions.isEmpty()) {
            return ToolApprovalDecision.ALLOW_ONCE
        }
        val scope = approvalScope(tool, argumentsJson)
        if (!recovered && scope in permissions.alwaysAllowed()) return ToolApprovalDecision.ALLOW_ONCE

        val answer = CompletableDeferred<ToolApprovalDecision>()
        val request = PendingToolApproval(
            toolName = tool.name,
            description = tool.description,
            argumentsJson = argumentsJson,
            requiredPermissions = tool.requiredPermissions,
            readOnly = tool.readOnly,
            scopeLabel = scopeLabel(tool, argumentsJson),
            recovered = recovered,
            answer = answer,
        )
        mutablePending.value = request
        return try {
            val decision = withTimeout(timeoutMillis) { answer.await() }
            if (decision == ToolApprovalDecision.ALLOW_ALWAYS && !recovered) {
                permissions.allowAlways(scope)
            }
            decision
        } catch (_: TimeoutCancellationException) {
            ToolApprovalDecision.DENY
        } finally {
            // Cleared whatever the outcome, including cancellation of the run, so a stale card
            // cannot outlive the call it belonged to.
            mutablePending.value = null
        }
    }
}
