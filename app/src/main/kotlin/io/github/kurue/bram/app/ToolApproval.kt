package io.github.kurue.bram.app

import android.content.Context
import io.github.kurue.bram.core.domain.PermissionMode
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
     *
     * Ten minutes rather than two: a local model on a phone generates at a few tokens a second, so
     * a turn that calls a tool routinely takes minutes, and a window shorter than the work makes
     * silent refusal the usual outcome rather than the exceptional one. This was found by a turn
     * timing out before it could be answered.
     */
    private val timeoutMillis: Long = 10 * 60 * 1_000,
) : ToolApprovalGate {

    private val mutablePending = MutableStateFlow<PendingToolApproval?>(null)

    /**
     * The active conversation's mode. Set by the ViewModel when a run starts and when the user
     * changes it, since the gate is a singleton but the choice is per conversation. Runs are
     * serial, so a single mutable value is enough.
     */
    @Volatile
    private var mode: PermissionMode = PermissionMode.AUTO

    /**
     * Whether someone is around to answer an approval prompt. The card lives in the activity's UI,
     * so a backgrounded or scheduled run cannot reach it: rather than hold the run for the full
     * timeout per call, [decide] refuses at once and the model gets a denial it can react to.
     */
    @Volatile
    private var attended: Boolean = true

    fun setMode(mode: PermissionMode) {
        this.mode = mode
    }

    fun setAttended(value: Boolean) {
        attended = value
    }

    companion object {
        /**
         * The key an allowance is remembered under: the tool itself.
         *
         * It used to include the arguments a tool named as its target, so allowing one URL did not
         * allow the next. That was safer and unusable: a person who allows a fetch means "you may
         * fetch", not "you may fetch this one address", and re-approving every page made the grant
         * worthless. Narrowing again should come with a way to see and revoke what was granted,
         * rather than by making every grant too small to be worth making.
         */
        fun approvalScope(tool: ToolDefinition, argumentsJson: String): String = tool.name

        /** The same thing said to a person rather than to a preferences file. */
        fun scopeLabel(tool: ToolDefinition, argumentsJson: String): String = "every use of ${tool.name}"
    }
    val pending: StateFlow<PendingToolApproval?> = mutablePending.asStateFlow()

    override suspend fun decide(
        tool: ToolDefinition,
        argumentsJson: String,
        recovered: Boolean,
    ): ToolApprovalDecision {
        val current = mode
        // BYPASS runs what the model asked for without asking again — but not a call recovered from
        // unmarked text. That is text read as an intent, and `web_fetch` puts pages Bram did not
        // write into the context, so the text could have come from anywhere. Taking responsibility
        // for a run is not the same as vouching for every page it reads, which is the one case the
        // fences on recovered calls exist for.
        if (current == PermissionMode.BYPASS && !recovered) return ToolApprovalDecision.ALLOW_ONCE

        val scope = approvalScope(tool, argumentsJson)
        // An explicit "allow always" grant is stronger than the mode, so it holds even in MANUAL —
        // otherwise withdrawing trust would require switching modes rather than using the list.
        if (!recovered && scope in permissions.alwaysAllowed()) return ToolApprovalDecision.ALLOW_ONCE
        // AUTO lets a read-only tool that needs no permission run silently. MANUAL asks about it,
        // which is the point of asking about everything.
        if (current == PermissionMode.AUTO && !recovered && tool.readOnly && tool.requiredPermissions.isEmpty()) {
            return ToolApprovalDecision.ALLOW_ONCE
        }

        // No one is watching to answer: a backgrounded or scheduled run cannot reach the approval
        // card, so waiting the full timeout only holds the run open. Deny at once — the model gets
        // a refusal it can react to instead of a multi-minute hang per call.
        if (!attended) return ToolApprovalDecision.DENY

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
