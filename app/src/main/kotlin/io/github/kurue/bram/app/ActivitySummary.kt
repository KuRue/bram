package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.AgentActivity

/**
 * One line that stands for a turn's reasoning and tool steps when there are several.
 *
 * The transcript shows a single expandable summary for a multi-step turn (several thoughts, tool
 * calls, or both) so a turn that searches, reads, and writes does not bury the answer under a
 * stack of step rows. The individual steps remain available once expanded.
 */
internal fun summariseActivity(activity: List<AgentActivity>): String {
    val thinking = activity.filterIsInstance<AgentActivity.Thinking>()
    val tools = activity.filterIsInstance<AgentActivity.ToolInvocation>()
    val parts = ArrayList<String>()
    if (thinking.isNotEmpty()) {
        val seconds = thinking.sumOf { it.durationMillis }.coerceAtLeast(0) / 1000
        val inFlight = thinking.any { it.inProgress }
        parts += buildString {
            append("Thought")
            if (seconds > 0) append(" ${seconds}s")
            if (thinking.size > 1) append(" (${thinking.size}x)")
            if (inFlight) append("…")
        }
    }
    if (tools.isNotEmpty()) {
        val inFlight = tools.any { it.result == null }
        val failed = tools.count { it.failed }
        parts += buildString {
            append("${tools.size} tool")
            if (tools.size != 1) append("s")
            if (failed > 0) append(", $failed failed")
            if (inFlight) append("…")
        }
    }
    return parts.joinToString(" · ")
}
