package io.github.kurue.bram.app

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kurue.bram.core.domain.AcceleratorAgreement
import io.github.kurue.bram.core.domain.AgentActivity
import io.github.kurue.bram.core.domain.AgentEvent
import io.github.kurue.bram.core.domain.AgentRunRequest
import io.github.kurue.bram.core.domain.Automation
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.Cron
import io.github.kurue.bram.core.domain.findOffloadBoundary
import io.github.kurue.bram.core.domain.BackendMeasurement
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.ConversationSummary
import io.github.kurue.bram.core.domain.DeviceProfile
import io.github.kurue.bram.core.domain.GenerationMetrics
import io.github.kurue.bram.core.domain.LiteRtBackend
import io.github.kurue.bram.core.domain.LITE_RT_CONTEXT_TOKENS
import io.github.kurue.bram.core.domain.LiteRtModelRecord
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.McpServer
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.MemoryKind
import io.github.kurue.bram.core.domain.MemoryPrompt
import io.github.kurue.bram.core.domain.MemoryRecord
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.core.domain.PermissionMode
import io.github.kurue.bram.core.domain.ReasoningFormat
import io.github.kurue.bram.core.domain.RunJournalEntry
import io.github.kurue.bram.core.domain.ModelProfile
import io.github.kurue.bram.core.domain.SamplerSettings
import io.github.kurue.bram.core.domain.SkillActionOutcome
import io.github.kurue.bram.core.domain.SkillImportOutcome
import io.github.kurue.bram.core.domain.SkillPackage
import io.github.kurue.bram.core.domain.SkillPrompt
import io.github.kurue.bram.core.domain.ToolApprovalDecision
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.ModelCapability
import io.github.kurue.bram.core.domain.PrivacyClass
import io.github.kurue.bram.core.domain.RemoteApiKind
import io.github.kurue.bram.core.domain.RemoteEndpoint
import io.github.kurue.bram.core.domain.RoutingMode
import io.github.kurue.bram.core.domain.RoutingPoolAssignments
import io.github.kurue.bram.core.domain.RoutingPoolSlot
import io.github.kurue.bram.core.domain.RoutingRequest
import io.github.kurue.bram.core.domain.RoutingWorkload
import io.github.kurue.bram.core.domain.RoutingWorkloadClassifier
import io.github.kurue.bram.core.domain.TokenUsage
import io.github.kurue.bram.core.agent.RuleBasedModelRouter
import io.github.kurue.bram.platform.android.RoutingSettingsStore
import io.github.kurue.bram.runtime.llamacpp.ModelImportProgress
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Outcome of comparing an accelerator against the CPU reference. [matchesCpu] is the acceptance
 * signal: the accelerator must agree with the CPU on nearly every teacher-forced prediction before
 * it counts as computing correctly. Exact equality is deliberately not required.
 */
/**
 * Auto-configure as it happens.
 *
 * Measuring three backends takes minutes, so the run has to be watchable rather than a frozen
 * screen: [step] of [total] says how far in, and [results] fills in as each one finishes so the
 * comparison is readable before the last one lands.
 */
data class AutoConfigureProgress(
    val profileId: String,
    val modelName: String,
    val step: Int,
    val total: Int,
    val current: String,
    /** Every accelerator candidate, in measurement order, so the dialog can show all of them. */
    val candidates: List<String> = emptyList(),
    val results: List<BackendMeasurement> = emptyList(),
    val finished: Boolean = false,
) {
    val fraction: Float get() = if (total <= 0) 0f else (step.toFloat() / total).coerceIn(0f, 1f)
}

data class AcceleratorReport(
    val deviceName: String,
    val matchesCpu: Boolean,
    val agreement: Double,
    val cpuTokens: List<Int>,
    val acceleratorTokens: List<Int>,
    val cpuMillis: Long,
    val acceleratorMillis: Long,
    val cpuText: String,
    val acceleratorText: String,
    val detail: String,
) {
    val speedup: Double
        get() = if (acceleratorMillis > 0) cpuMillis.toDouble() / acceleratorMillis.toDouble() else 0.0
}

/**
 * Result of narrowing down how many layers can be offloaded before output stops matching CPU.
 * [lastGoodLayers] is the largest offload that still reproduced the reference exactly.
 */
data class AcceleratorBisection(
    val deviceName: String,
    val totalLayers: Int,
    val lastGoodLayers: Int,
    val firstBadLayers: Int?,
    val probes: List<AcceleratorProbe>,
) {
    val detail: String
        get() = when {
            firstBadLayers == null -> "All $totalLayers layers agreed with the CPU reference."
            lastGoodLayers == 0 -> "Even one offloaded layer disagrees, so the failure is in an " +
                "operation every layer uses."
            else -> "Predictions agree up to $lastGoodLayers offloaded layers and break at " +
                "$firstBadLayers."
        }
}

data class AcceleratorProbe(
    val gpuLayers: Int,
    val agreement: Double,
    val millis: Long,
    val text: String,
) {
    /**
     * fp16 accelerators legitimately disagree with an fp32 CPU on a few near-tie predictions, so
     * exact equality is too strict. Near-total agreement means the backend computes correctly.
     */
    val usable: Boolean get() = AcceleratorAgreement.isUsable(agreement)
}

/** Accelerator families Bram can validate against the CPU reference. */
enum class AcceleratorTarget(val label: String, val devicePrefix: String) {
    VULKAN("Vulkan", "Vulkan"),
    // ggml names the OpenCL device GPUOpenCL, which is what the runtime reports back.
    OPENCL("OpenCL", "GPUOpenCL"),
    HEXAGON("NPU", "HTP"),
}

/**
 * Where a model actually runs. Separate from [AcceleratorTarget] because CPU is a real choice for
 * running, not something to validate against itself.
 */
enum class RuntimeBackend(val label: String, val devicePrefix: String) {
    CPU("CPU", ""),
    VULKAN("Vulkan", "Vulkan"),
    OPENCL("OpenCL", "GPUOpenCL"),
    HEXAGON("NPU", "HTP"),
    ;

    val offloadsToAccelerator: Boolean get() = this != CPU

    companion object {
        /** Persisted as the enum name; anything unrecognised falls back to CPU. */
        fun fromId(id: String?): RuntimeBackend =
            entries.firstOrNull { it.name == id } ?: CPU
    }
}

/** A partial reply, split the way the finished one will be. */
internal data class StreamingReply(
    /** What the model has said so far, with reasoning markup removed. */
    val visibleText: String,
    /** Reasoning blocks the model has already closed, oldest first. */
    val closedReasoning: List<String>,
    /** The block still being written, if the model is reasoning right now. */
    val openReasoning: String?,
)

/**
 * Splits a partial reply into visible text and reasoning.
 *
 * The finished reply is split by the runtime's own structured parser, which needs the whole thing.
 * Until it arrives this does the same job on the stream, so reasoning folds into a collapsed row as
 * it is produced rather than sitting in the transcript as raw markup until the turn ends.
 *
 * [format] comes from the runtime, which knows what the loaded chat template actually uses. The
 * tags vary — `<think>`, `[THINK]`, `<|channel|>analysis<|message|>` and `<mm:think>` are all in
 * use, and some formats close with more than one — and whether the prompt already opened the block
 * varies with the format too. Both were previously assumed, which was right for the Qwen and
 * DeepSeek families and wrong for the rest.
 *
 * A model that reasons in unmarked prose is indistinguishable from one that is answering, so
 * nothing is claimed about it. So is a format that reports no tags, which is why an unusable
 * [format] leaves the text alone rather than guessing.
 */
internal fun streamingReply(
    text: String,
    format: ReasoningFormat = ReasoningFormat(),
): StreamingReply {
    // The runtime usually reports the loaded format's exact tags. Some formats (LFM2.5 among them)
    // report no opening tag while the model still emits the standard close, so a reply plainly using
    // <think></think> is folded from those markers instead of shown raw.
    val effective = if (format.isUsable) format else standardThinkFallback(text)
    if (!effective.isUsable) return StreamingReply(text, emptyList(), null)
    val startTag = effective.startTag
    val hasStartTag = startTag.isNotEmpty()
    // A model that writes the opening marker itself is authoritative: read from the marker wherever
    // it is. Only when there is none does the prompt's own opening (startsOpen) apply.
    val startsInReasoning = effective.startsOpen && (!hasStartTag || !text.contains(startTag))
    val markerPresent = hasStartTag && text.contains(startTag)
    if (!startsInReasoning && !markerPresent) return StreamingReply(text, emptyList(), null)

    val visible = StringBuilder()
    val closed = mutableListOf<String>()
    var open: String? = null
    var cursor = 0
    var inReasoning = startsInReasoning
    while (cursor < text.length) {
        if (inReasoning) {
            val end = effective.firstEndTagFrom(text, cursor)
            if (end == null) {
                // The block is still being written: everything that follows is reasoning, and there
                // is no answer yet.
                open = text.substring(cursor)
                break
            }
            closed += text.substring(cursor, end.first)
            cursor = end.first + end.second.length
            inReasoning = false
        } else {
            if (!hasStartTag) {
                visible.append(text, cursor, text.length)
                break
            }
            val next = text.indexOf(startTag, cursor)
            if (next < 0) {
                visible.append(text, cursor, text.length)
                break
            }
            visible.append(text, cursor, next)
            cursor = next + startTag.length
            inReasoning = true
        }
    }
    return StreamingReply(visible.toString(), closed, open)
}

/**
 * The fallback when the runtime did not describe the format but the reply is clearly using the
 * de-facto reasoning markers.
 *
 * Returns an unusable format when neither marker is present, so ordinary text is left alone. An
 * opening marker in the text is authoritative; without one the block is treated as opened by the
 * prompt, which is how the reasoning formats that omit the opening tag actually behave.
 */
private fun standardThinkFallback(text: String): ReasoningFormat {
    val hasOpen = text.contains("<think>")
    val hasClose = text.contains("</think>")
    if (!hasOpen && !hasClose) return ReasoningFormat()
    return ReasoningFormat(
        startTag = if (hasOpen) "<think>" else "",
        endTags = listOf("</think>"),
        startsOpen = !hasOpen,
    )
}

/**
 * The earliest closing tag at or after [from], with the tag that matched.
 *
 * A format can close a reasoning block several ways — one lists `</think>` and `<tool_call>`
 * together — so the block ends at whichever comes first, not at whichever was listed first.
 */
private fun ReasoningFormat.firstEndTagFrom(text: String, from: Int): Pair<Int, String>? =
    endTags.mapNotNull { tag ->
        text.indexOf(tag, from).takeIf { it >= 0 }?.let { it to tag }
    }.minByOrNull { it.first }

data class AppUiState(
    val deviceProfile: DeviceProfile? = null,
    val localModels: List<LocalModelRecord> = emptyList(),
    /** Imported LiteRT packages (`.litertlm`), catalogued separately from GGUFs. */
    val litertlmModels: List<LiteRtModelRecord> = emptyList(),
    val profiles: List<ModelProfile> = emptyList(),
    /** A tool call waiting on the user. The run is blocked until this is answered. */
    val pendingApproval: PendingToolApproval? = null,
    /** Tool allowances the user granted for good, so they can be seen and taken back. */
    val alwaysAllowedTools: List<String> = emptyList(),
    /** The profile a load uses. Every model has at least a default one. */
    val activeProfileId: String? = null,
    val endpoints: List<RemoteEndpoint> = emptyList(),
    val selectedRuntimeId: String? = null,
    val loadedModelId: String? = null,
    val cpuValidated: Boolean = false,
    /** The LiteRT package the resident engine holds, or null when none is loaded. */
    val litertlmLoadedId: String? = null,
    /** What the resident LiteRT engine loaded onto. */
    val litertlmLoadedBackend: LiteRtBackend? = null,
    val isImporting: Boolean = false,
    val importProgress: ModelImportProgress? = null,
    val isLoadingModel: Boolean = false,
    val modelLoadDetail: String? = null,
    val isLoadingLiteRt: Boolean = false,
    val liteRtLoadDetail: String? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val lastUsage: TokenUsage? = null,
    val lastMetrics: GenerationMetrics? = null,
    val modelStorageBytes: Long = 0,
    val conversations: List<ConversationSummary> = emptyList(),
    val activeConversationId: String? = null,
    /** How this conversation asks before running a tool. Per conversation, defaults to AUTO. */
    val permissionMode: PermissionMode = PermissionMode.AUTO,
    /** How this conversation's content may be processed. Per conversation; see [PrivacyClass]. */
    val privacyClass: PrivacyClass = PrivacyClass.STANDARD,
    /** How every turn chooses between local and remote processing. Global. */
    val routingMode: RoutingMode = RoutingMode.AUTO,
    /** The small set of profiles/endpoints automatic routing is allowed to choose between. */
    val routingPool: RoutingPoolAssignments = RoutingPoolAssignments(),
    /** The privacy class a brand new conversation starts with. Global. */
    val defaultPrivacyClass: PrivacyClass = PrivacyClass.STANDARD,
    /** What the most recent routing decision picked, for the session header and notifications. */
    val lastRoutedRuntimeLabel: String? = null,
    /** Tokens fed to the model on the most recent turn, when the runtime reported the count. */
    val lastContextTokens: Int? = null,
    /** Cumulative input tokens across the conversation's turns, for the session stats card. */
    val sessionInputTokens: Int = 0,
    /** Cumulative output tokens the model has generated across the conversation's turns. */
    val sessionOutputTokens: Int = 0,
    /** Backends this build found on the device, CPU always included. */
    val availableBackends: List<RuntimeBackend> = listOf(RuntimeBackend.CPU),
    /** What the currently loaded model is actually running on. */
    val loadedBackend: RuntimeBackend? = null,
    val isValidatingAccelerator: Boolean = false,
    /** Which profile the batch tuning is measuring right now, or null when idle. */
    val batchTuneProfileId: String? = null,
    /** Present while auto-configure runs. The dialog is shown for exactly as long as this is. */
    val autoConfigure: AutoConfigureProgress? = null,
    val acceleratorReport: AcceleratorReport? = null,
    val acceleratorBisection: AcceleratorBisection? = null,
    /** Whether a finished backgrounded turn posts the completion alert. */
    val completionAlertsEnabled: Boolean = false,
    /** One-shot signal to the activity to ask for the notification permission. */
    val requestNotificationPermission: Boolean = false,
    /** An Android runtime permission a tool call needs, to show to the system dialog. */
    val runtimePermissionRequest: String? = null,
    /** The human words for what is being requested, for an explainer under the card. */
    val runtimePermissionLabel: String? = null,
    /** The scheduled-task queue, newest first, with live state as runs progress. */
    val tasks: List<AgentTask> = emptyList(),
    /** The persistent run journal, newest first, for the diagnostics card. */
    val recentRuns: List<RunJournalEntry> = emptyList(),
    /** Configured MCP servers with what the last refresh found, per server. */
    val mcpServers: List<McpServerUi> = emptyList(),
    /** True while every configured server is being asked what tools it has. */
    val mcpRefreshing: Boolean = false,
    /** Imported skill packages, with their active and staged versions. */
    val skills: List<SkillPackage> = emptyList(),
    /** The last skill action's result, shown under the skills section. */
    val skillStatus: String? = null,
    /** Recurring schedules that enqueue a task at their cron time. */
    val automations: List<Automation> = emptyList(),
    /** The last automation action's result, shown under the automations section. */
    val automationStatus: String? = null,
    /** Newest-first remembered facts and instructions, for the memory browser. */
    val memories: List<MemoryRecord> = emptyList(),
    /** Display name of the resident embedding model, or null when recall is keyword-only. */
    val embeddingModelName: String? = null,
) {
    val selectedLocalModel: LocalModelRecord?
        get() = localModels.firstOrNull { it.id.value == selectedRuntimeId }

    val selectedLiteRtModel: LiteRtModelRecord?
        get() = litertlmModels.firstOrNull { it.id.value == selectedRuntimeId }

    val selectedEndpoint: RemoteEndpoint?
        get() = endpoints.firstOrNull { remoteRuntimeId(it.id) == selectedRuntimeId }

    val selectedLocalModelIsLoaded: Boolean
        get() = selectedLocalModel?.id?.value == loadedModelId && cpuValidated

    val selectedLiteRtIsLoaded: Boolean
        get() = selectedLiteRtModel?.id?.value == litertlmLoadedId

    /** The backend a given model will load onto, falling back to CPU when unavailable. */
    fun backendFor(model: LocalModelRecord): RuntimeBackend =
        RuntimeBackend.fromId(profileFor(model).backendId).takeIf { it in availableBackends }
            ?: RuntimeBackend.CPU

    /**
     * The profile a model runs under: the one explicitly active, otherwise its first.
     *
     * Falls back to a profile derived from the record so a model is never unusable because its
     * profile has not been written yet — the store creates one on import, but a caller reading
     * state mid-load should not have to care.
     */
    fun profileFor(model: LocalModelRecord): ModelProfile =
        profiles.firstOrNull { it.id == activeProfileId && it.modelId == model.id }
            ?: profiles.firstOrNull { it.modelId == model.id }
            ?: ModelProfile.defaultFor(model)

    val activeProfile: ModelProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId }

    fun routingTargetLabel(targetId: String?): String? = when {
        targetId == null -> null
        targetId.startsWith(REMOTE_PREFIX) -> endpoints
            .firstOrNull { remoteRuntimeId(it.id) == targetId }
            ?.displayName
        else -> profiles.firstOrNull { it.id == targetId }?.name
    }
}

data class EndpointDraft(
    val displayName: String,
    val baseUrl: String,
    val modelName: String,
    val contextWindowTokens: Int,
    val apiKey: String,
    val allowInsecureHttp: Boolean,
    val apiKind: RemoteApiKind = RemoteApiKind.CHAT_COMPLETIONS,
)

/** One configured MCP server as the settings screen shows it after a refresh. */
data class McpServerUi(
    val server: McpServer,
    /** How many tools the server advertised, 0 when the last refresh failed. */
    val toolCount: Int,
    /** Why the last refresh failed, or null when it succeeded. */
    val error: String? = null,
)

data class McpServerDraft(
    val displayName: String,
    val baseUrl: String,
    val token: String,
    val allowInsecureHttp: Boolean,
)

/** One batch configuration's result during a tuning run. */
private data class BatchScore(
    val batch: Int,
    val ubatch: Int,
    /** The greedy reference decode's prompt phase, in milliseconds. Lower is faster. */
    val promptMillis: Long,
    /** How closely it reproduced the CPU reference, so the note can say so. */
    val agreement: Double,
)

class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var conversationId = ConversationId(UUID.randomUUID().toString())
    private var generationJob: Job? = null
    /** Whether the app is what the user is looking at. Gates the completion alert. */
    private var appForeground = true
    /** Pure candidate scorer; one per ViewModel because it holds no state. */
    private val router = RuleBasedModelRouter()
    /** Prevents catalog refresh from inventing a Primary before persisted assignments arrive. */
    private var routingPoolRestored = false

    init {
        restoreRoutingSettings()
        mutableState.update { it.copy(embeddingModelName = container.embeddingModelStore.modelName()) }
        container.llamaCppClient.processFailureListener = { message ->
            mutableState.update {
                it.copy(
                    loadedModelId = null,
                    cpuValidated = false,
                    modelLoadDetail = null,
                    error = message,
                )
            }
            // The model is gone with the process; the status service has nothing left to hold.
            AgentTaskService.stop(container.appContext)
            refreshDeviceProfile()
        }
        viewModelScope.launch {
            container.approvalGate.pending.collect { pending ->
                mutableState.update { it.copy(pendingApproval = pending) }
                if (pending != null) {
                    pushModelStatus(ModelPhase.CALLING_TOOL, "Awaiting approval")
                }
            }
        }
        // A tool call that was allowed but needs a runtime permission asks for it here: the broker
        // publishes the permission, the activity shows the system dialog, and the answer resolves
        // the broker so the tool either runs or returns permission_denied.
        viewModelScope.launch {
            container.runtimePermissionBroker.pendingPermission.collect { androidPermission ->
                val label = androidPermission?.let { permission ->
                    RuntimePermissions.androidPermissions
                        .entries
                        .firstOrNull { it.value == permission }
                        ?.let { RuntimePermissions.label(it.key) }
                }
                mutableState.update {
                    it.copy(runtimePermissionRequest = androidPermission, runtimePermissionLabel = label)
                }
            }
        }
        refreshToolPermissions()
        refreshDeviceProfile()
        reloadCatalogs()
        detectBackends()
        restoreConversations()
        installTaskRunner()
        refreshRunJournal()
        refreshMcpServers()
        refreshSkills()
        refreshAutomations()
        container.automationRunner.rescheduleAll()
        refreshMemories()
    }

    private fun refreshRunJournal() {
        viewModelScope.launch {
            val runs = runCatching { container.runJournal.recent(12) }.getOrDefault(emptyList())
            mutableState.update { it.copy(recentRuns = runs) }
        }
    }

    /**
     * Points the task queue at the agent and at everything the queue cannot know for itself:
     * whether a runtime is ready to run right now, and how to tell the user a task finished (or is
     * waiting for a model) when the app is not in front of them.
     */
    private fun installTaskRunner() {
        val runner = container.taskRunner
        runner.executor = { task -> runTask(task) }
        runner.canRun = {
            val snapshot = mutableState.value
            hasConfiguredRoute(snapshot, snapshot.defaultPrivacyClass)
        }
        runner.notifier = { task ->
            val backgrounded = !appForeground
            when (task.state) {
                TaskState.SUCCEEDED, TaskState.FAILED, TaskState.CANCELLED ->
                    if (backgrounded && container.notificationSettings.completionAlertsEnabled()) {
                        AgentTaskService.postTaskFinished(container.appContext, task)
                    }
                TaskState.DEFERRED -> if (backgrounded) {
                    AgentTaskService.postTaskDeferred(container.appContext, task)
                }
                else -> Unit
            }
        }
        viewModelScope.launch {
            runner.tasks.collect { tasks ->
                mutableState.update { it.copy(tasks = tasks) }
            }
        }
        // Tasks due while the app was closed run as soon as a model is ready again.
        runner.processNow()
    }

    /**
     * Runs one queued task to an outcome: its own conversation, AUTO permission mode (the
     * remembered allowances still apply), and its reply written back to the store as the session's
     * record — the task's named session that can be reopened from the library.
     *
     * Tasks are unattended, so a failed attempt falls back down the routing order instead of
     * failing outright; a cancellation is a real stop, not a retry.
     */
    private suspend fun runTask(task: AgentTask): TaskOutcome {
        val initial = mutableState.value
        val workload = RoutingWorkloadClassifier.classify(task.prompt)
        preferredLocalProfile(initial, workload)?.let { prepareLocalProfile(it) }
        val snapshot = mutableState.value
        val plan = routeSelection(
            snapshot,
            snapshot.defaultPrivacyClass,
            preferQuality = workload == RoutingWorkload.DEMANDING,
        )
            .takeIf { it.isNotEmpty() }
            ?: return TaskOutcome.Failed("No eligible routing profile. Check the routing and privacy settings.")
        container.approvalGate.setMode(PermissionMode.AUTO)
        var failure: String? = null
        var reply: String? = null
        var activity = emptyList<String>()
        for (selection in plan) {
            val attemptActivity = mutableListOf<String>()
            var attemptReply: String? = null
            var attemptFailure: String? = null
            val request = AgentRunRequest(
                conversationId = task.conversationId,
                messages = listOf(ConversationMessage(role = MessageRole.USER, content = task.prompt)),
                identity = BramDefaults.IDENTITY,
                maxOutputTokens = minOf(2_048, selection.runtime.model.contextWindowTokens / 4),
                sampler = snapshot.activeProfile?.sampler ?: SamplerSettings(),
                profileInstructions = runInstructions(snapshot),
            )
            try {
                container.agent().run(request, selection.runtime).collect { event ->
                    when (event) {
                        is AgentEvent.Status -> Unit
                        is AgentEvent.Reasoning -> Unit
                        is AgentEvent.ContextPrepared -> attemptActivity += "Context prepared (${event.estimatedInputTokens} tokens)"
                        is AgentEvent.ToolStarted -> attemptActivity += "Called ${event.call.name}"
                        is AgentEvent.ToolFinished -> Unit
                        is AgentEvent.TextDelta -> Unit
                        is AgentEvent.Usage -> Unit
                        is AgentEvent.Metrics -> Unit
                        is AgentEvent.Completed -> attemptReply = event.message.content
                        is AgentEvent.Failed -> attemptFailure = event.message
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                attemptFailure = error.message ?: error::class.java.simpleName
            }
            when {
                attemptReply != null -> {
                    reply = attemptReply
                    activity = attemptActivity
                    break
                }
                attemptFailure != null -> failure = attemptFailure
                else -> failure = "The run ended without producing a reply."
            }
        }
        if (failure != null) return TaskOutcome.Failed(failure)
        val text = reply ?: return TaskOutcome.Failed("The run ended without producing a reply.")
        val messages = listOf(
            ConversationMessage(role = MessageRole.USER, content = task.prompt),
            ConversationMessage(role = MessageRole.ASSISTANT, content = text),
        )
        runCatching { container.conversationStore.save(task.conversationId, messages, title = task.displayName) }
        return TaskOutcome.Succeeded(text, activity)
    }

    /** Queues a task. [inMinutes] null runs it as soon as the queue is free, else it is scheduled. */
    fun enqueueTask(displayName: String, prompt: String, inMinutes: Int? = null) {
        val scheduledAt = inMinutes?.takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 60_000L }
        container.taskRunner.enqueue(displayName, prompt, scheduledAt)
    }

    fun cancelTask(id: String) = container.taskRunner.cancel(id)

    fun retryTask(id: String) = container.taskRunner.retry(id)

    fun deleteTask(id: String) = container.taskRunner.delete(id)

    /** Reopens the most recent conversation so closing Bram does not discard the thread. */
    private fun restoreConversations() {
        viewModelScope.launch {
            val summaries = runCatching { container.conversationStore.list() }.getOrDefault(emptyList())
            val mostRecent = summaries.firstOrNull()
            val messages = mostRecent
                ?.let { summary -> runCatching { container.conversationStore.load(summary.id) }.getOrDefault(emptyList()) }
                .orEmpty()
            val mode = mostRecent
                ?.let { runCatching { container.conversationStore.permissionMode(it.id) }.getOrDefault(PermissionMode.AUTO) }
                ?: PermissionMode.AUTO
            val privacy = mostRecent
                ?.let { runCatching { container.conversationStore.privacyClass(it.id) }.getOrDefault(PrivacyClass.STANDARD) }
                ?: PrivacyClass.STANDARD
            mostRecent?.let { conversationId = it.id }
            container.approvalGate.setMode(mode)
            mutableState.update {
                it.copy(
                    conversations = summaries,
                    activeConversationId = mostRecent?.id?.value,
                    messages = messages,
                    permissionMode = mode,
                    privacyClass = privacy,
                    lastContextTokens = null,
                    sessionInputTokens = 0,
                    sessionOutputTokens = 0,
                )
            }
        }
    }

    /** Persists after each completed turn; a crash mid-generation loses only the partial reply. */
    private fun persistActiveConversation(messages: List<ConversationMessage>) {
        if (messages.isEmpty()) return
        viewModelScope.launch {
            runCatching { container.conversationStore.save(conversationId, messages) }
                .onSuccess { summary ->
                    val summaries = runCatching { container.conversationStore.list() }
                        .getOrDefault(listOf(summary))
                    mutableState.update {
                        it.copy(conversations = summaries, activeConversationId = summary.id.value)
                    }
                }
        }
    }

    fun startNewConversation() {
        if (mutableState.value.isGenerating) return
        conversationId = container.conversationStore.newId()
        container.approvalGate.setMode(PermissionMode.AUTO)
        mutableState.update {
            it.copy(
                messages = emptyList(),
                activeConversationId = conversationId.value,
                status = null,
                error = null,
                lastUsage = null,
                lastMetrics = null,
                permissionMode = PermissionMode.AUTO,
                privacyClass = it.defaultPrivacyClass,
                lastContextTokens = null,
                sessionInputTokens = 0,
                sessionOutputTokens = 0,
            )
        }
    }

    fun openConversation(id: String) {
        if (mutableState.value.isGenerating) return
        viewModelScope.launch {
            val target = ConversationId(id)
            val messages = runCatching { container.conversationStore.load(target) }.getOrDefault(emptyList())
            val mode = runCatching { container.conversationStore.permissionMode(target) }.getOrDefault(PermissionMode.AUTO)
            val privacy = runCatching { container.conversationStore.privacyClass(target) }.getOrDefault(PrivacyClass.STANDARD)
            conversationId = target
            container.approvalGate.setMode(mode)
            mutableState.update {
                it.copy(
                    messages = messages,
                    activeConversationId = id,
                    status = null,
                    error = null,
                    lastUsage = null,
                    lastMetrics = null,
                    permissionMode = mode,
                    privacyClass = privacy,
                    lastContextTokens = null,
                    sessionInputTokens = 0,
                    sessionOutputTokens = 0,
                )
            }
        }
    }

    fun deleteConversation(id: String) {
        if (mutableState.value.isGenerating) return
        viewModelScope.launch {
            runCatching { container.conversationStore.delete(ConversationId(id)) }
            val summaries = runCatching { container.conversationStore.list() }.getOrDefault(emptyList())
            mutableState.update { current ->
                val stillOpen = current.activeConversationId != id
                current.copy(
                    conversations = summaries,
                    messages = if (stillOpen) current.messages else emptyList(),
                    activeConversationId = if (stillOpen) current.activeConversationId else null,
                )
            }
            if (mutableState.value.activeConversationId == null) startNewConversation()
        }
    }

    /**
     * Asks the runtime which backends this build actually found, so the picker never offers one
     * that cannot load. CPU is always present; the rest depend on build flags and hardware.
     */
    private fun detectBackends() {
        viewModelScope.launch { mutableState.update { it.copy(availableBackends = detectBackendsNow()) } }
    }

    /**
     * What the runtime can actually see right now.
     *
     * Suspends rather than launching, because a caller about to choose a backend needs the answer
     * before it chooses, not shortly afterwards.
     */
    private suspend fun detectBackendsNow(): List<RuntimeBackend> {
        // The CPU is always there, so a runtime that cannot be asked still yields a usable answer
        // rather than an empty list that would make every profile look unloadable.
        return runCatching {
            val devices = container.llamaCppClient.devices()
            val names = (0 until devices.optJSONArray("devices")?.length().orZero())
                .map { index -> devices.getJSONArray("devices").getJSONObject(index).optString("name") }
            listOf(RuntimeBackend.CPU) + RuntimeBackend.entries.filter { backend ->
                backend.offloadsToAccelerator && names.any { it.startsWith(backend.devicePrefix) }
            }
        }.getOrDefault(listOf(RuntimeBackend.CPU))
    }

    /**
     * Picks the backend a load should actually target.
     *
     * A profile names its preferred backend, but at a cold start the accelerator list may not have
     * been populated yet — [detectBackends] runs as its own coroutine. Resolving against that stale
     * list is exactly how an NPU profile came back from a cold start running on the CPU, so when the
     * preference is an accelerator the runtime has not yet reported, the runtime is asked before any
     * fallback to CPU. CPU profiles never wait, since CPU is always present.
     */
    private suspend fun resolveLoadBackend(backendId: String): RuntimeBackend {
        val preferred = RuntimeBackend.fromId(backendId)
        if (preferred == RuntimeBackend.CPU) return RuntimeBackend.CPU
        val known = mutableState.value.availableBackends
        if (preferred in known) return preferred
        val detected = detectBackendsNow()
        if (detected != known) mutableState.update { it.copy(availableBackends = detected) }
        return preferred.takeIf { it in detected } ?: RuntimeBackend.CPU
    }

    /** Removes model copies nothing in the catalog references and reports what was reclaimed. */
    fun reclaimModelStorage() {
        viewModelScope.launch {
            val reclaimed = runCatching { container.localModelStore.deleteOrphanedCopies() }
                .getOrDefault(0L) +
                runCatching { container.liteRtLmStore.deleteOrphanedCopies() }
                    .getOrDefault(0L)
            mutableState.update {
                it.copy(
                    status = if (reclaimed > 0) {
                        "Reclaimed ${reclaimed / 1_048_576L} MB of unreferenced model copies"
                    } else {
                        "No unreferenced model copies to remove"
                    },
                )
            }
            refreshModelStorage()
        }
    }

    private fun refreshModelStorage() {
        viewModelScope.launch {
            val bytes = runCatching { container.localModelStore.storageBytesUsed() }.getOrDefault(0L) +
                runCatching { container.liteRtLmStore.storageBytesUsed() }.getOrDefault(0L)
            mutableState.update { it.copy(modelStorageBytes = bytes) }
        }
    }

    fun refreshDeviceProfile() {
        val current = mutableState.value
        val runtimeBackends = current.availableBackends.mapNotNull { backend ->
            when (backend) {
                RuntimeBackend.VULKAN -> io.github.kurue.bram.core.domain.AcceleratorKind.VULKAN_GPU
                RuntimeBackend.OPENCL -> io.github.kurue.bram.core.domain.AcceleratorKind.OPENCL_GPU
                RuntimeBackend.HEXAGON -> io.github.kurue.bram.core.domain.AcceleratorKind.HEXAGON_NPU
                RuntimeBackend.CPU -> null
            }
        }.toSet()
        mutableState.update {
            it.copy(
                deviceProfile = container.deviceProfiler.snapshot(
                    cpuValidated = current.cpuValidated,
                    runtimeBackends = runtimeBackends,
                ),
            )
        }
    }

    fun selectLocalModel(modelId: String) {
        mutableState.update { it.copy(selectedRuntimeId = modelId, error = null) }
    }

    fun selectEndpoint(endpointId: String) {
        mutableState.update { it.copy(selectedRuntimeId = remoteRuntimeId(endpointId), error = null) }
    }

    fun importModel(uri: Uri) {
        if (mutableState.value.isImporting) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isImporting = true,
                    importProgress = ModelImportProgress("Opening model"),
                    error = null,
                )
            }
            val isLiteRt = isLiteRtPackage(uri)
            runCatching {
                if (isLiteRt) {
                    container.liteRtLmStore.importPackage(uri) { progress ->
                        mutableState.update {
                            it.copy(importProgress = ModelImportProgress(progress.stage, progress.bytesRead, progress.totalBytes))
                        }
                    }.id.value
                } else {
                    container.localModelStore.importModel(uri) { progress ->
                        mutableState.update { it.copy(importProgress = progress) }
                    }.id.value
                }
            }.onSuccess { id ->
                if (isLiteRt) {
                    reloadLiteRtModels(selectId = id)
                } else {
                    // Import is the novice setup path: metadata creates the baseline profile, then
                    // Bram measures any processors this phone exposes without asking for tuning.
                    reloadLocalModels(selectId = id, configureImported = true)
                }
            }.onFailure { error ->
                mutableState.update { it.copy(error = error.message ?: "Could not import the model") }
            }
            mutableState.update { it.copy(isImporting = false, importProgress = null) }
        }
    }

    /** Whether a picked document is a LiteRT package (`.litertlm`) rather than a GGUF. */
    private fun isLiteRtPackage(uri: Uri): Boolean {
        val name = runCatching {
            container.appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
        return name?.endsWith(".litertlm", ignoreCase = true) == true
    }

    fun resolveApproval(decision: ToolApprovalDecision) {
        mutableState.value.pendingApproval?.resolve(decision)
        // Granting one is the only way the list grows, so this is the only place it needs refreshing.
        if (decision == ToolApprovalDecision.ALLOW_ALWAYS) refreshToolPermissions()
    }

    fun withdrawToolPermission(scope: String) {
        container.toolPermissionStore.withdraw(scope)
        refreshToolPermissions()
    }

    /**
     * Changes the active conversation's tool-permission mode. The gate is updated immediately so a
     * run in flight is bound by the new mode, and the choice is persisted so reopening the
     * conversation keeps it.
     */
    fun updatePermissionMode(mode: PermissionMode) {
        container.approvalGate.setMode(mode)
        mutableState.update { it.copy(permissionMode = mode) }
        val id = conversationId
        viewModelScope.launch {
            runCatching { container.conversationStore.setPermissionMode(id, mode) }
        }
    }

    /**
     * Changes the active conversation's privacy class: LOCAL_ONLY keeps its content on the device,
     * PRIVATE_REMOTE_ALLOWED lets remote processing in but prefers local, STANDARD has no
     * preference. Persisted per conversation like the tool-permission mode.
     */
    fun updateConversationPrivacyClass(privacyClass: PrivacyClass) {
        mutableState.update { it.copy(privacyClass = privacyClass) }
        val id = conversationId
        viewModelScope.launch {
            runCatching { container.conversationStore.setPrivacyClass(id, privacyClass) }
        }
    }

    /** The global routing mode: how every turn picks between local and remote. */
    fun setRoutingMode(mode: RoutingMode) {
        mutableState.update { it.copy(routingMode = mode) }
        viewModelScope.launch {
            runCatching { container.routingSettings.setRoutingMode(mode) }
        }
    }

    /** The privacy class a brand new conversation starts with. */
    fun setDefaultPrivacyClass(privacyClass: PrivacyClass) {
        mutableState.update { it.copy(defaultPrivacyClass = privacyClass) }
        viewModelScope.launch {
            runCatching { container.routingSettings.setDefaultPrivacyClass(privacyClass) }
        }
    }

    /** Assigns a local profile or remote runtime to one automatic-routing role. */
    fun setRoutingPoolTarget(slot: RoutingPoolSlot, targetId: String?) {
        val snapshot = mutableState.value
        val available = snapshot.profiles.map { it.id }.toSet() +
            snapshot.endpoints.map { remoteRuntimeId(it.id) }
        val clean = targetId?.takeIf { it in available }
        if (targetId != null && clean == null) return
        if (slot == RoutingPoolSlot.REMOTE_OFFLOAD && clean != null && !clean.startsWith(REMOTE_PREFIX)) return
        val updated = snapshot.routingPool.assign(slot, clean)
        mutableState.update { it.copy(routingPool = updated) }
        viewModelScope.launch {
            runCatching { container.routingSettings.setRoutingPool(updated) }
            container.taskRunner.processNow()
        }
    }

    private fun restoreRoutingSettings() {
        viewModelScope.launch {
            val mode = runCatching { container.routingSettings.routingMode() }.getOrDefault(RoutingMode.AUTO)
            val default = runCatching { container.routingSettings.defaultPrivacyClass() }.getOrDefault(PrivacyClass.STANDARD)
            val pool = runCatching { container.routingSettings.routingPool() }.getOrDefault(RoutingPoolAssignments())
            mutableState.update {
                it.copy(routingMode = mode, defaultPrivacyClass = default, routingPool = pool)
            }
            routingPoolRestored = true
            ensurePrimaryRoutingProfile()
        }
    }

    /** A first imported model becomes Primary without making the user understand routing first. */
    private suspend fun ensurePrimaryRoutingProfile() {
        if (!routingPoolRestored) return
        val snapshot = mutableState.value
        if (snapshot.routingPool.primaryTargetId != null) return
        val primary = snapshot.profiles.firstOrNull()?.id ?: return
        val updated = snapshot.routingPool.assign(RoutingPoolSlot.PRIMARY, primary)
        mutableState.update { it.copy(routingPool = updated) }
        runCatching { container.routingSettings.setRoutingPool(updated) }
        container.taskRunner.processNow()
    }

    private fun refreshToolPermissions() {
        val allowed = runCatching { container.toolPermissionStore.alwaysAllowed() }
            .getOrDefault(emptySet())
        mutableState.update { it.copy(alwaysAllowedTools = allowed.sorted()) }
    }

    /**
     * Measures every accelerator this device offers and sets the profile to the best of them.
     *
     * Fastest that *agrees with the CPU*, never simply fastest. The comparison exists because a
     * backend on this hardware reports success while returning garbage, and a choice made on speed
     * alone would pick exactly that one. CPU is the reference, so it is always a valid answer and
     * needs no measurement of its own.
     *
     * The result is written to the profile in words, with the date, so the choice can be read and
     * repeated rather than believed.
     */
    fun autoConfigure(profileId: String) {
        val snapshot = mutableState.value
        val profile = snapshot.profiles.firstOrNull { it.id == profileId } ?: return
        val model = snapshot.localModels.firstOrNull { it.id == profile.modelId } ?: return
        if (snapshot.isLoadingModel || snapshot.isGenerating || snapshot.isValidatingAccelerator ||
            snapshot.batchTuneProfileId != null
        ) return
        val restoreLoaded = snapshot.loadedModelId

        viewModelScope.launch {
            // Ask the runtime what it can see now rather than trusting what was detected at start.
            // The inference process restarts across loads, and a backend that registered late was
            // missing from the list, so a profile configured for it silently loaded on the CPU.
            val backends = detectBackendsNow()

            val candidates = backends
                .filter(RuntimeBackend::offloadsToAccelerator)
                .mapNotNull { backend ->
                    AcceleratorTarget.entries
                        .firstOrNull { it.devicePrefix == backend.devicePrefix }
                        ?.let { backend to it }
                }

            // The CPU is the reference every other result is expressed against, so it is in the
            // list from the start at exactly 1.0x rather than being implied by the others.
            val reference = BackendMeasurement(
                backendId = "",
                label = RuntimeBackend.CPU.label,
                agrees = true,
                agreement = 1.0,
                speedup = 1.0,
            )
            mutableState.update {
                it.copy(
                    isValidatingAccelerator = true,
                    error = null,
                    availableBackends = backends,
                    autoConfigure = AutoConfigureProgress(
                        profileId = profile.id,
                        modelName = profile.name,
                        step = 0,
                        total = candidates.size,
                        current = RuntimeBackend.CPU.label,
                        candidates = candidates.map { it.second.label },
                        results = listOf(reference),
                    ),
                )
            }

            val visibleCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val threads = (visibleCores - 2).coerceIn(1, 4)
            val measured = mutableListOf<Pair<RuntimeBackend, AcceleratorReport>>()

            for ((index, candidate) in candidates.withIndex()) {
                val (backend, target) = candidate
                mutableState.update {
                    it.copy(
                        autoConfigure = it.autoConfigure?.copy(step = index, current = backend.label),
                    )
                }
                // A backend failing is a result, not an error: it means do not use that one. It is
                // recorded as a failure so the card can say so rather than leaving it unexplained.
                val report = runCatching {
                    measureBackend(model, profile, target, threads) { message ->
                        mutableState.update {
                            it.copy(autoConfigure = it.autoConfigure?.copy(current = message))
                        }
                    }
                }.getOrNull()
                if (report != null) measured += backend to report
                val result = BackendMeasurement(
                    backendId = backend.name,
                    label = backend.label,
                    agrees = report?.matchesCpu == true,
                    agreement = report?.agreement ?: 0.0,
                    speedup = report?.speedup ?: 0.0,
                )
                mutableState.update {
                    it.copy(
                        autoConfigure = it.autoConfigure?.let { progress ->
                            progress.copy(step = index + 1, results = progress.results + result)
                        },
                    )
                }
            }

            val best = measured
                .filter { (_, report) -> report.matchesCpu }
                .maxByOrNull { (_, report) -> report.speedup }
                ?.takeIf { (_, report) -> report.speedup > 1.0 }

            val note = when {
                best != null -> {
                    val (backend, report) = best
                    "${backend.label}: ${(report.agreement * 100).toInt()}% agreement with CPU, " +
                        "%.1f× faster. Measured %s.".format(report.speedup, today())
                }
                measured.isEmpty() -> "No accelerator to measure, so this runs on the CPU."
                else -> "No accelerator agreed with the CPU, so this runs on the CPU."
            }

            val results = mutableState.value.autoConfigure?.results.orEmpty()
            container.modelProfileStore.save(
                profile.copy(
                    backendId = best?.first?.takeIf { it != RuntimeBackend.CPU }?.name.orEmpty(),
                    measurements = results.sortedWith(
                        // Winner first, failures last: the order a person reads it in.
                        compareByDescending<BackendMeasurement> { it.agrees }
                            .thenByDescending { it.speedup },
                    ),
                    autoConfiguredNote = note,
                    autoConfiguredAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            runCatching { unloadModelInternal(forget = false) }
            mutableState.update {
                it.copy(isValidatingAccelerator = false, status = null)
            }
            refreshDeviceProfile()
            reloadProfiles(selectId = profile.id)

            // Second phase: the best batch depends on the processor, so it follows the choice of
            // one. Two buttons where one says "configure this for me" was the confusion; this makes
            // the one button mean it.
            mutableState.update {
                it.copy(autoConfigure = it.autoConfigure?.copy(current = "Tuning prompt batch…"))
            }
            runCatching { runBatchTune(profile.id) }
            val tuned = mutableState.value.profiles.firstOrNull { it.id == profile.id }

            mutableState.update {
                it.copy(
                    autoConfigure = it.autoConfigure?.copy(
                        finished = true,
                        current = listOfNotNull(
                            note,
                            tuned?.batchTuneNote?.takeIf(String::isNotBlank),
                        ).joinToString("\n\n"),
                    ),
                )
            }
            restoreLoaded?.let { loadModel(it) }
        }
    }

    /** Closes the auto-configure dialog. The run itself has already finished by then. */
    fun dismissAutoConfigure() {
        mutableState.update { it.copy(autoConfigure = null) }
    }

    private fun today(): String = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
        .format(java.util.Date())

    /**
     * Adds a profile for a model, copied from the one it is running under.
     *
     * Copying rather than starting from defaults is the useful default: a new profile is nearly
     * always a variation on the current one — the same model with a longer context, or reasoning
     * turned on — not a blank slate.
     */
    fun createProfile(model: LocalModelRecord) {
        viewModelScope.launch {
            val source = mutableState.value.profileFor(model)
            val existing = mutableState.value.profiles.count { it.modelId == model.id }
            val created = source.copy(
                id = "profile:${model.id.value}:${System.currentTimeMillis()}",
                name = "${model.displayName} ${existing + 1}",
                isDefault = false,
                createdAtEpochMillis = System.currentTimeMillis(),
            )
            container.modelProfileStore.save(created)
            reloadProfiles(selectId = created.id)
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            val state = mutableState.value
            val profile = state.profiles.firstOrNull { it.id == profileId } ?: return@launch
            // A model with no profile cannot be loaded, and the store would just recreate a default
            // on the next sync, so the last one stays.
            if (state.profiles.count { it.modelId == profile.modelId } <= 1) return@launch
            if (state.activeProfileId == profileId && state.loadedModelId != null) {
                unloadModelInternal(forget = false)
            }
            val updatedPool = state.routingPool.remove(profileId)
            if (updatedPool != state.routingPool) {
                mutableState.update { it.copy(routingPool = updatedPool) }
                container.routingSettings.setRoutingPool(updatedPool)
            }
            container.modelProfileStore.delete(profileId)
            reloadProfiles()
        }
    }

    fun updateProfile(profile: ModelProfile) {
        viewModelScope.launch {
            val state = mutableState.value
            val previous = state.profiles.firstOrNull { it.id == profile.id }
            // Only settings the runtime reads at load time need it torn down. Sampling travels with
            // each request, so a temperature change takes effect on the next reply — unloading for
            // that would throw away a loaded model for nothing, and it is the setting most likely
            // to be nudged repeatedly.
            val needsReload = previous != null && (
                previous.contextTokens != profile.contextTokens ||
                    previous.backendId != profile.backendId ||
                    previous.thinkingEnabled != profile.thinkingEnabled ||
                    previous.flashAttention != profile.flashAttention ||
                    previous.kvCacheType != profile.kvCacheType
                )
            if (needsReload && state.activeProfileId == profile.id && state.loadedModelId != null) {
                unloadModelInternal(forget = false)
            }
            container.modelProfileStore.save(profile)
            reloadProfiles(selectId = profile.id)
        }
    }

    private fun reloadProfiles(selectId: String? = null) {
        viewModelScope.launch { syncProfiles(mutableState.value.localModels, selectId) }
    }

    /**
     * Brings the profile list into state, creating any a model is missing and dropping any whose
     * model is gone. Suspends rather than launching, so a caller that needs profiles present
     * before its next step can wait for it.
     */
    private suspend fun syncProfiles(
        models: List<LocalModelRecord>,
        selectId: String? = null,
    ) {
        runCatching { container.modelProfileStore.removeOrphans(models) }
        val profiles = runCatching { container.modelProfileStore.ensureDefaults(models) }
            .getOrDefault(emptyList())
        mutableState.update { current ->
            current.copy(
                profiles = profiles,
                activeProfileId = selectId
                    ?: current.activeProfileId?.takeIf { id -> profiles.any { it.id == id } },
            )
        }
        ensurePrimaryRoutingProfile()
    }

    /** Loads a model under whichever profile is active for it. */
    fun loadModel(modelId: String) {
        val model = mutableState.value.localModels.firstOrNull { it.id.value == modelId } ?: return
        loadProfile(mutableState.value.profileFor(model).id)
    }

    /**
     * Loads the model a profile names, configured the way the profile says.
     *
     * Context size, processor, and reasoning come from the profile rather than the file, which is
     * what lets one GGUF be run several ways.
     */
    fun loadProfile(profileId: String) {
        val state = mutableState.value
        if (state.isLoadingModel || state.isGenerating) return
        viewModelScope.launch { prepareLocalProfile(profileId) }
    }

    /** Prepares a local profile and returns whether its self-test passed. */
    private suspend fun prepareLocalProfile(profileId: String): Boolean {
        val state = mutableState.value
        val profile = state.profiles.firstOrNull { it.id == profileId } ?: return false
        val model = state.localModels.firstOrNull { it.id == profile.modelId } ?: return false
        val modelId = model.id.value
        if (state.loadedModelId == modelId && state.activeProfileId == profile.id && state.cpuValidated) {
            return true
        }
        if (state.isLoadingModel) return false

        // At a cold start the profile may name an accelerator detection has not reported yet.
        val backend = resolveLoadBackend(profile.backendId)
        mutableState.update {
            it.copy(
                selectedRuntimeId = modelId,
                activeProfileId = profile.id,
                isLoadingModel = true,
                status = "Preparing ${profile.name} on ${backend.label}…",
                error = null,
                modelLoadDetail = null,
            )
        }
        val outcome = runCatching {
            val visibleCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val threads = (visibleCores - 2).coerceIn(1, 4)
            container.llamaCppClient.load(
                model = model.copy(preferredContextTokens = profile.contextTokens),
                threads = threads,
                gpuLayers = if (backend.offloadsToAccelerator) FULL_GPU_OFFLOAD else 0,
                deviceFilter = backend.devicePrefix,
                enableThinking = profile.thinkingEnabled,
                flashAttention = profile.flashAttention,
                kvCacheType = profile.kvCacheType,
                batchTokens = profile.batchTokens,
                ubatchTokens = profile.ubatchTokens,
            )
        }
        outcome.onSuccess { result ->
            container.localModelStore.setLastLoadedModelId(modelId)
            container.modelProfileStore.setLastUsedProfileId(profile.id)
            container.taskRunner.processNow()
            mutableState.update {
                it.copy(
                    loadedModelId = modelId,
                    loadedBackend = backend,
                    cpuValidated = result.optBoolean("cpuValidated"),
                    modelLoadDetail = buildString {
                        append(result.optString("description", model.displayName))
                        append(" · running on ")
                        append(backend.label)
                        append(" · ")
                        append(result.optInt("contextTokens", model.preferredContextTokens))
                        append(" context")
                        if (!backend.offloadsToAccelerator) {
                            append(" · ")
                            append(result.optInt("threads", 1))
                            append(" threads")
                        }
                        result.optLong("processPssBytes").takeIf { bytes -> bytes > 0 }?.let { bytes ->
                            append(" · ")
                            append(bytes / 1_048_576L)
                            append(" MB process PSS")
                        }
                        append(" · self-test passed")
                    },
                )
            }
            pushModelStatus(ModelPhase.IDLE)
            refreshDeviceProfile()
        }.onFailure { error ->
            mutableState.update {
                it.copy(
                    loadedModelId = null,
                    loadedBackend = null,
                    cpuValidated = false,
                    error = error.message ?: "Could not prepare the local profile",
                )
            }
            AgentTaskService.stop(container.appContext)
            refreshDeviceProfile()
        }
        mutableState.update { it.copy(isLoadingModel = false, status = null) }
        return outcome.isSuccess && mutableState.value.cpuValidated
    }

    /**
     * Validates the Vulkan/Adreno backend against CPU output. The model is loaded on CPU to record
     * a deterministic greedy-decode reference, then reloaded with full GPU offload and asked for
     * the same sequence. Loading twice in sequence (rather than side by side) keeps peak memory to
     * one model, which matters on a phone.
     */
    /**
     * Runs one accelerator against the CPU reference and reports what it found.
     *
     * Shared by the manual comparison and by automatic provisioning, so a profile is configured
     * against exactly the evidence a user would see if they ran the comparison themselves. Leaves
     * the runtime holding whichever model it last loaded; the caller restores it.
     */
    private suspend fun measureBackend(
        model: LocalModelRecord,
        profile: ModelProfile,
        target: AcceleratorTarget,
        threads: Int,
        onProgress: (String) -> Unit = {},
    ): AcceleratorReport = run {
            val devices = container.llamaCppClient.devices()
            val deviceName = (0 until devices.optJSONArray("devices")?.length().orZero())
                .map { index -> devices.getJSONArray("devices").getJSONObject(index) }
                .firstOrNull { device -> device.optString("name").startsWith(target.devicePrefix) }
                ?.let { device -> device.optString("description").ifBlank { device.optString("name") } }
                ?: throw IllegalStateException(
                    "This build found no ${target.label} device, so it cannot be validated.",
                )

            container.llamaCppClient.load(
                model,
                threads,
                gpuLayers = 0,
                flashAttention = profile.flashAttention,
                kvCacheType = profile.kvCacheType,
            )
            val cpuStarted = System.currentTimeMillis()
            val cpuResult = container.llamaCppClient.referenceDecode(REFERENCE_TOKENS)
            val cpuMillis = System.currentTimeMillis() - cpuStarted
            val cpuTokens = cpuResult.optJSONArray("tokens").toIntList()

            onProgress("Replaying the reference on $deviceName…")
            container.llamaCppClient.load(
                model,
                threads,
                gpuLayers = FULL_GPU_OFFLOAD,
                deviceFilter = target.devicePrefix,
                flashAttention = profile.flashAttention,
                kvCacheType = profile.kvCacheType,
            )
            val gpuStarted = System.currentTimeMillis()
            // Score the accelerator the same way the bisection does. Comparing free-running
            // output token-for-token is not a correctness test: a quantized backend disagrees
            // on near-ties, and one such difference then changes every token after it.
            val predicted = container.llamaCppClient.teacherForced(cpuTokens.toIntArray())
                .optJSONArray("predictions").toIntList()
            val gpuMillis = System.currentTimeMillis() - gpuStarted
            val agreement = AcceleratorAgreement.score(cpuTokens, predicted)
            val usable = cpuTokens.isNotEmpty() && AcceleratorAgreement.isUsable(agreement)
            val agreed = (agreement * minOf(cpuTokens.size, predicted.size)).toInt()

            AcceleratorReport(
                deviceName = deviceName,
                matchesCpu = usable,
                agreement = agreement,
                cpuTokens = cpuTokens,
                acceleratorTokens = predicted,
                cpuMillis = cpuMillis,
                acceleratorMillis = gpuMillis,
                cpuText = cpuResult.optString("text"),
                // Teacher forcing yields per-position predictions rather than a continuous
                // string, so name the positions that differed instead of a second passage.
                acceleratorText = (0 until minOf(cpuTokens.size, predicted.size))
                    .filter { position -> cpuTokens[position] != predicted[position] }
                    .joinToString(", ") { position ->
                        "position $position: expected ${cpuTokens[position]}, got ${predicted[position]}"
                    }
                    .ifBlank { "every prediction matched" },
                detail = if (usable) {
                    "$deviceName agreed with the CPU reference on $agreed of ${cpuTokens.size} " +
                        "predictions. Small differences on near-ties are expected."
                } else {
                    "$deviceName agreed on only $agreed of ${cpuTokens.size} predictions, which is " +
                        "below the ${(AcceleratorAgreement.USABLE_THRESHOLD * 100).toInt()}% needed " +
                        "to treat it as computing correctly."
                },
            )
    }

    fun validateAccelerator(modelId: String, target: AcceleratorTarget = AcceleratorTarget.VULKAN) {
        val state = mutableState.value
        val model = state.localModels.firstOrNull { it.id.value == modelId } ?: return
        if (state.isLoadingModel || state.isGenerating || state.isValidatingAccelerator) return
        // The comparison must run under the profile's attention and KV settings: those are context
        // parameters, and scoring the accelerator under different ones would validate a mix the
        // profile can never reproduce.
        val profile = state.profiles.firstOrNull { it.modelId == model.id && it.id == state.activeProfileId }
            ?: state.profiles.firstOrNull { it.modelId == model.id }
            ?: ModelProfile.defaultFor(model)
        // Put the chat back the way it was found: the run needs the runtime to itself.
        val restoreLoaded = state.loadedModelId
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isValidatingAccelerator = true,
                    acceleratorReport = null,
                    error = null,
                    status = "Recording the CPU reference…",
                )
            }
            val visibleCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val threads = (visibleCores - 2).coerceIn(1, 4)
            runCatching {
                measureBackend(model, profile, target, threads) { message ->
                    mutableState.update { it.copy(status = message) }
                }
            }.onSuccess { report ->
                mutableState.update { it.copy(acceleratorReport = report) }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(error = error.message ?: "Could not validate the accelerator")
                }
            }
            // The comparison leaves the runtime in whatever state the last load produced, so drop
            // it and put back what was loaded before. Leaving it unloaded stranded the chat: the
            // composer stays enabled with no model behind it and sending does nothing.
            runCatching { unloadModelInternal(forget = false) }
            mutableState.update { it.copy(isValidatingAccelerator = false, status = null) }
            refreshDeviceProfile()
            restoreLoaded?.let { loadModel(it) }
        }
    }

    /**
     * Measures which batch configuration is fastest for a profile, then saves the winner.
     *
     * Prompt speed lives in n_batch — how many prompt tokens llama.cpp evaluates at once — so a
     * bigger batch means fewer evaluations and a shorter wait for the first token, up to the point
     * where the device's memory or the backend's tile size stops cooperating. The candidates are
     * scored on the profile's own backend under teacher forcing: each one must reproduce the CPU
     * reference token-for-token before its speed counts, so a bigger batch cannot buy time by
     * changing the math. The winner is written into the profile, like an accelerator measurement,
     * and the model is restored to whatever was loaded before.
     */
    /** Tunes the batch on its own, for someone who wants only that. */
    fun tuneBatch(profileId: String) {
        val state = mutableState.value
        if (state.isLoadingModel || state.isGenerating ||
            state.isValidatingAccelerator || state.batchTuneProfileId != null
        ) return
        viewModelScope.launch { runBatchTune(profileId) }
    }

    /**
     * Measures prompt speed across candidate batch sizes and keeps the fastest that still
     * reproduces the CPU reference.
     *
     * Suspends rather than launching so auto-configure can run it as its second phase: the
     * best batch depends on the processor, so it has to follow the choice of one.
     */
    private suspend fun runBatchTune(profileId: String) {
        val state = mutableState.value
        val profile = state.profiles.firstOrNull { it.id == profileId } ?: return
        val model = state.localModels.firstOrNull { it.id == profile.modelId } ?: return
        val restoreLoaded = mutableState.value.loadedModelId
            val backend = resolveLoadBackend(profile.backendId)
            mutableState.update {
                it.copy(
                    batchTuneProfileId = profile.id,
                    error = null,
                    status = "Recording the CPU reference…",
                )
            }
            val visibleCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val threads = (visibleCores - 2).coerceIn(1, 4)
            val gpuLayers = if (backend.offloadsToAccelerator) FULL_GPU_OFFLOAD else 0
            val tried = mutableListOf<String>()
            runCatching {
                // The reference is always recorded on CPU at the default batch, so every candidate
                // is scored against the same yardstick and "fastest" can never mean "wrong the
                // same way twice".
                container.llamaCppClient.load(
                    model,
                    threads,
                    gpuLayers = 0,
                    enableThinking = profile.thinkingEnabled,
                    flashAttention = profile.flashAttention,
                    kvCacheType = profile.kvCacheType,
                )
                val cpuReference = container.llamaCppClient.referenceDecode(REFERENCE_TOKENS)
                val reference = cpuReference.optJSONArray("tokens").toIntList()
                check(reference.isNotEmpty()) { "The CPU reference decode returned no tokens" }
                val forced = reference.toIntArray()
                // The default (512/128) is in the list so there is always a baseline to fall back
                // to, and 1024/128 is the largest batch worth trying before the profile's context
                // memory starts paying for it.
                val candidates = listOf(256 to 128, 512 to 128, 512 to 256, 1024 to 128)
                val scored = mutableListOf<BatchScore>()
                for ((batch, ubatch) in candidates) {
                    mutableState.update {
                        it.copy(status = "Measuring batch $batch/$ubatch on ${backend.label}…")
                    }
                    container.llamaCppClient.load(
                        model,
                        threads,
                        gpuLayers = gpuLayers,
                        deviceFilter = backend.devicePrefix,
                        enableThinking = profile.thinkingEnabled,
                        flashAttention = profile.flashAttention,
                        kvCacheType = profile.kvCacheType,
                        batchTokens = batch,
                        ubatchTokens = ubatch,
                    )
                    val predicted = container.llamaCppClient.teacherForced(forced)
                        .optJSONArray("predictions").toIntList()
                    // Exact equality is the wrong bar here: a quantized accelerator legitimately
                    // disagrees with the fp32 CPU on near-ties (the same one bisection finds at a
                    // fixed offload depth). The batch must hold the same ground the accelerator
                    // comparison does — usable, not identical.
                    val agreement = AcceleratorAgreement.score(reference, predicted)
                    if (AcceleratorAgreement.isUsable(agreement)) {
                        // The agreement check already ran the same graph shape, but the score that
                        // decides anything is the wall time of the actual greedy reference path.
                        val decode = container.llamaCppClient.referenceDecode(REFERENCE_TOKENS)
                        scored += BatchScore(
                            batch = batch,
                            ubatch = ubatch,
                            promptMillis = decode.optLong("promptMillis", 0L),
                            agreement = agreement,
                        )
                    }
                    tried += "$batch/$ubatch"
                }
                check(scored.isNotEmpty()) {
                    "None of the batch configurations met the " +
                        "${(AcceleratorAgreement.USABLE_THRESHOLD * 100).toInt()}% agreement bar " +
                        "against the CPU reference (${tried.joinToString(", ")} all failed)"
                }
                val best = scored.minBy { it.promptMillis }
                val promptTokens = cpuReference.optInt("promptTokens", 0)
                val rate = if (best.promptMillis > 0 && promptTokens > 0) {
                    promptTokens * 1000 / best.promptMillis
                } else {
                    0
                }
                val comparable = reference.size
                val tuned = profile.copy(
                    batchTokens = best.batch,
                    ubatchTokens = best.ubatch,
                    batchTuneNote = buildString {
                        append("Tuned batch ${best.batch}/${best.ubatch} on ${backend.label}: ")
                        append("$rate prompt tok/s, ")
                        append("${(best.agreement * comparable).toInt()}/$comparable predictions ")
                        append("match the CPU reference")
                        append(". Tried ${tried.joinToString(", ")}.")
                    },
                    batchTunedAtEpochMillis = System.currentTimeMillis(),
                )
                container.modelProfileStore.save(tuned)
                reloadProfiles(selectId = profile.id)
            }.onFailure { error ->
                mutableState.update {
                    it.copy(error = error.message ?: "Could not tune the batch size")
                }
            }
            // The measurement leaves the runtime on the last candidate, so drop it and restore
            // whatever was loaded before — the profile, now with its tuned batch, if it was the
            // one being tuned.
            runCatching { unloadModelInternal(forget = false) }
            mutableState.update { it.copy(batchTuneProfileId = null, status = null) }
            refreshDeviceProfile()
            restoreLoaded?.let { loadModel(it) }
    }

    /**
     * Narrows a failing accelerator down to a layer boundary. Records the CPU reference once, then
     * binary-searches the offload count for the largest value that still reproduces it. Whether the
     * boundary lands at zero or partway through separates "a shared operation is broken" from
     * "one layer's operation is broken".
     */
    fun bisectAccelerator(modelId: String, target: AcceleratorTarget = AcceleratorTarget.VULKAN) {
        val state = mutableState.value
        val model = state.localModels.firstOrNull { it.id.value == modelId } ?: return
        if (state.isLoadingModel || state.isGenerating || state.isValidatingAccelerator) return
        val profile = state.profiles.firstOrNull { it.modelId == model.id && it.id == state.activeProfileId }
            ?: state.profiles.firstOrNull { it.modelId == model.id }
            ?: ModelProfile.defaultFor(model)
        // Put the chat back the way it was found: the run needs the runtime to itself.
        val restoreLoaded = state.loadedModelId
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isValidatingAccelerator = true,
                    acceleratorBisection = null,
                    acceleratorReport = null,
                    error = null,
                    status = "Recording the CPU reference…",
                )
            }
            val visibleCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val threads = (visibleCores - 2).coerceIn(1, 4)
            val probes = mutableListOf<AcceleratorProbe>()
            runCatching {
                val devices = container.llamaCppClient.devices()
                val deviceName = (0 until devices.optJSONArray("devices")?.length().orZero())
                    .map { index -> devices.getJSONArray("devices").getJSONObject(index) }
                    .firstOrNull { device -> device.optString("name").startsWith(target.devicePrefix) }
                    ?.let { device -> device.optString("description").ifBlank { device.optString("name") } }
                    ?: throw IllegalStateException(
                        "This build found no ${target.label} device to bisect.",
                    )

                container.llamaCppClient.load(
                    model,
                    threads,
                    gpuLayers = 0,
                    flashAttention = profile.flashAttention,
                    kvCacheType = profile.kvCacheType,
                )
                val reference = container.llamaCppClient.referenceDecode(REFERENCE_TOKENS)
                    .optJSONArray("tokens").toIntList()
                check(reference.isNotEmpty()) { "The CPU reference decode returned no tokens" }

                // llama.cpp counts the output layer too, so probe one past the repeating layers.
                val totalLayers = model.layerCount.takeIf { it > 0 }?.plus(1) ?: 32

                val forced = reference.toIntArray()
                suspend fun probeAt(layers: Int): AcceleratorProbe {
                    mutableState.update {
                        it.copy(status = "Testing $layers of $totalLayers layers on ${target.label}…")
                    }
                    container.llamaCppClient.load(
                        model,
                        threads,
                        gpuLayers = layers,
                        deviceFilter = target.devicePrefix,
                        flashAttention = profile.flashAttention,
                        kvCacheType = profile.kvCacheType,
                    )
                    val started = System.currentTimeMillis()
                    val predicted = container.llamaCppClient.teacherForced(forced)
                        .optJSONArray("predictions").toIntList()
                    // Position i predicts reference[i]; both backends see identical inputs, so a
                    // disagreement is a real numerical difference rather than compounded drift.
                    val score = AcceleratorAgreement.score(reference, predicted)
                    val comparable = minOf(predicted.size, reference.size)
                    val probe = AcceleratorProbe(
                        gpuLayers = layers,
                        agreement = score,
                        millis = System.currentTimeMillis() - started,
                        text = "${(score * comparable).toInt()}/$comparable predictions agree",
                    )
                    probes += probe
                    return probe
                }

                val boundary = findOffloadBoundary(totalLayers) { layers -> probeAt(layers).usable }
                AcceleratorBisection(
                    deviceName = deviceName,
                    totalLayers = totalLayers,
                    lastGoodLayers = boundary.lastGoodLayers,
                    firstBadLayers = boundary.firstBadLayers,
                    probes = probes.toList(),
                )
            }.onSuccess { bisection ->
                mutableState.update { it.copy(acceleratorBisection = bisection) }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(error = error.message ?: "Could not bisect the accelerator")
                }
            }
            runCatching { unloadModelInternal(forget = false) }
            mutableState.update { it.copy(isValidatingAccelerator = false, status = null) }
            refreshDeviceProfile()
            restoreLoaded?.let { loadModel(it) }
        }
    }

    fun unloadModel() {
        if (mutableState.value.isGenerating) return
        viewModelScope.launch { unloadModelInternal() }
    }

    /**
     * Loads a LiteRT package onto the resident engine's backend — CPU or GPU as the package was
     * imported with, so a package imported for its accelerator stays on it.
     *
     * Engine initialization is the whole validation: if the package's native libraries or kernels
     * cannot run here, load throws and nothing is marked loaded. Compilation for a first-time GPU
     * package can take a while, hence the loading status line.
     */
    fun loadLiteRtModel(modelId: String) {
        val record = mutableState.value.litertlmModels.firstOrNull { it.id.value == modelId } ?: return
        if (mutableState.value.isLoadingLiteRt || mutableState.value.isGenerating) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    selectedRuntimeId = modelId,
                    isLoadingLiteRt = true,
                    status = "Loading ${record.displayName} on ${record.backend.label}…",
                    error = null,
                    liteRtLoadDetail = null,
                )
            }
            runCatching { container.liteRtEngineManager.load(record) }
                .onSuccess {
                    viewModelScope.launch { container.liteRtLmStore.setLastLoadedPackageId(modelId) }
                    // Deferred tasks wait on a loaded model; the queue can start them now.
                    container.taskRunner.processNow()
                    mutableState.update {
                        it.copy(
                            litertlmLoadedId = modelId,
                            litertlmLoadedBackend = record.backend,
                            liteRtLoadDetail = "${record.displayName} · ${record.backend.label} · " +
                                "$LITE_RT_CONTEXT_TOKENS context",
                        )
                    }
                    pushModelStatus(ModelPhase.IDLE)
                    refreshDeviceProfile()
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            litertlmLoadedId = null,
                            litertlmLoadedBackend = null,
                            error = error.message ?: "Could not load the LiteRT package",
                        )
                    }
                    AgentTaskService.stop(container.appContext)
                    refreshDeviceProfile()
                }
            mutableState.update { it.copy(isLoadingLiteRt = false, status = null) }
        }
    }

    /** Unloads the LiteRT engine. The llama.cpp service, if it holds a model, is untouched. */
    fun unloadLiteRt() {
        if (mutableState.value.isGenerating) return
        container.liteRtEngineManager.unload()
        mutableState.update {
            it.copy(litertlmLoadedId = null, litertlmLoadedBackend = null, liteRtLoadDetail = null)
        }
        AgentTaskService.stop(container.appContext)
    }

    /** Removes an imported LiteRT package, unloading it first if it holds the engine. */
    fun removeLiteRt(modelId: String) {
        if (mutableState.value.litertlmLoadedId == modelId) {
            container.liteRtEngineManager.unload()
        }
        viewModelScope.launch {
            runCatching { container.liteRtLmStore.remove(ModelId(modelId)) }
            mutableState.update {
                it.copy(
                    litertlmLoadedId = if (it.litertlmLoadedId == modelId) null else it.litertlmLoadedId,
                    litertlmLoadedBackend = if (it.litertlmLoadedId == modelId) null else it.litertlmLoadedBackend,
                    liteRtLoadDetail = null,
                )
            }
            reloadLiteRtModels()
        }
    }

    fun saveEndpoint(draft: EndpointDraft) {
        viewModelScope.launch {
            val validation = validate(draft)
            if (validation != null) {
                mutableState.update { it.copy(error = validation) }
                return@launch
            }
            val endpoint = RemoteEndpoint(
                id = UUID.randomUUID().toString(),
                displayName = draft.displayName.trim(),
                baseUrl = draft.baseUrl.trim().trimEnd('/'),
                modelName = draft.modelName.trim(),
                apiKind = draft.apiKind,
                contextWindowTokens = draft.contextWindowTokens,
                supportsToolCalling = true,
                allowInsecureHttp = draft.allowInsecureHttp,
            )
            container.endpointStore.upsert(endpoint, draft.apiKey)
            reloadEndpoints(selectId = endpoint.id)
        }
    }

    fun removeEndpoint(endpointId: String) {
        viewModelScope.launch {
            val targetId = remoteRuntimeId(endpointId)
            val snapshot = mutableState.value
            val updatedPool = snapshot.routingPool.remove(targetId)
            if (updatedPool != snapshot.routingPool) {
                mutableState.update { it.copy(routingPool = updatedPool) }
                container.routingSettings.setRoutingPool(updatedPool)
            }
            container.endpointStore.remove(endpointId)
            reloadEndpoints()
        }
    }

    /**
     * Connects to every configured MCP server, lists its tools, and swaps them into the tool
     * registry. A server that cannot be reached drops its tools instead of leaving stale ones that
     * would fail mid-run; the card under it says why.
     */
    fun refreshMcpServers() {
        if (mutableState.value.mcpRefreshing) return
        viewModelScope.launch {
            mutableState.update { it.copy(mcpRefreshing = true) }
            val servers = runCatching { container.mcpServerStore.list() }.getOrDefault(emptyList())
            val results = servers.map { server ->
                val token = container.mcpServerStore.resolveToken(server.id)
                val client = McpClient(server, token)
                runCatching {
                    client.connect()
                    client.listTools()
                }.fold(
                    onSuccess = { tools ->
                        container.toolRegistry.setServerTools(
                            server.id,
                            tools.map { McpToolHandler(server, it, token) },
                        )
                        McpServerUi(server, tools.size)
                    },
                    onFailure = { failure ->
                        container.toolRegistry.removeServerTools(server.id)
                        McpServerUi(
                            server,
                            toolCount = 0,
                            error = failure.message
                                ?: "The server did not answer (${failure::class.java.simpleName})",
                        )
                    },
                )
            }
            mutableState.update { it.copy(mcpServers = results, mcpRefreshing = false) }
        }
    }

    fun saveMcpServer(draft: McpServerDraft) {
        viewModelScope.launch {
            val url = runCatching { java.net.URI(draft.baseUrl.trim()) }.getOrNull()
            when {
                draft.displayName.isBlank() ->
                    mutableState.update { it.copy(error = "An MCP server needs a name") }
                url == null || (url.scheme != "http" && url.scheme != "https") || url.host.isNullOrBlank() ->
                    mutableState.update { it.copy(error = "The MCP server URL must start with http:// or https://") }
                url.scheme == "http" && !draft.allowInsecureHttp ->
                    mutableState.update {
                        it.copy(error = "Plain HTTP is refused for MCP servers; check the box to allow it for this server")
                    }
                else -> {
                    val server = McpServer(
                        id = UUID.randomUUID().toString(),
                        displayName = draft.displayName.trim(),
                        baseUrl = draft.baseUrl.trim().trimEnd('/'),
                        allowInsecureHttp = draft.allowInsecureHttp,
                    )
                    container.mcpServerStore.upsert(server, draft.token)
                    refreshMcpServers()
                }
            }
        }
    }

    fun removeMcpServer(serverId: String) {
        viewModelScope.launch {
            container.mcpServerStore.remove(serverId)
            container.toolRegistry.removeServerTools(serverId)
            refreshMcpServers()
        }
    }

    fun refreshSkills() {
        viewModelScope.launch {
            val skills = runCatching { container.skillStore.packages() }.getOrDefault(emptyList())
            mutableState.update { it.copy(skills = skills) }
        }
    }

    /** Reads a picked skill file and imports it; the file picker hands over the URI, like models. */
    fun importSkillDocument(uri: Uri) {
        viewModelScope.launch {
            val document = runCatching {
                container.appContext.contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (document == null) {
                mutableState.update { it.copy(skillStatus = "Could not read the picked file.") }
            } else {
                importSkill(document)
            }
        }
    }

    fun importSkill(document: String) {
        viewModelScope.launch {
            val message = when (val outcome = container.skillStore.importDocument(document)) {
                is SkillImportOutcome.Imported ->
                    if (outcome.stagedAsDraft) {
                        "Staged ${outcome.version} of ${outcome.packageId} as a draft. Activate it to make it available to the agent."
                    } else {
                        "Imported ${outcome.packageId} ${outcome.version}; it is active."
                    }
                is SkillImportOutcome.Rejected -> "Skill not imported: ${outcome.reason}"
            }
            mutableState.update { it.copy(skillStatus = message) }
            refreshSkills()
        }
    }

    fun activateSkillDraft(skillId: String) {
        viewModelScope.launch {
            val message = when (val outcome = container.skillStore.activateDraft(skillId)) {
                is SkillActionOutcome.Ok -> "Draft activated."
                is SkillActionOutcome.Failed -> "Could not activate: ${outcome.reason}"
            }
            mutableState.update { it.copy(skillStatus = message) }
            refreshSkills()
        }
    }

    fun rollbackSkill(skillId: String) {
        viewModelScope.launch {
            val message = when (val outcome = container.skillStore.rollback(skillId)) {
                is SkillActionOutcome.Ok -> "Rolled back to the previous version."
                is SkillActionOutcome.Failed -> "Could not roll back: ${outcome.reason}"
            }
            mutableState.update { it.copy(skillStatus = message) }
            refreshSkills()
        }
    }

    fun removeSkill(skillId: String) {
        viewModelScope.launch {
            val message = when (val outcome = container.skillStore.remove(skillId)) {
                is SkillActionOutcome.Ok -> "Skill removed."
                is SkillActionOutcome.Failed -> "Could not remove: ${outcome.reason}"
            }
            mutableState.update { it.copy(skillStatus = message) }
            refreshSkills()
        }
    }

    /**
     * The profile's instructions with the active skills and the user's standing memories appended,
     * read fresh for every run so newly activated skills and freshly extracted facts apply at once.
     */
    private suspend fun runInstructions(snapshot: AppUiState): String {
        val base = snapshot.activeProfile?.systemPrompt.orEmpty()
        val skills = runCatching { container.skillStore.activeSkills() }.getOrDefault(emptyList())
        val memories = runCatching { container.memoryStore.mostImportant(MEMORY_INJECTION_LIMIT) }
            .getOrDefault(emptyList())
        return MemoryPrompt.append(SkillPrompt.append(base, skills), memories)
    }

    fun refreshAutomations() {
        viewModelScope.launch {
            val automations = runCatching { container.automationStore.automations() }
                .getOrDefault(emptyList())
                .sortedByDescending { it.updatedAtEpochMillis }
            mutableState.update { it.copy(automations = automations) }
        }
    }

    fun saveAutomation(name: String, cron: String, prompt: String) {
        viewModelScope.launch {
            when {
                name.isBlank() -> status("An automation needs a name")
                prompt.isBlank() -> status("An automation needs a prompt to run")
                Cron.parse(cron) is Cron.Result.Rejected ->
                    status("The schedule \"$cron\" is not a valid cron expression: minute hour day-of-month month day-of-week")
                else -> {
                    val now = System.currentTimeMillis()
                    container.automationStore.save(
                        Automation(
                            id = UUID.randomUUID().toString(),
                            name = name.trim(),
                            cron = cron.trim(),
                            prompt = prompt.trim(),
                            enabled = true,
                            updatedAtEpochMillis = now,
                        ),
                    )
                    container.automationRunner.rescheduleAll()
                    status("Automation saved; it fires on its schedule from now on.")
                    refreshAutomations()
                }
            }
        }
    }

    fun removeAutomation(automationId: String) {
        viewModelScope.launch {
            container.automationStore.remove(automationId)
            container.automationRunner.rescheduleAll()
            refreshAutomations()
        }
    }

    fun setAutomationEnabled(automationId: String, enabled: Boolean) {
        viewModelScope.launch {
            val existing = container.automationStore.automations().firstOrNull { it.id == automationId }
                ?: return@launch
            container.automationStore.save(existing.copy(enabled = enabled))
            container.automationRunner.rescheduleAll()
            refreshAutomations()
        }
    }

    private fun status(message: String) {
        mutableState.update { it.copy(automationStatus = message) }
    }

    fun refreshMemories() {
        viewModelScope.launch {
            val memories = runCatching { container.memoryStore.recent(MEMORY_BROWSER_LIMIT) }
                .getOrDefault(emptyList())
            mutableState.update { it.copy(memories = memories) }
        }
    }

    fun removeMemory(id: String) {
        viewModelScope.launch {
            runCatching { container.memoryStore.remove(id) }
            refreshMemories()
        }
    }

    /**
     * Loads [modelId] into the resident embedding context in the inference process and remembers it
     * as the active embedding model, so future memories (and the next query) embed through it.
     */
    fun setEmbeddingModel(modelId: String) {
        val model = mutableState.value.localModels.firstOrNull { it.id.value == modelId } ?: return
        viewModelScope.launch {
            container.embeddingModelStore.set(model.id.value, model.localPath, model.displayName)
            mutableState.update { it.copy(embeddingModelName = model.displayName) }
            runCatching { container.llamaCppClient.loadEmbedder(model.localPath, EMBEDDER_THREADS) }
        }
    }

    /** Drops the resident embedding model; recall falls back to keyword (FTS) only. */
    fun clearEmbeddingModel() {
        viewModelScope.launch {
            runCatching { container.llamaCppClient.unloadEmbedder() }
            container.embeddingModelStore.clear()
            mutableState.update { it.copy(embeddingModelName = null) }
        }
    }

    /** The assistant bubble as it stands mid-turn, so tool steps appear as they happen. */
    private fun inFlightMessage(text: String, activity: List<AgentActivity>) = ConversationMessage(
        role = MessageRole.ASSISTANT,
        content = text,
        activity = activity.toList(),
    )

    /** Starts a new thread rather than erasing the current one, which is now kept on disk. */
    fun clearChat() = startNewConversation()

    fun stopGeneration() {
        if (!mutableState.value.isGenerating) return
        mutableState.update { it.copy(status = "Stopping…") }
        generationJob?.cancel(CancellationException("Stopped by user"))
    }

    /**
     * Moves the status notification to the current phase. No-op when nothing is loaded or
     * selected, which is when there is nothing to hold a foreground service for.
     */
    private fun pushModelStatus(phase: ModelPhase, detail: String? = null) {
        val s = mutableState.value
        val name = s.loadedModelId
            ?.let { id -> s.localModels.firstOrNull { it.id.value == id }?.displayName }
            ?: s.litertlmLoadedId
                ?.let { id -> s.litertlmModels.firstOrNull { it.id.value == id }?.displayName }
            ?: s.selectedEndpoint?.displayName
            ?: return
        val backend = s.loadedBackend?.label
            ?: s.litertlmLoadedBackend?.label
            ?: if (s.lastRoutedRuntimeLabel != null && s.loadedModelId == null) "Remote" else "CPU"
        AgentTaskService.update(
            container.appContext,
            model = name,
            backend = backend,
            phase = phase,
            detail = detail,
        )
    }

    /** The activity reports whether the user is looking at the app, gating the completion alert. */
    fun setAppForeground(foreground: Boolean) {
        appForeground = foreground
        // The approval card is part of the activity UI; an unattended run cannot reach it, so tell
        // the gate to refuse rather than wait the full timeout per call.
        container.approvalGate.setAttended(foreground)
    }

    fun setCompletionAlerts(enabled: Boolean) {
        container.notificationSettings.setCompletionAlerts(enabled)
        mutableState.update {
            it.copy(completionAlertsEnabled = enabled, requestNotificationPermission = enabled)
        }
    }

    /** The activity acted on the one-shot permission request; clear it so it does not re-fire. */
    fun consumedPermissionRequest() {
        mutableState.update { it.copy(requestNotificationPermission = false) }
    }

    /** The system dialog answered; tell the broker, which resumes the waiting tool call. */
    fun resolvedRuntimePermission(granted: Boolean) {
        container.runtimePermissionBroker.resolve(granted)
    }

    /** The activity launched the dialog for the pending request; clear it so it does not re-fire. */
    fun consumedRuntimePermissionRequest() {
        mutableState.update { it.copy(runtimePermissionRequest = null, runtimePermissionLabel = null) }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        val priorMessages = mutableState.value.messages
        runTurn(priorMessages + ConversationMessage(role = MessageRole.USER, content = prompt))
    }

    /**
     * Discards the last reply and asks again from the same point. Useful when a small model wanders
     * or stops early, which is common enough on a phone that retrying should not mean retyping.
     */
    fun regenerateLastReply() {
        val messages = mutableState.value.messages
        val lastUser = messages.indexOfLast { it.role == MessageRole.USER }
        if (lastUser < 0) return
        runTurn(messages.take(lastUser + 1))
    }

    /** Rewrites a message and continues from there, dropping everything that followed it. */
    fun editAndResend(messageId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val messages = mutableState.value.messages
        val index = messages.indexOfFirst { it.id.value == messageId }
        if (index < 0) return
        val rewritten = messages[index].copy(content = trimmed)
        runTurn(messages.take(index) + rewritten)
    }

    private fun runTurn(requestMessages: List<ConversationMessage>) {
        val snapshot = mutableState.value
        if (snapshot.isGenerating) return
        // Bind the gate to this conversation's mode for the duration of the run. Runs are serial,
        // and the mode could have been changed on another conversation in the meantime.
        container.approvalGate.setMode(snapshot.permissionMode)
        mutableState.update {
            it.copy(
                messages = requestMessages,
                isGenerating = true,
                status = "Choosing a profile…",
                error = null,
                lastUsage = null,
                lastMetrics = null,
            )
        }

        // Run on the application scope, not the ViewModel's: agent work is expected to continue
        // while the user is elsewhere, and a run tied to the screen would be cancelled the moment
        // the ViewModel is cleared. AgentTaskService keeps the process alive for the duration.
        generationJob = container.appScope.launch {
            val conversationCharacters = requestMessages.sumOf { it.content.length }
            val latestRequest = requestMessages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
            val workload = RoutingWorkloadClassifier.classify(latestRequest, conversationCharacters)
            preferredLocalProfile(snapshot, workload)?.let { prepareLocalProfile(it) }
            val runSnapshot = mutableState.value
            val plan = routeSelection(
                runSnapshot,
                snapshot.privacyClass,
                preferQuality = workload == RoutingWorkload.DEMANDING,
            )
            if (plan.isEmpty()) {
                mutableState.update {
                    it.copy(
                        isGenerating = false,
                        status = null,
                        error = "No profile is ready. Check the Primary profile and conversation privacy.",
                    )
                }
                generationJob = null
                return@launch
            }
            var failure: String? = null
            var stopped = false
            for ((index, selection) in plan.withIndex()) {
                if (index > 0) {
                    mutableState.update { it.copy(status = "Falling back to ${selection.runtime.model.displayName}…") }
                }
                when (val outcome = runTurnAttempt(selection, runSnapshot, requestMessages)) {
                    is TurnOutcome.Completed -> { stopped = false; break }
                    is TurnOutcome.Failed -> {
                        failure = outcome.reason
                        if (outcome.localAttemptFailed) {
                            // A local failure means the model is suspect even when the fallback
                            // succeeds; treat it as unloaded rather than showing a stale warm model.
                            val failedId = outcome.localRuntimeId
                            mutableState.update { current ->
                                if (failedId != null && current.litertlmLoadedId == failedId) {
                                    current.copy(
                                        litertlmLoadedId = null,
                                        litertlmLoadedBackend = null,
                                        liteRtLoadDetail = null,
                                    )
                                } else {
                                    current.copy(
                                        loadedModelId = if (failedId == null || current.loadedModelId == failedId) {
                                            null
                                        } else {
                                            current.loadedModelId
                                        },
                                        cpuValidated = if (failedId == null || current.loadedModelId == failedId) {
                                            false
                                        } else {
                                            current.cpuValidated
                                        },
                                    )
                                }
                            }
                        }
                    }
                    is TurnOutcome.Cancelled -> { stopped = true; break }
                }
            }
            if (stopped) {
                mutableState.update { it.copy(status = "Generation stopped") }
            } else if (failure != null) {
                mutableState.update {
                    it.copy(error = failure, status = "Bram could not prepare an eligible fallback.")
                }
            }
        }
    }

    /**
     * One agent run on one runtime, to an outcome. A failure ends the turn here cleanly — the
     * caller decides whether a policy-allowed fallback runtime gets the same messages.
     */
    private suspend fun runTurnAttempt(
        selection: RuntimeSelection,
        snapshot: AppUiState,
        requestMessages: List<ConversationMessage>,
    ): TurnOutcome {
        // The status service already runs for a loaded model; remote turns start it now and it is
        // stopped again in the finally. Either way the notification says what is happening.
        pushModelStatus(ModelPhase.PREPARING)
        val agent = container.agent()
        // Reported by the runtime before any text arrives, since only it knows what the loaded
        // chat template uses. Until it does, an empty format leaves the stream alone rather
        // than splitting it on tags that may not be this model's.
        var reasoningFormat = ReasoningFormat()
        var assistantText = ""
        var completedMessage: ConversationMessage? = null
        val activity = mutableListOf<AgentActivity>()
        var thinkingStartedAt = 0L
        var thinkingMillisTotal = 0L
        // Parsing is per round, not per turn. A format that reports no opening marker — LFM2.5
        // among them — has the prompt open the reasoning block, and the prompt does that again for
        // every segment after a tool result. Reading the whole turn as one string therefore treated
        // only the first block as reasoning and spilled every later one, its closing marker, and
        // the bare call text into the visible answer.
        val visibleParts = mutableListOf<String>()
        var roundText = ""
        var roundReasoningRecorded = 0
        var thinking = false
        var failure: String? = null
        return try {
            agent.run(
                request = AgentRunRequest(
                    conversationId = conversationId,
                    messages = requestMessages,
                    identity = BramDefaults.IDENTITY,
                    maxOutputTokens = minOf(2_048, selection.runtime.model.contextWindowTokens / 4),
                    sampler = snapshot.activeProfile?.sampler ?: SamplerSettings(),
                    profileInstructions = runInstructions(snapshot),
                ),
                runtime = selection.runtime,
            ).collect { event ->
                    when (event) {
                        is AgentEvent.Status -> {
                            mutableState.update { it.copy(status = event.text) }
                            pushModelStatus(ModelPhase.GENERATING)
                        }
                        is AgentEvent.Reasoning -> reasoningFormat = event.format
                        is AgentEvent.ContextPrepared -> mutableState.update {
                            it.copy(
                                lastContextTokens = event.estimatedInputTokens,
                                sessionInputTokens = it.sessionInputTokens + event.estimatedInputTokens,
                                status = buildString {
                                    append("Context: ${event.estimatedInputTokens} tokens")
                                    if (event.omittedMessageCount > 0) {
                                        append(" · ${event.omittedMessageCount} older messages omitted")
                                    }
                                },
                            )
                        }
                        is AgentEvent.TextDelta -> {
                            assistantText += event.text
                            roundText += event.text
                            val streaming = streamingReply(roundText, reasoningFormat)
                            val now = System.currentTimeMillis()
                            // A block that has just closed keeps the time it actually took; leaving
                            // it on the running clock would have every finished block claim the
                            // duration of the whole turn.
                            while (roundReasoningRecorded < streaming.closedReasoning.size) {
                                val index = roundReasoningRecorded
                                val took = if (thinkingStartedAt > 0) now - thinkingStartedAt else 0L
                                // Appended to the same list the tool calls go into, so the rows read
                                // in the order the model did things: thought, called, thought again.
                                activity += AgentActivity.Thinking(
                                    text = streaming.closedReasoning[index],
                                    durationMillis = took,
                                    inProgress = false,
                                )
                                roundReasoningRecorded += 1
                                thinkingMillisTotal += took
                                thinkingStartedAt = 0L
                            }
                            // Say "Thinking…" while the block is still open rather than waiting for
                            // it to close. On a slow device that wait is long, and a blank reply
                            // with no explanation looks like a stall.
                            val inFlight = streaming.openReasoning?.let { reasoning ->
                                if (thinkingStartedAt == 0L) thinkingStartedAt = now
                                AgentActivity.Thinking(
                                    text = reasoning,
                                    durationMillis = now - thinkingStartedAt,
                                    inProgress = true,
                                )
                            }
                            if (inFlight != null && !thinking) {
                                thinking = true
                                pushModelStatus(ModelPhase.THINKING)
                            } else if (inFlight == null && thinking) {
                                thinking = false
                                pushModelStatus(ModelPhase.GENERATING)
                            }
                            mutableState.update {
                                it.copy(
                                    messages = requestMessages + ConversationMessage(
                                        role = MessageRole.ASSISTANT,
                                        content = (visibleParts + streaming.visibleText).joinToString("").trim(),
                                        activity = activity + listOfNotNull(inFlight),
                                    ),
                                )
                            }
                        }
                        is AgentEvent.ToolStarted -> {
                            // The round that produced this call is over. Keep whatever prose it
                            // wrote, drop the call itself — the row below says it better than
                            // `[web_fetch(url='…')]` sitting in the middle of the answer does.
                            val finished = streamingReply(roundText, reasoningFormat)
                            stripBareCalls(finished.visibleText).takeIf(String::isNotBlank)
                                ?.let { visibleParts += it }
                            roundText = ""
                            roundReasoningRecorded = 0
                            activity += AgentActivity.ToolInvocation(
                                id = event.call.id,
                                name = event.call.name,
                                argumentsJson = event.call.argumentsJson,
                            )
                            mutableState.update {
                                it.copy(
                                    status = "Running ${event.call.name}…",
                                    messages = requestMessages + inFlightMessage(
                                        (visibleParts + streamingReply(roundText, reasoningFormat).visibleText)
                                            .joinToString("").trim(),
                                        activity,
                                    ),
                                )
                            }
                            pushModelStatus(ModelPhase.CALLING_TOOL)
                        }
                        is AgentEvent.ToolFinished -> {
                            val index = activity.indexOfLast { entry ->
                                entry is AgentActivity.ToolInvocation && entry.id == event.call.id
                            }
                            if (index >= 0) {
                                val started = activity[index] as AgentActivity.ToolInvocation
                                activity[index] = started.copy(result = event.result)
                            }
                            mutableState.update {
                                it.copy(
                                    status = null,
                                    messages = requestMessages + inFlightMessage(
                                        (visibleParts + streamingReply(roundText, reasoningFormat).visibleText)
                                            .joinToString("").trim(),
                                        activity,
                                    ),
                                )
                            }
                            pushModelStatus(ModelPhase.GENERATING)
                            // A proposed skill is persisted the moment the tool returns; refresh so the
                            // draft shows in Settings without waiting for an app restart.
                            if (event.call.name == "propose_skill") refreshSkills()
                        }
                        is AgentEvent.Usage -> mutableState.update { it.copy(lastUsage = event.usage) }
                        is AgentEvent.Metrics -> mutableState.update {
                            it.copy(
                                lastMetrics = event.metrics,
                                sessionOutputTokens = it.sessionOutputTokens + event.metrics.outputTokens,
                            )
                        }
                        is AgentEvent.Completed -> {
                            completedMessage = event.message
                            // Extraction runs in the orchestrator just before Completed, so the new
                            // memory is already stored — refresh so the browser reflects it live.
                            refreshMemories()
                        }
                        is AgentEvent.Failed -> failure = event.message
                    }
                }
            when {
                completedMessage != null -> TurnOutcome.Completed
                failure != null -> TurnOutcome.Failed(failure, selection.isLocal, selection.localModel?.id?.value ?: selection.litertlmModel?.id?.value)
                else -> TurnOutcome.Failed("The run ended without producing a reply.", selection.isLocal, selection.localModel?.id?.value ?: selection.litertlmModel?.id?.value)
            }
        } catch (_: CancellationException) {
            TurnOutcome.Cancelled
        } catch (error: Throwable) {
            TurnOutcome.Failed(error.message ?: error::class.java.simpleName, selection.isLocal, selection.localModel?.id?.value ?: selection.litertlmModel?.id?.value)
        } finally {
                // Separate reasoning from the answer once the reply is complete: the transcript
                // shows thinking collapsed, and mid-stream the split is not yet determinable.
                val rawReply = completedMessage?.content ?: assistantText
                val reply = if (rawReply.isBlank() || !selection.isLocal) {
                    rawReply to ""
                } else {
                    runCatching {
                        val parsed = container.llamaCppClient.parseReply(rawReply)
                        val parsedReasoning = parsed.optString("reasoning")
                        if (parsedReasoning.isNotBlank()) {
                            parsed.optString("content").ifBlank { rawReply } to parsedReasoning
                        } else {
                            // The structured parser found no reasoning. Some formats it cannot
                            // describe (LFM2.5 among them) still mark reasoning in the text, so fall
                            // back to the same split the stream uses rather than leaving the markers
                            // inline in the saved answer.
                            val split = streamingReply(rawReply, reasoningFormat)
                            val reasoning = (split.closedReasoning + listOfNotNull(split.openReasoning))
                                .joinToString("\n\n").trim()
                            split.visibleText.ifBlank { rawReply } to reasoning
                        }
                    }.getOrDefault(rawReply to "")
                }
                // Every block the model opened, including one it never closed.
                val thinkingMillis = thinkingMillisTotal + if (thinkingStartedAt > 0) {
                    System.currentTimeMillis() - thinkingStartedAt
                } else {
                    0L
                }
                val finalActivity = buildList {
                    reply.second.takeIf(String::isNotBlank)?.let {
                        add(AgentActivity.Thinking(it, durationMillis = thinkingMillis))
                    }
                    addAll(activity)
                }
                val settled = when {
                    reply.first.isNotBlank() || finalActivity.isNotEmpty() ->
                        requestMessages + (completedMessage ?: ConversationMessage(
                            role = MessageRole.ASSISTANT,
                            content = reply.first,
                        )).copy(content = reply.first, activity = finalActivity)
                    else -> requestMessages
                }
                mutableState.update {
                    it.copy(messages = settled, isGenerating = false, status = null)
                }
                // Persist whatever the turn produced, including a reply that was stopped part way,
                // so the thread on disk matches what is on screen.
                persistActiveConversation(settled)
                generationJob = null
                if (mutableState.value.loadedModelId != null || mutableState.value.litertlmLoadedId != null) {
                    // A loaded model keeps the status service alive; the turn just went idle.
                    pushModelStatus(ModelPhase.IDLE)
                } else {
                    // A remote turn holds the service only for its own duration.
                    AgentTaskService.stop(container.appContext)
                }
                if (!appForeground && container.notificationSettings.completionAlertsEnabled()) {
                    val turnName = selection.localModel?.displayName
                        ?: selection.runtime.model.displayName
                    val summary = reply.first.ifBlank { turnName }
                    AgentTaskService.postCompletion(container.appContext, model = turnName, summary = summary)
                }
                refreshDeviceProfile()
            }
        }

    /**
     * The result of one agent run on one runtime. [Failed.localAttemptFailed] lets the caller
     * treat a crashed local model as unloaded even when a fallback runtime finished the turn.
     * [Failed.localRuntimeId] says which local record failed, so the right one of the llama.cpp
     * model and the LiteRT package is dropped.
     */
    private sealed interface TurnOutcome {
        data object Completed : TurnOutcome
        data class Failed(
            val reason: String,
            val localAttemptFailed: Boolean,
            val localRuntimeId: String? = null,
        ) : TurnOutcome
        data object Cancelled : TurnOutcome
    }

    /**
     * @param forget whether to also drop the model from the startup restore. An unload the user
     *   asked for should not come back by itself next launch; one the app does to free the runtime
     *   for a moment should.
     */
    private suspend fun unloadModelInternal(forget: Boolean = true) {
        runCatching { container.llamaCppClient.unload() }
        if (forget) runCatching { container.localModelStore.setLastLoadedModelId(null) }
        mutableState.update {
            it.copy(
                loadedModelId = null,
                loadedBackend = null,
                cpuValidated = false,
                modelLoadDetail = null,
                status = null,
            )
        }
        // No model left to hold a foreground service for.
        AgentTaskService.stop(container.appContext)
        refreshDeviceProfile()
    }

    private fun reloadCatalogs() {
        viewModelScope.launch {
            val models = container.localModelStore.list()
            val litertlm = container.liteRtLmStore.list()
            val endpoints = container.endpointStore.list()
            mutableState.update { current ->
                val selected = current.selectedRuntimeId?.takeIf { id ->
                    models.any { it.id.value == id } ||
                        litertlm.any { it.id.value == id } ||
                        endpoints.any { remoteRuntimeId(it.id) == id }
                } ?: models.firstOrNull()?.id?.value
                    ?: litertlm.firstOrNull()?.id?.value
                    ?: endpoints.firstOrNull()?.let { remoteRuntimeId(it.id) }
                current.copy(
                    localModels = models,
                    litertlmModels = litertlm,
                    endpoints = endpoints,
                    selectedRuntimeId = selected,
                    error = null,
                )
            }
            // Profiles first: the restore below reads them, and a model imported before profiles
            // existed needs its default written before it can be loaded through one.
            syncProfiles(models)
            // This is the startup path. Without this the last model was only reopened after an
            // import, so every launch after the first one landed on an empty chat that silently
            // refused to send.
            restoreLastModel()
        }
    }

    /**
     * Loads whatever was open last time.
     *
     * Being met by an import prompt on every launch is wrong when a model is already sitting on
     * disk: the common case is continuing with what was being used, so that is what happens unless
     * it fails.
     */
    private fun restoreLastModel() {
        viewModelScope.launch {
            val current = mutableState.value
            if (current.loadedModelId != null || current.litertlmLoadedId != null ||
                current.isLoadingModel || current.isLoadingLiteRt
            ) {
                return@launch
            }
            // Prefer the profile: it restores the context size, processor, and sampling as well as
            // the file. The model id remains the fallback for a catalog written before profiles.
            val lastProfileId = runCatching { container.modelProfileStore.lastUsedProfileId() }
                .getOrNull()
            val profile = current.profiles.firstOrNull { it.id == lastProfileId }
            if (profile != null) {
                selectLocalModel(profile.modelId.value)
                loadProfile(profile.id)
                return@launch
            }
            val lastId = runCatching { container.localModelStore.lastLoadedModelId() }.getOrNull()
            val model = lastId?.let { id -> current.localModels.firstOrNull { it.id.value == id } }
            if (model != null) {
                selectLocalModel(model.id.value)
                loadModel(model.id.value)
                return@launch
            }
            // A LiteRT package holds no profile; the package's own last-loaded id is all there is.
            val liteRtLastId = runCatching { container.liteRtLmStore.lastLoadedPackageId() }.getOrNull()
            val liteRtPackage = liteRtLastId
                ?.let { id -> current.litertlmModels.firstOrNull { it.id.value == id } }
            if (liteRtPackage != null) {
                selectLocalModel(liteRtPackage.id.value)
                loadLiteRtModel(liteRtPackage.id.value)
            }
        }
    }

    private fun reloadLocalModels(selectId: String? = null, configureImported: Boolean = false) {
        viewModelScope.launch {
            val models = container.localModelStore.list()
            mutableState.update { current ->
                val selected = selectId
                    ?: current.selectedRuntimeId?.takeIf { id ->
                        models.any { it.id.value == id } ||
                            current.litertlmModels.any { it.id.value == id } ||
                            id.startsWith(REMOTE_PREFIX)
                    }
                    ?: models.firstOrNull()?.id?.value
                current.copy(localModels = models, selectedRuntimeId = selected, error = null)
            }
            refreshModelStorage()
            syncProfiles(models)
            val importedProfile = selectId?.let { modelId ->
                mutableState.value.profiles.firstOrNull {
                    it.modelId.value == modelId && it.autoConfiguredAtEpochMillis == 0L
                }
            }
            if (configureImported && importedProfile != null) {
                autoConfigure(importedProfile.id)
            } else {
                restoreLastModel()
            }
        }
    }

    private fun reloadLiteRtModels(selectId: String? = null) {
        viewModelScope.launch {
            val packages = container.liteRtLmStore.list()
            mutableState.update { current ->
                val selected = selectId
                    ?: current.selectedRuntimeId?.takeIf { id ->
                        current.localModels.any { it.id.value == id } ||
                            packages.any { it.id.value == id } ||
                            id.startsWith(REMOTE_PREFIX)
                    }
                    ?: packages.firstOrNull()?.id?.value
                current.copy(litertlmModels = packages, selectedRuntimeId = selected, error = null)
            }
            refreshModelStorage()
            restoreLastModel()
        }
    }

    private fun reloadEndpoints(selectId: String? = null) {
        viewModelScope.launch {
            val endpoints = container.endpointStore.list()
            mutableState.update { current ->
                val requested = selectId?.let(::remoteRuntimeId)
                val selected = requested
                    ?: current.selectedRuntimeId?.takeIf { id ->
                        !id.startsWith(REMOTE_PREFIX) || endpoints.any { remoteRuntimeId(it.id) == id }
                    }
                    ?: endpoints.firstOrNull()?.let { remoteRuntimeId(it.id) }
                current.copy(endpoints = endpoints, selectedRuntimeId = selected, error = null)
            }
        }
    }

    /**
     * Routes one run: builds a candidate for every usable runtime (the loaded GGUF, and each remote
     * endpoint), asks the rule-based router for the best pick given the routing mode and this
     * conversation's privacy class, and returns the ordered list of runtimes — first choice, then
     * policy-allowed fallbacks. Empty when nothing passed the hard gates.
     */
    private fun routeSelection(
        snapshot: AppUiState,
        privacyClass: PrivacyClass,
        preferQuality: Boolean = false,
    ): List<RuntimeSelection> {
        val assignedTargets = snapshot.routingPool.targetIds
        val assignedLocalModels = snapshot.profiles
            .filter { it.id in assignedTargets }
            .map { it.modelId }
            .toSet()
        val pairs = buildList {
            snapshot.localModels
                .filter { assignedTargets.isEmpty() || it.id in assignedLocalModels }
                .forEach { model ->
                val available = snapshot.cpuValidated && model.id.value == snapshot.loadedModelId
                add(
                    RuntimeSelection(
                        runtime = container.runtime(model),
                        localModel = model,
                        routingLabel = snapshot.profileFor(model).name,
                    ) to
                        RoutingEstimates.localCandidate(model, available, snapshot.loadedBackend != null && snapshot.loadedBackend != RuntimeBackend.CPU),
                )
            }
            snapshot.litertlmModels.filter { assignedTargets.isEmpty() }.forEach { record ->
                val available = record.id.value == snapshot.litertlmLoadedId
                add(
                    RuntimeSelection(
                        runtime = container.runtime(record),
                        localModel = null,
                        litertlmModel = record,
                        routingLabel = record.displayName,
                    ) to
                        RoutingEstimates.localCandidate(record, available, snapshot.litertlmLoadedBackend == LiteRtBackend.GPU),
                )
            }
            snapshot.endpoints
                .filter { assignedTargets.isEmpty() || remoteRuntimeId(it.id) in assignedTargets }
                .forEach { endpoint ->
                add(
                    RuntimeSelection(
                        runtime = container.runtime(endpoint),
                        localModel = null,
                        routingLabel = endpoint.displayName,
                    ) to RoutingEstimates.remoteCandidate(endpoint),
                )
            }
        }
        if (pairs.isEmpty()) return emptyList()

        val decision = router.route(
            request = RoutingRequest(
                mode = snapshot.routingMode,
                privacyClass = privacyClass,
                requiredCapabilities = setOf(ModelCapability.TEXT),
                minimumContextTokens = MINIMUM_CONTEXT_TOKENS,
                // Routine work favors the efficient Primary. Demanding work prepares Power first
                // and lets the router weigh remote offload when conversation privacy permits it.
                preferQuality = preferQuality,
                localBias = if (privacyClass == PrivacyClass.PRIVATE_REMOTE_ALLOWED) 2.0 else 1.0,
            ),
            candidates = pairs.map { it.second },
        )
        val ordered = buildList {
            decision.selected?.let { selected ->
                pairs.firstOrNull { it.second.model.id == selected.model.id }?.let { add(it.first) }
            }
            decision.fallbacks.forEach { fallback ->
                pairs.firstOrNull { it.second.model.id == fallback.model.id }?.let { add(it.first) }
            }
        }
        val label = ordered.firstOrNull()?.routingLabel
        if (label != null) {
            mutableState.update { it.copy(lastRoutedRuntimeLabel = label) }
        }
        return ordered
    }

    /** Which local role to prepare before routing; only one llama.cpp model is resident at a time. */
    private fun preferredLocalProfile(snapshot: AppUiState, workload: RoutingWorkload): String? {
        if (snapshot.routingMode == RoutingMode.REMOTE_ONLY) return null
        val preferred = when (workload) {
            RoutingWorkload.ROUTINE -> snapshot.routingPool.primaryTargetId
            RoutingWorkload.DEMANDING -> snapshot.routingPool.powerTargetId
                ?: snapshot.routingPool.primaryTargetId
        }
        return preferred
            ?.takeUnless { it.startsWith(REMOTE_PREFIX) }
            ?.takeIf { id -> snapshot.profiles.any { it.id == id } }
    }

    /** A configured local profile counts as runnable even before its model has been prepared. */
    private fun hasConfiguredRoute(snapshot: AppUiState, privacyClass: PrivacyClass): Boolean {
        val assigned = snapshot.routingPool.targetIds
        if (assigned.isEmpty()) {
            val local = snapshot.routingMode != RoutingMode.REMOTE_ONLY && snapshot.profiles.isNotEmpty()
            val remote = snapshot.routingMode != RoutingMode.LOCAL_ONLY &&
                privacyClass != PrivacyClass.LOCAL_ONLY && snapshot.endpoints.isNotEmpty()
            return local || remote
        }
        val local = snapshot.routingMode != RoutingMode.REMOTE_ONLY &&
            snapshot.profiles.any { it.id in assigned }
        val remote = snapshot.routingMode != RoutingMode.LOCAL_ONLY &&
            privacyClass != PrivacyClass.LOCAL_ONLY &&
            snapshot.endpoints.any { remoteRuntimeId(it.id) in assigned }
        return local || remote
    }

    private fun validate(draft: EndpointDraft): String? {
        if (draft.displayName.isBlank()) return "Provider name is required."
        if (draft.modelName.isBlank()) return "Model name is required."
        if (draft.contextWindowTokens !in 256..10_000_000) {
            return "Context window must be between 256 and 10,000,000 tokens."
        }
        val uri = runCatching { URI(draft.baseUrl.trim()) }.getOrNull() ?: return "Enter a valid endpoint URL."
        if (uri.host.isNullOrBlank()) return "Endpoint URL needs a hostname or IP address."
        if (uri.scheme !in setOf("https", "http")) return "Endpoint must use HTTPS or HTTP."
        if (uri.scheme == "http" && !draft.allowInsecureHttp) {
            return "Enable insecure HTTP for this local endpoint or use HTTPS."
        }
        return null
    }

    override fun onCleared() {
        // Deliberately does not cancel an in-flight run or close the inference connection: the run
        // is owned by the application scope so it can finish and write its reply while the user is
        // elsewhere. Stopping is a user action, not a consequence of the screen going away.
        container.llamaCppClient.processFailureListener = null
        super.onCleared()
    }

    private data class RuntimeSelection(
        val runtime: ModelRuntime,
        val localModel: LocalModelRecord?,
        val litertlmModel: LiteRtModelRecord? = null,
        /** User-facing profile/provider name, which can differ for two profiles of one GGUF. */
        val routingLabel: String,
    ) {
        /** Whether this candidate runs on-device, which changes how a failure is treated. */
        val isLocal: Boolean
            get() = localModel != null || litertlmModel != null
    }
}

internal const val REMOTE_PREFIX = "remote:"
internal fun remoteRuntimeId(endpointId: String): String = "$REMOTE_PREFIX$endpointId"

/** Tokens compared between backends. Long enough to catch drift, short enough to stay quick. */
private const val REFERENCE_TOKENS = 24

/** llama.cpp clamps this to the model's layer count, so it means "offload everything". */
private const val FULL_GPU_OFFLOAD = 999

/** CPU threads for the resident embedding model; small models are fast enough at four. */
private const val EMBEDDER_THREADS = 4

/** How many memories the browser shows — newest first, plenty for review without flooding the panel. */
private const val MEMORY_BROWSER_LIMIT = 200

/** How many standing memories join the system prompt each turn (the char budget trims further). */
private const val MEMORY_INJECTION_LIMIT = 12

private fun Int?.orZero(): Int = this ?: 0

private fun org.json.JSONArray?.toIntList(): List<Int> {
    val array = this ?: return emptyList()
    return (0 until array.length()).map(array::getInt)
}

/**
 * Removes call syntax a model wrote as text.
 *
 * Formats that mark their calls have them stripped by the runtime's parser, but the ones Bram
 * recovers from bare text are still sitting in the reply, so `[web_fetch(url='…')]` ended up in the
 * middle of the answer. The activity row above says the same thing better.
 */
internal fun stripBareCalls(text: String): String =
    text.replace(Regex("""\[?\b\w+\((?:[^()]|\([^()]*\))*\)]?"""), "").trim()
