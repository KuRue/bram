package io.github.kurue.bram.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.kurue.bram.core.domain.ToolApprovalDecision

/**
 * Answers a tool-approval request from the notification's Allow/Deny actions.
 *
 * The request itself lives in the app process on the approval gate, and runs are serial, so a
 * single pending request at a time. A stale action — the request was already answered from the
 * app card, or the run was stopped — finds nothing matching its request id and resolves nothing;
 * the notification is taken down either way.
 */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AgentTaskService.ACTION_APPROVAL_RESOLVE) return
        val requestId = intent.getStringExtra(AgentTaskService.EXTRA_APPROVAL_REQUEST_ID) ?: return
        val decisionName = intent.getStringExtra(AgentTaskService.EXTRA_APPROVAL_DECISION) ?: return
        val decision = runCatching { ToolApprovalDecision.valueOf(decisionName) }.getOrNull() ?: return
        val container = (context.applicationContext as BramApplication).container
        container.approvalGate.pending.value
            ?.takeIf { it.id == requestId }
            ?.resolve(decision)
        AgentTaskService.cancelApprovalNotification(context, requestId)
    }
}
