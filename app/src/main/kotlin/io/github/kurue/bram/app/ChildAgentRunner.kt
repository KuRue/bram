package io.github.kurue.bram.app

import io.github.kurue.bram.core.agent.DefaultAgentOrchestrator
import io.github.kurue.bram.core.agent.DelegationPolicy
import io.github.kurue.bram.core.agent.ChildRunExecutor
import io.github.kurue.bram.core.agent.ChildRunRequest
import io.github.kurue.bram.core.agent.ChildRunResult
import io.github.kurue.bram.core.agent.StaticToolRegistry
import io.github.kurue.bram.core.agent.ReadOnlyApprovalGate
import io.github.kurue.bram.core.domain.AgentEvent
import io.github.kurue.bram.core.domain.AgentRunRequest

import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.RunJournal
import io.github.kurue.bram.core.domain.RunOrigin
import io.github.kurue.bram.core.domain.MemoryStore
import io.github.kurue.bram.core.domain.TokenUsage
import io.github.kurue.bram.platform.android.ConversationStore
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.collect

/**
 * What a delegation needs to know about the run that is delegating. Set by the ViewModel around
 * every parent run — chat turns and tasks alike — because only it knows which runtime the turn
 * chose and which root id its journal rows share. Read by [ChildAgentRunner] when the delegate
 * tool fires mid-run.
 *
 * Volatile slots rather than parameters because the tool handler has no way to receive run
 * context. The value is advisory for lineage and budgeting; the child's own run is correct
 * whatever these hold. Chat turns and queued tasks do not overlap in practice (one run at a
 * time on each path); if they ever do, only the per-root child count could be miscounted.
 */
class DelegationAnchor {
    @Volatile
    var rootRunId: String? = null

    @Volatile
    var runtime: ModelRuntime? = null
}

/**
 * Runs delegated child agents for the delegate tool, first slice: foreground, serial with the
 * parent on the same runtime, read-only, at most two per root run.
 *
 * A child gets its own conversation — that is the "child log" the parent's transcript row points
 * at — built from the task and the context the parent explicitly handed over, never an implicit
 * copy of the parent's transcript. Its tool surface is derived by [DelegationPolicy] and its gate
 * is read-only, so a child cannot side-effect even if a definition asked it to.
 */
class ChildAgentRunner(
    private val anchor: DelegationAnchor,
    /** Read per child run, so a delegated agent sees the registry as it is now, not as it was built. */
    private val parentRegistry: () -> io.github.kurue.bram.core.domain.ToolRegistry,
    private val memoryStore: MemoryStore,
    private val journal: RunJournal,
    private val conversationStore: ConversationStore,
) : ChildRunExecutor {

    private val childrenPerRoot = ConcurrentHashMap<String, AtomicInteger>()

    override suspend fun runChild(request: ChildRunRequest): ChildRunResult {
        val root = request.parentRootRunId ?: "unattached"
        if (childrenPerRoot.size > TRACKED_ROOT_LIMIT) childrenPerRoot.clear()
        val count = childrenPerRoot.getOrPut(root) { AtomicInteger() }
        if (count.incrementAndGet() > MAX_CHILDREN_PER_ROOT) {
            count.decrementAndGet()
            return failed(
                "This conversation has already run its limit of $MAX_CHILDREN_PER_ROOT delegated agents.",
            )
        }
        val runtime = anchor.runtime
            ?: return failed("No runtime is available to run the agent on right now.")

        val conversationId = ConversationId("agent-${request.definition.id}-${UUID.randomUUID()}")
        val task = buildString {
            appendLine(request.task.trim())
            if (request.contextNotes.isNotBlank()) {
                appendLine()
                appendLine("Context from the caller:")
                appendLine(request.contextNotes.trim())
            }
        }
        val childMessages = listOf(
            ConversationMessage(role = MessageRole.USER, content = task),
        )
        val orchestrator = DefaultAgentOrchestrator(
            contextWindowManager = io.github.kurue.bram.core.agent.ContextWindowManager(),
            memoryStore = memoryStore,
            toolRegistry = StaticToolRegistry(
                DelegationPolicy.childHandlers(parentRegistry(), request.definition),
            ),
            approvalGate = ReadOnlyApprovalGate(),
            journal = journal,
        )
        var reply: String? = null
        var failure: String? = null
        var usage: TokenUsage? = null
        try {
            orchestrator.run(
                AgentRunRequest(
                    conversationId = conversationId,
                    messages = childMessages,
                    identity = BramDefaults.IDENTITY,
                    maxOutputTokens = request.definition.maxOutputTokens,
                    profileInstructions = request.definition.instructions,
                    maxToolTurns = request.definition.maxToolTurns,
                    rootRunId = request.parentRootRunId,
                    origin = RunOrigin.AGENT,
                ),
                runtime,
            ).collect { event ->
                when (event) {
                    is AgentEvent.Completed -> reply = event.message.content
                    is AgentEvent.Failed -> failure = event.message
                    is AgentEvent.Usage -> usage = event.usage
                    else -> Unit
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: error::class.java.simpleName
        }

        val answer = reply?.trim()?.takeIf(String::isNotEmpty)
        // The child's transcript is worth keeping even on failure: it says what was tried.
        runCatching {
            conversationStore.save(
                conversationId,
                childMessages + listOfNotNull(
                    answer?.let {
                        ConversationMessage(role = MessageRole.ASSISTANT, content = it)
                    },
                    failure?.let {
                        ConversationMessage(
                            role = MessageRole.ASSISTANT,
                            content = "[agent run failed: $it]",
                        )
                    },
                ),
            )
        }
        return if (answer != null) {
            val summary = if (answer.length > MAX_SUMMARY_CHARS) {
                answer.take(MAX_SUMMARY_CHARS) + "\n…[truncated; full reply in the transcript]"
            } else {
                answer
            }
            ChildRunResult(
                succeeded = true,
                summary = summary,
                conversationId = conversationId.value,
                usage = usage,
            )
        } else {
            failed(failure ?: "the agent produced no reply", conversationId.value)
        }
    }

    private fun failed(reason: String, conversationId: String = "") = ChildRunResult(
        succeeded = false,
        summary = "",
        conversationId = conversationId,
        failureReason = reason,
    )

    companion object {
        const val MAX_CHILDREN_PER_ROOT = 2
        const val MAX_SUMMARY_CHARS = 4_000

        /** Roots are unique per run; the cap only stops an unbounded map over a very long session. */
        const val TRACKED_ROOT_LIMIT = 128
    }
}
