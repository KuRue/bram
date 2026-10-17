package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.AgentCatalog
import io.github.kurue.bram.core.domain.AgentDefinition
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import io.github.kurue.bram.core.domain.ToolRegistry
import io.github.kurue.bram.core.domain.TokenUsage
import org.json.JSONObject

/**
 * What a parent asks a child agent to do. The task is the whole assignment; [contextNotes] carries
 * only the references the parent chose to hand over — a child never implicitly receives the parent's
 * transcript, memories, or attachments.
 */
data class ChildRunRequest(
    val definition: AgentDefinition,
    val task: String,
    val contextNotes: String = "",
    /** The parent run's root id, so budgets and journal lineage follow the root, not the child. */
    val parentRootRunId: String?,
)

data class ChildRunResult(
    val succeeded: Boolean,
    /** The bounded answer, already truncated to the delegation budget by the executor. */
    val summary: String,
    /** The conversation holding the child's own transcript, linkable from the parent's. */
    val conversationId: String,
    val usage: TokenUsage? = null,
    val failureReason: String? = null,
)

/** Runs one child agent. Implemented where runtimes and stores live; pure tests fake it. */
interface ChildRunExecutor {
    suspend fun runChild(request: ChildRunRequest): ChildRunResult
}

/**
 * The delegation tool. One tool, not several: the parent names an agent and a task, waits, and gets
 * a bounded summary back — the shape both Open WebUI subagents and Claude Code subagents converge on
 * for a first version.
 *
 * The result is untrusted tool-result content like any other: the parent model reads it, it does not
 * execute. A failed child is a structured failure the parent can report, never a silent loss.
 */
class DelegateTool(
    private val executor: ChildRunExecutor,
    /** The run currently delegating, so child budgets attach to the parent's root. */
    private val rootRunIdProvider: () -> String?,
) : ToolHandler {

    override val definition = ToolDefinition(
        name = TOOL_NAME,
        description = "Delegate a self-contained task to a read-only agent and get its answer back. " +
            "Use for research or review work worth its own focused pass.",
        inputSchemaJson = """
            {"type":"object","properties":{
              "agent":{"type":"string","enum":["research"],"description":"which agent to delegate to"},
              "task":{"type":"string","description":"the complete, self-contained task; the agent sees only this"},
              "context":{"type":"string","description":"optional facts or references the agent needs"}
            },"required":["agent","task"],"additionalProperties":false}
        """.trimIndent().replace("\n", " "),
        readOnly = true,
        // A child is a whole agent run: several tool turns and a generation, not one call. The
        // deadline is the parent-visible bound on that, matching the delegation budget.
        timeoutMillis = DELEGATION_DEADLINE_MILLIS,
    )

    override suspend fun execute(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrElse { error ->
            return failure("arguments were not valid JSON: ${error.message}")
        }
        val agentId = arguments.optString("agent")
        val definition = AgentCatalog.find(agentId)
            ?: return failure("unknown agent '$agentId'; available: research")
        if (definition.id == AgentCatalog.DEFAULT.id) {
            return failure("the default agent cannot be delegated to; name a specialized agent")
        }
        val task = arguments.optString("task").trim()
        if (task.isEmpty()) return failure("the task was empty; give the agent something to do")
        val context = arguments.optString("context").trim()

        val result = executor.runChild(
            ChildRunRequest(
                definition = definition,
                task = task,
                contextNotes = context,
                parentRootRunId = rootRunIdProvider(),
            ),
        )
        return if (result.succeeded) {
            JSONObject()
                .put("status", "completed")
                .put("summary", result.summary)
                .put("transcript", result.conversationId)
                .put("tokensUsed", result.usage?.outputTokens ?: JSONObject.NULL)
                .toString()
        } else {
            failure(result.failureReason ?: "the agent run failed")
        }
    }

    private fun failure(reason: String): String =
        JSONObject().put("status", "failed").put("reason", reason).toString()

    companion object {
        const val TOOL_NAME = "delegate"
        const val DELEGATION_DEADLINE_MILLIS = 10 * 60_000L
    }
}

/**
 * Derives the child's tool surface from the parent's registry and the agent definition, at the
 * executor rather than in prompts. A child can never see more than its parent: the delegate tool
 * itself is removed (one nesting level — a child cannot spawn children), the allowlist narrows, and
 * only read-only tools pass, whatever the definition or the parent asked for.
 */
object DelegationPolicy {
    fun childHandlers(parentRegistry: ToolRegistry, definition: AgentDefinition): List<ToolHandler> =
        parentRegistry.definitions()
            .filter { it.name != DelegateTool.TOOL_NAME }
            .filter { definition.toolAllowlist.isEmpty() || it.name in definition.toolAllowlist }
            .filter { it.readOnly }
            .mapNotNull { parentRegistry.find(it.name) }
            .filter { it.definition.readOnly }
}
