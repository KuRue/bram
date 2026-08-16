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
import io.github.kurue.bram.core.domain.DimensionTuneNote
import io.github.kurue.bram.core.domain.GenerationMetrics
import io.github.kurue.bram.core.domain.HexFlags
import io.github.kurue.bram.core.domain.KvCacheType
import io.github.kurue.bram.core.domain.FlashAttentionMode
import io.github.kurue.bram.core.domain.LiteRtBackend
import io.github.kurue.bram.core.domain.LITE_RT_CONTEXT_TOKENS
import io.github.kurue.bram.core.domain.LiteRtModelRecord
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.McpServer
import io.github.kurue.bram.core.domain.MeasurementFingerprint
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
import io.github.kurue.bram.core.domain.TuningCandidates
import io.github.kurue.bram.core.domain.TuningDimension
import io.github.kurue.bram.core.domain.TuneCandidateResult
import io.github.kurue.bram.core.agent.RuleBasedModelRouter
import io.github.kurue.bram.platform.android.CpuTopology
import io.github.kurue.bram.platform.android.RoutingSettingsStore
import io.github.kurue.bram.runtime.llamacpp.ModelImportProgress
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import java.util.UUID
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
enum class AutoConfigurePhase { MEASURING, BATCH, TUNING, DONE }

/**
 * One phase of the auto-configure run, known before the first measurement: its candidate rows
 * are listed from the start so the overlay can show every run up front and mark each row as it
 * is measured, instead of the later phases appearing out of nowhere when they finish.
 */
data class TuningPlanPhase(
    val title: String,
    val candidates: List<String>,
    val dimension: TuningDimension? = null,
    val isBackends: Boolean = false,
    val isBatch: Boolean = false,
    /**
     * Live per-row results, parallel to [candidates]: a row flips to its outcome the moment the
     * sweep decides it (timed out, measured) instead of waiting for the whole sweep's note to
     * land. The landed note's results take precedence once they exist.
     */
    val results: List<TuneCandidateResult> = emptyList(),
)

data class AutoConfigureProgress(
    val profileId: String,
    val modelName: String,
    val step: Int,
    val total: Int,
    val current: String,
    /** Every accelerator candidate, in measurement order, so the dialog can show all of them. */
    val candidates: List<String> = emptyList(),
    val results: List<BackendMeasurement> = emptyList(),
    /**
     * Which candidate (by [candidates] index) is being measured right now, or null between
     * candidates, during the batch phase, or when finished. The overlay marks exactly one row
     * "Measuring…" off this rather than off [current], which carries the live callback text and so
     * would make every row flicker between Measuring and Waiting mid-measurement.
     */
    val measuringIndex: Int? = null,
    /** The tuning dimensions that have landed so far, newest first, for the overlay's charts. */
    val dimensions: List<DimensionTuneNote> = emptyList(),
    val phase: AutoConfigurePhase = AutoConfigurePhase.MEASURING,
    val finished: Boolean = false,
    /**
     * The device's live thermal status ("none" … "critical") refreshed while the run is in
     * flight, and whether the run is parked waiting for it to return to a measurable range.
     * The wait is automatic; the overlay offers Continue-anyway for the impatient.
     */
    val thermalStatus: String = "",
    val waitingForCooldown: Boolean = false,
    /** Every phase and its candidate rows, fixed before the first measurement. */
    val plan: List<TuningPlanPhase> = emptyList(),
    /** The plan row being measured now: phase and candidate index, or null between phases. */
    val measuringPlanPhase: Int? = null,
    val measuringPlanCandidate: Int? = null,
    /** When the run started; notes older than this belong to earlier sessions. */
    val startedAtEpochMillis: Long = System.currentTimeMillis(),
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
    /** Prompt and decode millis of the reference run on each side, for absolute tok/s. */
    val promptTokens: Int = 0,
    val cpuPromptMillis: Long = 0L,
    val cpuDecodeMillis: Long = 0L,
    val acceleratorPromptMillis: Long = 0L,
    val acceleratorDecodeMillis: Long = 0L,
) {
    val speedup: Double
        get() = if (acceleratorMillis > 0) cpuMillis.toDouble() / acceleratorMillis.toDouble() else 0.0

    val cpuPromptTokPerSec: Double
        get() = if (cpuPromptMillis > 0 && promptTokens > 0) promptTokens * 1000.0 / cpuPromptMillis else 0.0

    val cpuDecodeTokPerSec: Double
        get() = if (cpuDecodeMillis > 0 && cpuTokens.isNotEmpty()) cpuTokens.size * 1000.0 / cpuDecodeMillis else 0.0

    val acceleratorPromptTokPerSec: Double
        get() = if (acceleratorPromptMillis > 0 && promptTokens > 0) {
            promptTokens * 1000.0 / acceleratorPromptMillis
        } else 0.0

    val acceleratorDecodeTokPerSec: Double
        get() = if (acceleratorDecodeMillis > 0 && acceleratorTokens.isNotEmpty()) {
            minOf(acceleratorTokens.size, cpuTokens.size) * 1000.0 / acceleratorDecodeMillis
        } else 0.0
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

/** Builds the persisted winning-backend summary without treating its literal percent as a format token. */
internal fun successfulAutoConfigureNote(
    backendLabel: String,
    promptTokensPerSecond: Double,
    decodeTokensPerSecond: Double,
    agreement: Double,
    measuredOn: String,
): String = "$backendLabel: ${promptTokensPerSecond.toInt()} prompt tok/s, " +
    "${decodeTokensPerSecond.toInt()} decode tok/s, " +
    "${(agreement * 100).toInt()}% agreement. Measured $measuredOn."

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
    /**
     * Messages the user sent while a turn was already running. They are held here — not in
     * [messages], which the in-flight turn overwrites as it settles — and each one starts a
     * fresh turn as soon as the previous reply finishes.
     */
    val queuedMessages: List<String> = emptyList(),
    /** Current model phase, shared by the in-app instrument and foreground notification. */
    val modelPhase: ModelPhase = ModelPhase.IDLE,
    /** Rolling decode rate while a reply is streaming; final runtime metrics replace it. */
    val liveDecodeTokensPerSecond: Double? = null,
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
    /** A non-batch dimension tune in flight, so the card can show which row is measuring. */
    val tuningProfileId: String? = null,
    val tuningDimension: TuningDimension? = null,
    /**
     * The fingerprint measurements are recorded under — device, app build, engine build, and
     * CPU features. A profile whose [ModelProfile.measuredFingerprint] differs was measured
     * somewhere else and the card says so.
     */
    val measurementFingerprint: String = "",
    /** A quant conversion in flight, so the card can show which model is being converted. */
    val convertingProfileId: String? = null,
    /** Present while auto-configure runs. The dialog is shown for exactly as long as this is. */
    val autoConfigure: AutoConfigureProgress? = null,
    /**
     * Set by the overlay's Continue-anyway button: measures through the thermal pause for the
     * rest of this pass. Reset when the pass or the tune finishes.
     */
    val cooldownOverride: Boolean = false,
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
    /** The same run's decode phase, for the decode tok/s the bars show. */
    val decodeMillis: Long,
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
    /**
     * Drafted-skill ids already nudged in the current conversation, so a relevant draft is suggested
     * at most once per thread. Cleared when the conversation changes; a draft that activates or
     * re-versions is handled by it no longer being a draft (or the caller deduping on id).
     */
    private val hintedDraftSkills = mutableSetOf<String>()
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
        // Tasks are unattended and independent: their Termux calls stay stateless, so a task never
        // inherits a chat conversation's remembered working directory.
        container.termuxTool.setSession(null)
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
                maxOutputTokens = minOf(1_024, selection.runtime.model.contextWindowTokens / 8),
                sampler = snapshot.activeProfile?.sampler ?: SamplerSettings(),
                profileInstructions = runInstructions(snapshot, selection.runtime.model.contextWindowTokens),
            )
            try {
                container.agent().run(request, selection.runtime).collect { event ->
                    when (event) {
                        is AgentEvent.Status -> Unit
                        is AgentEvent.ToolsSelected -> {
                            android.util.Log.d("BramTools", "offering ${event.names.size} tools: ${event.names.joinToString()}")
                        }
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
            // Only adopted on a fresh start. A turn already running here — or a message the user
            // sent while the disk read was in flight — owns the transcript; swapping in the stale
            // disk copy underneath it would hide the in-flight reply, drop the prompt, and reset
            // the session counters mid-run.
            val initial = mutableState.value
            if (initial.isGenerating || initial.messages.isNotEmpty()) return@launch
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
            var applied = false
            mutableState.update {
                if (it.isGenerating || it.messages.isNotEmpty()) {
                    it
                } else {
                    applied = true
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
            if (applied) {
                mostRecent?.let { conversationId = it.id }
                container.approvalGate.setMode(mode)
            }
        }
    }

    /** Persists after each completed turn; a crash mid-generation loses only the partial reply. */
    private fun persistActiveConversation(messages: List<ConversationMessage>) {
        if (messages.isEmpty()) return
        viewModelScope.launch {
            runCatching { container.conversationStore.save(conversationId, messages) }
                .onSuccess { summary ->
                    // The first save creates the file, which is the earliest a per-conversation
                    // setting can persist — so a mode or privacy class chosen before the first
                    // message is written now, rather than surviving only in memory.
                    val snapshot = mutableState.value
                    if (snapshot.permissionMode != PermissionMode.AUTO) {
                        runCatching { container.conversationStore.setPermissionMode(summary.id, snapshot.permissionMode) }
                    }
                    if (snapshot.privacyClass != PrivacyClass.STANDARD) {
                        runCatching { container.conversationStore.setPrivacyClass(summary.id, snapshot.privacyClass) }
                    }
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
        hintedDraftSkills.clear()
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
            hintedDraftSkills.clear()
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
        viewModelScope.launch {
            mutableState.update { it.copy(availableBackends = detectBackendsNow()) }
            // The measurement fingerprint is how the card knows a profile was measured on this
            // device and build; compute it at startup too, or every measured profile would look
            // stale until the first sweep refreshes it.
            refreshMeasurementFingerprint()
        }
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

    /**
     * Recomputes the fingerprint measurements are recorded under: the device identity from the
     * profiler, the app build, the llama.cpp commit, and the CPU ISA features the engine
     * reports. A measurement is only meaningful while every part matches.
     */
    private suspend fun refreshMeasurementFingerprint() {
        refreshDeviceProfile()
        val device = mutableState.value.deviceProfile?.profileFingerprint.orEmpty()
        val appBuild = runCatching {
            val info = container.appContext.packageManager.getPackageInfo(
                container.appContext.packageName,
                0,
            )
            "${info.versionName.orEmpty()}/${info.longVersionCode}"
        }.getOrDefault("")
        val probe = runCatching { container.llamaCppClient.probe() }.getOrNull()
        val engine = probe?.optString("llamaCppCommit").orEmpty()
        val systemInfo = probe?.optString("systemInfo").orEmpty()
        val key = MeasurementFingerprint(
            device = device,
            appBuild = appBuild,
            engineBuild = engine,
            cpuFeatures = MeasurementFingerprint.cpuFeatureFlags(systemInfo),
        ).key
        mutableState.update { it.copy(measurementFingerprint = key) }
    }

    /**
     * Refuses sweeps that would measure through memory pressure: a number taken while the device
     * is swapping is a lie with a timestamp. Thermal throttling is not a refusal here — the sweep
     * parks on it via [waitForCooldown] and resumes by itself. Returns the reason, or null when
     * measuring is allowed.
     */
    private suspend fun sweepGate(): String? {
        refreshDeviceProfile()
        val profile = mutableState.value.deviceProfile ?: return null
        if (profile.availableRamBytes < MIN_SWEEP_RAM_BYTES) {
            return "Not enough free memory to measure reliably " +
                "(${profile.availableRamBytes / 1_048_576L} MB free); close some apps and try again."
        }
        return null
    }

    /**
     * Parks the sweep while the device is thermally throttled: a decode measured through
     * throttling can be hundreds of times slower than the cool number, and the winner would be a
     * lie with a timestamp. Polls the thermal status on a background-thread delay (the same
     * pattern as the candidate watches, which cannot be defeated by the coroutine cancellation
     * machinery) and resumes by itself once the status returns to none/light. The overlay's
     * Continue-anyway button sets [BramState.cooldownOverride], which skips the wait for the rest
     * of the pass.
     */
    private suspend fun waitForCooldown(): Boolean {
        var waiting = false
        while (true) {
            refreshDeviceProfile()
            val status = mutableState.value.deviceProfile?.thermalStatus.orEmpty()
            val blocked = status.isNotEmpty() &&
                status !in ALLOWED_SWEEP_THERMAL &&
                !status.startsWith("unknown")
            val override = mutableState.value.cooldownOverride
            if (blocked && !override && !waiting) {
                android.util.Log.d("BramTune", "pausing for cooldown (thermal=$status)")
                waiting = true
            }
            mutableState.update {
                it.copy(
                    status = if (blocked && !override) {
                        "Waiting for the phone to cool (thermal: $status)…"
                    } else {
                        it.status
                    },
                    autoConfigure = it.autoConfigure?.copy(
                        thermalStatus = status,
                        waitingForCooldown = blocked && !override,
                        current = if (blocked && !override) {
                            "Waiting for the phone to cool (thermal: $status)…"
                        } else {
                            it.autoConfigure.current
                        },
                    ),
                )
            }
            if (!blocked || override) {
                if (blocked) {
                    android.util.Log.d("BramTune", "cooldown overridden; measuring while thermal=$status")
                } else if (waiting) {
                    android.util.Log.d("BramTune", "cooldown over; resuming (thermal=$status)")
                }
                return true
            }
            withContext(Dispatchers.Default) { Thread.sleep(15_000L) }
        }
    }

    fun overrideCooldown() {
        mutableState.update { it.copy(cooldownOverride = true) }
    }

    /**
     * Watches a thread against a wall-clock deadline. The whole loop runs inside a single
     * [withContext] on [Dispatchers.Default] and uses [Thread.sleep] rather than per-iteration
     * [delay]: the old pattern (four [withContext] round-trips per second) intermittently lost
     * a resumption under sustained CPU load — exactly when a hanging candidate spins full-tilt
     * for its probe window — and the sweep wedged silently. One round-trip + [Thread.sleep]
     * cannot be defeated by the coroutine cancellation machinery.
     */
    private suspend fun watchThread(
        thread: Thread,
        deadlineMillis: Long,
        pollMillis: Long = 500L,
        onTick: (() -> Unit)? = null,
    ) {
        withContext(Dispatchers.Default) {
            var ticks = 0
            val ticksPerRefresh = (15_000L / pollMillis).toInt().coerceAtLeast(1)
            while (thread.isAlive && System.currentTimeMillis() < deadlineMillis) {
                if (onTick != null && ++ticks % ticksPerRefresh == 0) onTick()
                Thread.sleep(pollMillis)
            }
        }
    }

    /**
     * Keeps the overlay's thermal line honest while a long candidate runs: the wait happens
     * between candidates, but a measurement can heat the phone enough to matter mid-run, and the
     * indicator should say so even though the running candidate is allowed to finish.
     */
    private fun refreshThermalIndicator() {
        refreshDeviceProfile()
        val status = mutableState.value.deviceProfile?.thermalStatus.orEmpty()
        mutableState.update {
            it.copy(autoConfigure = it.autoConfigure?.copy(thermalStatus = status))
        }
    }

    /**
     * Flips a plan row to "timed out" the moment the sweep abandons it, so the row does not sit
     * on "Measuring…" or fall back to "Waiting" until the whole sweep's note lands.
     */
    private fun markPlanCandidateTimedOut(phaseIndex: Int?, candidateIndex: Int) {
        if (phaseIndex == null || phaseIndex < 0) return
        mutableState.update {
            it.copy(
                autoConfigure = it.autoConfigure?.let { progress ->
                    progress.copy(
                        plan = progress.plan.mapIndexed { index, phase ->
                            if (index != phaseIndex) {
                                phase
                            } else {
                                val results = phase.results.toMutableList()
                                while (results.size <= candidateIndex) {
                                    results += TuneCandidateResult(
                                        label = phase.candidates.getOrNull(results.size).orEmpty(),
                                        promptTokPerSec = 0.0,
                                        decodeTokPerSec = 0.0,
                                        agreed = false,
                                    )
                                }
                                results[candidateIndex] = TuneCandidateResult(
                                    label = phase.candidates.getOrNull(candidateIndex).orEmpty(),
                                    promptTokPerSec = 0.0,
                                    decodeTokPerSec = 0.0,
                                    agreed = false,
                                    timedOut = true,
                                )
                                phase.copy(results = results)
                            }
                        },
                        measuringPlanPhase = null,
                        measuringPlanCandidate = null,
                    )
                },
            )
        }
    }

    fun selectLocalModel(modelId: String) {
        mutableState.update { it.copy(selectedRuntimeId = modelId, error = null) }
    }

    fun selectEndpoint(endpointId: String) {
        mutableState.update { it.copy(selectedRuntimeId = remoteRuntimeId(endpointId), error = null) }
    }

    fun importModel(uri: Uri) = importModels(listOf(uri))

    /**
     * Imports one or more picked documents: a single GGUF, or every part of a multi-part GGUF.
     * The picker allows multiple selection so a split model (published as
     * `name-00001-of-00005.gguf` …) can be picked as a set in one go.
     */
    fun importModels(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (mutableState.value.isImporting) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isImporting = true,
                    importProgress = ModelImportProgress("Opening model"),
                    error = null,
                )
            }
            val isLiteRt = uris.size == 1 && isLiteRtPackage(uris.first())
            runCatching {
                if (isLiteRt) {
                    container.liteRtLmStore.importPackage(uris.first()) { progress ->
                        mutableState.update {
                            it.copy(importProgress = ModelImportProgress(progress.stage, progress.bytesRead, progress.totalBytes))
                        }
                    }.id.value
                } else {
                    container.localModelStore.importModel(uris) { progress ->
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
            snapshot.batchTuneProfileId != null || snapshot.tuningProfileId != null
        ) {
            android.util.Log.d(
                "BramTune",
                "autoConfigure rejected: loading=${snapshot.isLoadingModel} generating=${snapshot.isGenerating} " +
                    "validating=${snapshot.isValidatingAccelerator} batch=${snapshot.batchTuneProfileId != null} " +
                    "tuning=${snapshot.tuningProfileId != null}",
            )
            return
        }
        val restoreLoaded = snapshot.loadedModelId

        viewModelScope.launch(Dispatchers.Default) {
            // Ask the runtime what it can see now rather than trusting what was detected at start.
            // The inference process restarts across loads, and a backend that registered late was
            // missing from the list, so a profile configured for it silently loaded on the CPU.
            // A fresh probe can be temporarily incomplete while the isolated inference process is
            // restarting. Keep capabilities already detected during this app session and merge in
            // anything the new probe finds; otherwise skipping one freshly reported backend (such
            // as Vulkan) can accidentally collapse the sweep to CPU only.
            val backends = (snapshot.availableBackends + detectBackendsNow()).distinct()
            refreshMeasurementFingerprint()
            mutableState.update { it.copy(cooldownOverride = false) }
            sweepGate()?.let { reason ->
                mutableState.update { it.copy(error = reason) }
                return@launch
            }
            waitForCooldown()

            val candidates = backends
                // Vulkan is still available for manual profiles, but its validation path is not
                // reliable enough to spend time on during the one-tap setup pass yet.
                .filter { backend ->
                    backend.offloadsToAccelerator && backend != RuntimeBackend.VULKAN
                }
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
                        plan = autoConfigurePlan(profile, backends, candidates),
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
                        autoConfigure = it.autoConfigure?.copy(
                            step = index,
                            measuringIndex = index,
                            current = backend.label,
                        ),
                    )
                }
                waitForCooldown()
                // A backend measurement can wedge the same way a tuning candidate can — the
                // reference decode and the replay are the same native calls — so it runs on its
                // own thread watched against a wall-clock deadline instead of in the sweep
                // coroutine, where a hang would stall the whole pass with no way out. A backend
                // that times out is recorded as a failure (the overlay says so) and the process
                // restarts for the next one.
                val reportRef = AtomicReference<AcceleratorReport?>()
                val backendThread = thread(name = "bram-tune-backend") {
                    runCatching {
                        runBlocking {
                            reportRef.set(
                                measureBackend(model, profile, target, threads) { message ->
                                    mutableState.update {
                                        it.copy(autoConfigure = it.autoConfigure?.copy(current = message))
                                    }
                                },
                            )
                        }
                    }.onFailure { error ->
                        if (error !is android.os.DeadObjectException) {
                            android.util.Log.d(
                                "BramTune",
                                "backend ${backend.label} error: ${error::class.simpleName}: ${error.message}",
                            )
                        }
                    }
                }
                val backendDeadline = System.currentTimeMillis() + CANDIDATE_TIMEOUT_MILLIS
                watchThread(backendThread, backendDeadline, 500L) { refreshThermalIndicator() }
                val report = if (backendThread.isAlive) {
                    android.util.Log.d("BramTune", "backend ${backend.label} TIMED OUT; restarting the inference process")
                    runCatching { container.llamaCppClient.restartInferenceProcess() }
                    null
                } else {
                    reportRef.get()
                }
                if (report != null) measured += backend to report
                val result = BackendMeasurement(
                    backendId = backend.name,
                    label = backend.label,
                    agrees = report?.matchesCpu == true,
                    agreement = report?.agreement ?: 0.0,
                    speedup = report?.speedup ?: 0.0,
                    promptTokPerSec = report?.acceleratorPromptTokPerSec ?: 0.0,
                    decodeTokPerSec = report?.acceleratorDecodeTokPerSec ?: 0.0,
                )
                // The CPU row is the reference every other result is expressed against, so it is
                // in the list from the start — its throughput arrives with the first measurement
                // that recorded the CPU side.
                if (report != null) {
                    val cpuTiming = report
                    mutableState.update {
                        it.copy(
                            autoConfigure = it.autoConfigure?.let { progress ->
                                progress.copy(results = progress.results.map { entry ->
                                    if (entry.isReference) {
                                        entry.copy(
                                            promptTokPerSec = cpuTiming.cpuPromptTokPerSec,
                                            decodeTokPerSec = cpuTiming.cpuDecodeTokPerSec,
                                        )
                                    } else {
                                        entry
                                    }
                                })
                            },
                        )
                    }
                }
                mutableState.update {
                    it.copy(
                        autoConfigure = it.autoConfigure?.let { progress ->
                            progress.copy(step = index + 1, results = progress.results + result)
                        },
                    )
                }
            }

            // The winner is the agreeing backend with the highest absolute prompt throughput —
            // the same number the card shows. Ranking by speedup-against-CPU would pick a
            // backend whose bar is visibly smaller than another's when thermal drift shifts the
            // CPU reference between measurements, which reads as a lie on the chart.
            val best = measured
                .filter { (_, report) -> report.matchesCpu }
                .maxByOrNull { (_, report) -> report.acceleratorPromptTokPerSec }
                ?.takeIf { (_, report) ->
                    report.acceleratorPromptTokPerSec > report.cpuPromptTokPerSec
                }

            // When no accelerator exists, no report ever lands to fill the CPU row's throughput,
            // so the reference decode runs once for its own numbers — the reference bar is part of
            // the comparison either way.
            if (measured.isEmpty()) {
                val cpuTiming = runCatching {
                    container.llamaCppClient.load(
                        model = model.copy(preferredContextTokens = profile.contextTokens),
                        threads = threads,
                        gpuLayers = 0,
                        enableThinking = profile.thinkingEnabled,
                        flashAttention = profile.flashAttention,
                        kvCacheType = profile.kvCacheType,
                    )
                    container.llamaCppClient.referenceDecode(REFERENCE_TOKENS)
                }.getOrNull()
                if (cpuTiming != null) {
                    mutableState.update {
                        it.copy(
                            autoConfigure = it.autoConfigure?.let { progress ->
                                progress.copy(results = progress.results.map { entry ->
                                    if (entry.isReference) {
                                        entry.copy(
                                            promptTokPerSec = if (cpuTiming.optLong("promptMillis", 0L) > 0) {
                                                val promptTokens = cpuTiming.optInt("promptTokens", 0)
                                                if (promptTokens > 0) promptTokens * 1000.0 / cpuTiming.optLong("promptMillis") else 0.0
                                            } else 0.0,
                                            decodeTokPerSec = if (cpuTiming.optLong("decodeMillis", 0L) > 0) {
                                                REFERENCE_TOKENS * 1000.0 / cpuTiming.optLong("decodeMillis")
                                            } else 0.0,
                                        )
                                    } else {
                                        entry
                                    }
                                })
                            },
                        )
                    }
                }
            }

            val note = when {
                best != null -> {
                    val (backend, report) = best
                    successfulAutoConfigureNote(
                        backendLabel = backend.label,
                        promptTokensPerSecond = report.acceleratorPromptTokPerSec,
                        decodeTokensPerSecond = report.acceleratorDecodeTokPerSec,
                        agreement = report.agreement,
                        measuredOn = today(),
                    )
                }
                measured.isEmpty() -> "No accelerator to measure, so this runs on the CPU."
                // A backend can agree with the reference and still be slower than it — agreement
                // gates correctness, speed chooses the winner, and "slower" is a result too.
                measured.any { it.second.matchesCpu } -> "Every accelerator agreed with the CPU but " +
                    "none beat it, so this runs on the CPU."
                else -> "No accelerator agreed with the CPU, so this runs on the CPU."
            }

            val results = mutableState.value.autoConfigure?.results.orEmpty()
            container.modelProfileStore.save(
                profile.copy(
                    backendId = best?.first?.takeIf { it != RuntimeBackend.CPU }?.name.orEmpty(),
                    // The Hexagon flags only mean anything on the NPU; leaving them on a profile
                    // that now runs elsewhere would still set the process environment on load.
                    hexFlags = if (best?.first == RuntimeBackend.HEXAGON) profile.hexFlags else HexFlags(),
                    measurements = results.sortedWith(
                        // Winner first, failures last: the order a person reads it in.
                        compareByDescending<BackendMeasurement> { it.agrees }
                            .thenByDescending { it.speedup },
                    ),
                    autoConfiguredNote = note,
                    autoConfiguredAtEpochMillis = System.currentTimeMillis(),
                    measuredFingerprint = mutableState.value.measurementFingerprint,
                ),
            )
            runCatching { unloadModelInternal(forget = false) }
            mutableState.update {
                it.copy(isValidatingAccelerator = false, status = null)
            }
            refreshDeviceProfile()
            // The suspend sync rather than the async reload: the tuning phases read the profile
            // list next, and a stale list would let them overwrite this phase's measurements.
            syncProfiles(mutableState.value.localModels, profile.id)

            // Second phase: the decode-shaped dimensions, in dependency order. Each one follows
            // the choice the earlier ones made — threads first, then the mask on top of the
            // winner, then polling, load mode, and the Hexagon flags. A dimension with nothing
            // to compare writes its "why" note and moves on.
            mutableState.update {
                it.copy(
                    autoConfigure = it.autoConfigure?.copy(
                        phase = AutoConfigurePhase.TUNING,
                        measuringIndex = null,
                        measuringPlanPhase = null,
                        measuringPlanCandidate = null,
                        current = "Tuning decode settings…",
                    ),
                )
            }
            val tuningBackend = resolveLoadBackend(
                mutableState.value.profiles.firstOrNull { it.id == profile.id }?.backendId.orEmpty(),
            )
            val dimensions = autoConfigureDimensions(tuningBackend)
            for (dimension in dimensions) {
                runCatching { runDimensionTune(profile.id, dimension, fromAutoConfigure = true) }
                    .onFailure { error ->
                        if (error is CancellationException) {
                            android.util.Log.d("BramTune", "auto-configure cancelled; stopping the pass")
                            return@launch
                        }
                    }
                val landed = mutableState.value.profiles.firstOrNull { it.id == profile.id }?.tuning
                    .orEmpty()
                    .sortedByDescending { it.measuredAtEpochMillis }
                mutableState.update {
                    it.copy(autoConfigure = it.autoConfigure?.copy(dimensions = landed))
                }
            }

            // Third phase: the batch, last so it is measured under the configuration the decode
            // dimensions chose. The best batch depends on the processor; the one button means
            // configure everything, in order.
            mutableState.update {
                it.copy(
                    autoConfigure = it.autoConfigure?.copy(
                        phase = AutoConfigurePhase.BATCH,
                        measuringIndex = null,
                        measuringPlanPhase = null,
                        measuringPlanCandidate = null,
                        current = "Tuning prompt batch…",
                    ),
                )
            }
            runCatching { runBatchTune(profile.id) }.onFailure { error ->
                if (error is CancellationException) {
                    android.util.Log.d("BramTune", "auto-configure cancelled; stopping the pass")
                    return@launch
                }
            }
            val tuned = mutableState.value.profiles.firstOrNull { it.id == profile.id }

            mutableState.update {
                it.copy(
                    cooldownOverride = false,
                    autoConfigure = it.autoConfigure?.copy(
                        finished = true,
                        phase = AutoConfigurePhase.DONE,
                        waitingForCooldown = false,
                        measuringPlanPhase = null,
                        measuringPlanCandidate = null,
                        dimensions = tuned?.tuning.orEmpty().sortedByDescending { note -> note.measuredAtEpochMillis },
                        current = buildString {
                            append(note)
                            tuned?.batchTuneNote?.takeIf(String::isNotBlank)?.let { batch ->
                                append("\n\n").append(batch)
                            }
                            tuned?.tuning?.takeIf(List<DimensionTuneNote>::isNotEmpty)?.let { tuning ->
                                tuning.asReversed().forEach { dimensionNote ->
                                    append("\n\n").append(dimensionNote.note)
                                }
                            }
                        },
                    ),
                )
            }
            restoreLoaded?.let { loadModel(it) }
        }
    }

    /** Closes the auto-configure dialog. The run itself has already finished by then. */
    fun dismissAutoConfigure() {
        mutableState.update { it.copy(autoConfigure = null, cooldownOverride = false) }
    }

    /**
     * Runs the CPU reference (load + 24-token decode) on its own thread and returns it, or null
     * when the attempt wedged for longer than [REFERENCE_TIMEOUT_MILLIS]. The reference is a
     * native call like any other candidate, so it gets the same thread-watch: a wedged load or
     * decode must not stall the whole sweep with no way out.
     */
    private suspend fun referenceAttempt(
        model: LocalModelRecord,
        profile: ModelProfile,
        threads: Int,
        onSlow: suspend (Long) -> Unit,
        /** The batch to load with; null means the llama.cpp default, which the batch sweep's yardstick uses. */
        batchTokens: Int? = null,
        ubatchTokens: Int? = null,
        /** Filler tokens to prepend, so context-sensitive knobs are measured at a realistic context. */
        padTokens: Int = 0,
    ): JSONObject? {
        val refRef = AtomicReference<JSONObject?>()
        val refThread = thread(name = "bram-tune-reference") {
            runCatching {
                runBlocking {
                    container.llamaCppClient.load(
                        model = model.copy(preferredContextTokens = profile.contextTokens),
                        threads = threads,
                        gpuLayers = 0,
                        enableThinking = profile.thinkingEnabled,
                        flashAttention = profile.flashAttention,
                        kvCacheType = profile.kvCacheType,
                        batchTokens = batchTokens ?: 0,
                        ubatchTokens = ubatchTokens ?: 0,
                    )
                    refRef.set(container.llamaCppClient.referenceDecode(REFERENCE_TOKENS, padTokens))
                }
            }.onFailure { error ->
                if (error !is android.os.DeadObjectException) {
                    android.util.Log.d(
                        "BramTune",
                        "reference error: ${error::class.simpleName}: ${error.message}",
                    )
                }
            }
        }
        val deadline = System.currentTimeMillis() + REFERENCE_TIMEOUT_MILLIS
        watchThread(refThread, deadline, 500L)
        if (refThread.isAlive) {
            android.util.Log.d("BramTune", "reference wedged; restarting the inference process")
            runCatching { container.llamaCppClient.restartInferenceProcess() }
            return null
        }
        val result = refRef.get() ?: return null
        val millis = result.optLong("promptMillis", 0L) + result.optLong("decodeMillis", 0L)
        if (millis > MAX_REFERENCE_MILLIS && !mutableState.value.cooldownOverride) {
            onSlow(millis)
            return null
        }
        return result
    }

    /** The decode-shaped dimensions auto-configure runs, in dependency order. */
    private fun autoConfigureDimensions(backend: RuntimeBackend): List<TuningDimension> = buildList {
        add(TuningDimension.THREADS)
        add(TuningDimension.CPU_MASK)
        add(TuningDimension.POLL)
        add(TuningDimension.KV_CACHE)
        add(TuningDimension.FLASH_ATTENTION)
        add(TuningDimension.LOAD_MODE)
        if (backend == RuntimeBackend.HEXAGON) add(TuningDimension.HEX_FLAGS)
    }

    /**
     * Returns the labels of candidates the profile's own saved note recorded as timed out.
     * These hang in the engine — on the S25 Ultra the CPU-mask configs and 8 threads have never
     * completed — and re-measuring one burns its probe window for nothing. NOT fingerprint-gated:
     * a pin bump was verified not to fix these, and the note is device+model-specific evidence.
     * Delete the profile to force a full re-discovery.
     */
    private fun previouslyTimedOutLabels(
        priorResults: List<TuneCandidateResult>,
    ): Set<String> {
        val labels = priorResults.filter { it.timedOut }.map { it.label }.toSet()
        if (labels.isNotEmpty()) {
            android.util.Log.d("BramTune", "candidates with a prior timeout: $labels")
        }
        return labels
    }

    /**
     * The whole run, laid out before the first measurement: backends, every decode dimension with
     * its candidate labels, and the batch — so the overlay lists every row from the start and
     * marks each one as it runs, instead of later phases appearing only when they finish.
     */
    private suspend fun autoConfigurePlan(
        profile: ModelProfile,
        backends: List<RuntimeBackend>,
        backendCandidates: List<Pair<RuntimeBackend, AcceleratorTarget>>,
    ): List<TuningPlanPhase> {
        val visibleCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val clusters = CpuTopology.clusters()
        val backendPhase = TuningPlanPhase(
            title = "Backends",
            candidates = backendCandidates.map { it.second.label },
            isBackends = true,
        )
        val dimensions = autoConfigureDimensions(
            resolveLoadBackend(profile.backendId),
        ).map { dimension ->
            TuningPlanPhase(
                title = dimension.label,
                candidates = dimensionCandidates(dimension, profile, visibleCores, clusters)
                    .map { it.label },
                dimension = dimension,
            )
        }
        val batchPhase = TuningPlanPhase(
            title = TuningDimension.BATCH.label,
            candidates = BATCH_CANDIDATES.map { (batch, ubatch) -> "$batch/$ubatch" },
            isBatch = true,
        )
        return buildList {
            add(backendPhase)
            addAll(dimensions)
            add(batchPhase)
        }
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
            val removesModel = state.profiles.count { it.modelId == profile.modelId } == 1
            if (state.activeProfileId == profileId && state.loadedModelId != null) {
                unloadModelInternal(forget = false)
            }
            val updatedPool = state.routingPool.remove(profileId)
            if (updatedPool != state.routingPool) {
                mutableState.update { it.copy(routingPool = updatedPool) }
                container.routingSettings.setRoutingPool(updatedPool)
            }
            container.modelProfileStore.delete(profileId)
            if (removesModel) {
                container.localModelStore.remove(profile.modelId)
                reloadLocalModels()
            } else {
                reloadProfiles()
            }
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
            // A tuned profile carries its own thread count; an untouched one keeps the fixed
            // validation-style threads Bram always ran, so upgrading cannot change behavior.
            val threads = if (profile.threads > 0) {
                profile.threads.coerceIn(1, visibleCores)
            } else {
                (visibleCores - 2).coerceIn(1, 4)
            }
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
                cpuMask = profile.cpuMask,
                cpuStrict = profile.cpuStrict,
                poll = profile.poll,
                threadPriority = profile.threadPriority,
                loadMode = profile.loadMode,
                hexFlags = profile.hexFlags,
                streamExperts = profile.streamExperts,
                streamCacheMb = profile.streamCacheMb,
                streamDenseAnon = profile.streamDenseAnon,
                streamOverlap = profile.streamOverlap,
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
                enableThinking = profile.thinkingEnabled,
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
                enableThinking = profile.thinkingEnabled,
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

            // The same reference run on the accelerator, for its own prompt/decode timings —
            // the card and the overlay show absolute tok/s rather than a comparison sentence.
            val acceleratorTiming = runCatching {
                container.llamaCppClient.referenceDecode(REFERENCE_TOKENS)
            }.getOrNull()

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
                promptTokens = cpuResult.optInt("promptTokens", 0),
                cpuPromptMillis = cpuResult.optLong("promptMillis", 0L),
                cpuDecodeMillis = cpuResult.optLong("decodeMillis", 0L),
                acceleratorPromptMillis = acceleratorTiming?.optLong("promptMillis", 0L) ?: 0L,
                acceleratorDecodeMillis = acceleratorTiming?.optLong("decodeMillis", 0L) ?: 0L,
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
            state.isValidatingAccelerator || state.batchTuneProfileId != null ||
            state.tuningProfileId != null
        ) return
        mutableState.update { it.copy(cooldownOverride = false) }
        viewModelScope.launch(Dispatchers.Default) { runBatchTune(profileId) }
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
        refreshMeasurementFingerprint()
        // The override is not reset here: a run inside auto-configure inherits the pass's
        // Continue-anyway, so one tap covers every remaining dimension instead of just one.
        sweepGate()?.let { reason ->
            mutableState.update { it.copy(error = reason) }
            return
        }
        waitForCooldown()
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
                // same way twice". A reference that takes minutes means the CPU is clock-limited
                // even when Android's thermal status still reads "none", so a slow one is retried
                // after a park — numbers taken through it would be lies with timestamps.
                var cpuReference: JSONObject? = null
                var referenceWedges = 0
                while (cpuReference == null) {
                    var slow = false
                    val result = referenceAttempt(model, profile, threads, onSlow = { millis ->
                        slow = true
                        android.util.Log.d("BramTune", "reference slow (${millis}ms); parking 60s and retrying")
                        mutableState.update {
                            it.copy(
                                status = "The phone is throttling (reference took ${millis / 1000}s); waiting for it to cool…",
                                autoConfigure = it.autoConfigure?.copy(
                                    current = "The phone is throttling (reference took ${millis / 1000}s); waiting…",
                                ),
                            )
                        }
                        withContext(Dispatchers.Default) { Thread.sleep(60_000L) }
                    })
                    when {
                        result != null -> cpuReference = result
                        slow -> Unit // parked and retried; a slow phone needs time, not a failure count
                        else -> {
                            referenceWedges++
                            if (referenceWedges >= MAX_REFERENCE_ATTEMPTS) {
                                throw IllegalStateException(
                                    "The CPU reference could not be measured (it hung repeatedly); " +
                                        "the batch stays at the device default.",
                                )
                            }
                        }
                    }
                }
                val cpuRef = cpuReference!!
                val reference = cpuRef.optJSONArray("tokens").toIntList()
                check(reference.isNotEmpty()) { "The CPU reference decode returned no tokens" }
                val forced = reference.toIntArray()
                // The yardstick's own wall time scales the candidate deadlines, so a thermally
                // throttled phone gets a window its decode can actually fit in instead of being
                // misread as hung by the fixed bounds.
                val referenceMillis = cpuRef.optLong("promptMillis", 0L) +
                    cpuRef.optLong("decodeMillis", 0L)
                val probeDeadlineMillis = maxOf(PROBE_TIMEOUT_MILLIS, referenceMillis * 2 + 5_000L)
                val candidateDeadlineMillis = maxOf(CANDIDATE_TIMEOUT_MILLIS, referenceMillis * 4 + 10_000L)
                // The default (512/128) is in the list so there is always a baseline to fall back
                // to, and the wide end covers the Hexagon reference configuration, which runs
                // ubatch 1024 — the backend batches prompt work in chunks that size.
                val candidates = BATCH_CANDIDATES
                val previouslyHungBatches = previouslyTimedOutLabels(
                    profile.tuning
                        .firstOrNull { it.dimension == TuningDimension.BATCH }
                        ?.results.orEmpty()
                        .map { it.copy(label = it.label) },
                )
                val scored = mutableListOf<BatchScore>()
                val batchPlanPhaseIndex = mutableState.value.autoConfigure?.plan
                    ?.indexOfFirst { phase -> phase.isBatch } ?: -1
                for ((batchIndex, pair) in candidates.withIndex()) {
                    val (batch, ubatch) = pair
                    if ("$batch/$ubatch" in previouslyHungBatches) {
                        android.util.Log.d("BramTune", "batch $batch/$ubatch previously timed out; skipping")
                        tried += "$batch/$ubatch (previously timed out)"
                        markPlanCandidateTimedOut(batchPlanPhaseIndex, batchIndex)
                        continue
                    }
                    waitForCooldown()
                    mutableState.update {
                        it.copy(
                            status = "Measuring batch $batch/$ubatch on ${backend.label}…",
                            autoConfigure = it.autoConfigure?.copy(
                                current = "Measuring batch $batch/$ubatch…",
                                measuringPlanPhase = batchPlanPhaseIndex.takeIf { index -> index >= 0 },
                                measuringPlanCandidate = batchIndex,
                            ),
                        )
                    }
                    // The same cheap probe as the dimension sweeps: a batch that hangs is
                    // abandoned in seconds instead of holding the sweep for the full timeout.
                    // Thread-watched for the same reason as the dimension probe: a wedged native
                    // call also wedges coroutine cancellation, so withTimeout cannot be relied on.
                    val probeThread = thread(name = "bram-tune-probe") {
                        runCatching {
                            runBlocking {
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
                                // Same paths as the full measurement: full forced replay plus a
                                // short decode, so a batch that hangs is caught here.
                                container.llamaCppClient.teacherForced(forced)
                                container.llamaCppClient.referenceDecode(PROBE_TOKENS)
                            }
                        }.onFailure { error ->
                            if (error !is android.os.DeadObjectException) {
                                android.util.Log.d(
                                    "BramTune",
                                    "probe $batch/$ubatch error: ${error::class.simpleName}: ${error.message}",
                                )
                            }
                        }
                    }
                    val probeDeadline = System.currentTimeMillis() + probeDeadlineMillis
                    watchThread(probeThread, probeDeadline, 250L) { refreshThermalIndicator() }
                    if (probeThread.isAlive) {
                        android.util.Log.d("BramTune", "batch $batch/$ubatch hung in probe; abandoned")
                        runCatching { container.llamaCppClient.restartInferenceProcess() }
                        tried += "$batch/$ubatch (timed out)"
                        markPlanCandidateTimedOut(batchPlanPhaseIndex, batchIndex)
                        mutableState.update {
                            it.copy(autoConfigure = it.autoConfigure?.copy(current = "$batch/$ubatch timed out"))
                        }
                        continue
                    }
                    val batchScore = AtomicReference<BatchScore?>()
                    val measureThread = thread(name = "bram-tune-measure") {
                        runCatching {
                            runBlocking {
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
                                // Exact equality is the wrong bar here: a quantized accelerator
                                // legitimately disagrees with the fp32 CPU on near-ties (the same
                                // one bisection finds at a fixed offload depth). The batch must
                                // hold the same ground the accelerator comparison does — usable,
                                // not identical.
                                val agreement = AcceleratorAgreement.score(reference, predicted)
                                if (AcceleratorAgreement.isUsable(agreement)) {
                                    // The agreement check already ran the same graph shape, but
                                    // the score that decides anything is the wall time of the
                                    // actual greedy reference path.
                                    val decode = container.llamaCppClient.referenceDecode(REFERENCE_TOKENS)
                                    batchScore.set(
                                        BatchScore(
                                            batch = batch,
                                            ubatch = ubatch,
                                            promptMillis = decode.optLong("promptMillis", 0L),
                                            decodeMillis = decode.optLong("decodeMillis", 0L),
                                            agreement = agreement,
                                        ),
                                    )
                                }
                            }
                        }.onFailure { error ->
                            if (error !is android.os.DeadObjectException) {
                                android.util.Log.d(
                                    "BramTune",
                                    "measure $batch/$ubatch error: ${error::class.simpleName}: ${error.message}",
                                )
                            }
                        }
                    }
                    val measureDeadline = System.currentTimeMillis() + candidateDeadlineMillis
                    watchThread(measureThread, measureDeadline, 500L) { refreshThermalIndicator() }
                    if (measureThread.isAlive) {
                        android.util.Log.d("BramTune", "batch $batch/$ubatch TIMED OUT")
                        runCatching { container.llamaCppClient.restartInferenceProcess() }
                        tried += "$batch/$ubatch (timed out)"
                        markPlanCandidateTimedOut(batchPlanPhaseIndex, batchIndex)
                        mutableState.update {
                            it.copy(autoConfigure = it.autoConfigure?.copy(current = "$batch/$ubatch timed out"))
                        }
                        continue
                    }
                    batchScore.get()?.let { scored += it }
                    tried += "$batch/$ubatch"
                }
                check(scored.isNotEmpty()) {
                    "None of the batch configurations met the " +
                        "${(AcceleratorAgreement.USABLE_THRESHOLD * 100).toInt()}% agreement bar " +
                        "against the CPU reference (${tried.joinToString(", ")} all failed)"
                }
                val best = scored.minBy { it.promptMillis }
                val promptTokens = cpuRef.optInt("promptTokens", 0)
                val rate = if (best.promptMillis > 0 && promptTokens > 0) {
                    promptTokens * 1000 / best.promptMillis
                } else {
                    0
                }
                val comparable = reference.size
                // The structured results the bars draw: one entry per candidate, absolute
                // throughput, winner marked. Written into the tuning list so the batch row gets
                // the same visuals as the decode dimensions; the legacy note fields stay too.
                val batchResults = candidates.map { (batch, ubatch) ->
                    val score = scored.firstOrNull { it.batch == batch && it.ubatch == ubatch }
                    TuneCandidateResult(
                        label = "$batch/$ubatch",
                        promptTokPerSec = score?.let {
                            if (it.promptMillis > 0 && promptTokens > 0) {
                                promptTokens * 1000.0 / it.promptMillis
                            } else 0.0
                        } ?: 0.0,
                        decodeTokPerSec = score?.let {
                            if (it.decodeMillis > 0 && reference.isNotEmpty()) {
                                reference.size * 1000.0 / it.decodeMillis
                            } else 0.0
                        } ?: 0.0,
                        agreed = score != null,
                        winner = score != null && score.batch == best.batch && score.ubatch == best.ubatch,
                    )
                }
                val batchNote = DimensionTuneNote(
                    dimension = TuningDimension.BATCH,
                    chosen = "${best.batch}/${best.ubatch}",
                    note = buildString {
                        append("Tuned batch ${best.batch}/${best.ubatch} on ${backend.label}: ")
                        append("$rate prompt tok/s, ")
                        append("${(best.agreement * comparable).toInt()}/$comparable predictions ")
                        append("match the CPU reference")
                        append(". Tried ${tried.joinToString(", ")}.")
                    },
                    measuredAtEpochMillis = System.currentTimeMillis(),
                    results = batchResults,
                )
                val tuned = profile.copy(
                    batchTokens = best.batch,
                    ubatchTokens = best.ubatch,
                    batchTuneNote = batchNote.note,
                    batchTunedAtEpochMillis = System.currentTimeMillis(),
                    measuredFingerprint = mutableState.value.measurementFingerprint,
                    tuning = listOf(batchNote) + profile.tuning.filterNot { it.dimension == TuningDimension.BATCH },
                )
                container.modelProfileStore.save(tuned)
                syncProfiles(mutableState.value.localModels, profile.id)
            }.onFailure { error ->
                mutableState.update {
                    it.copy(error = error.message ?: "Could not tune the batch size")
                }
            }
            // The measurement leaves the runtime on the last candidate, so drop it and restore
            // whatever was loaded before — the profile, now with its tuned batch, if it was the
            // one being tuned.
            runCatching { unloadModelInternal(forget = false) }
            mutableState.update {
                it.copy(
                    batchTuneProfileId = null,
                    status = null,
                    autoConfigure = it.autoConfigure?.copy(
                        waitingForCooldown = false,
                        measuringPlanPhase = null,
                        measuringPlanCandidate = null,
                    ),
                )
            }
            refreshDeviceProfile()
            restoreLoaded?.let { loadModel(it) }
    }

    /** Measures one non-batch dimension, then saves the winner with a note, batch-tuner style. */
    fun tuneDimension(profileId: String, dimension: TuningDimension) {
        val state = mutableState.value
        if (state.isLoadingModel || state.isGenerating || state.isValidatingAccelerator ||
            state.batchTuneProfileId != null || state.tuningProfileId != null
        ) return
        mutableState.update { it.copy(cooldownOverride = false) }
        viewModelScope.launch(Dispatchers.Default) { runDimensionTune(profileId, dimension, fromAutoConfigure = false) }
    }

    /** A candidate for one tuning dimension: what to try, in words, and how to apply it. */
    private data class TuningCandidate(val label: String, val apply: (ModelProfile) -> ModelProfile)

    /** A candidate's measured timings, so the note can quote both phases honestly. */
    private data class DimensionScore(
        val candidate: TuningCandidate,
        val promptMillis: Long,
        val decodeMillis: Long,
    ) {
        val totalMillis: Long get() = promptMillis + decodeMillis
    }

    /**
     * The candidates for a dimension, derived from the device's facts: core count, CPU cluster
     * topology, and the backend the profile loads onto. The device decides — there is no table.
     */
    private fun dimensionCandidates(
        dimension: TuningDimension,
        profile: ModelProfile,
        cores: Int,
        clusters: List<Int>,
    ): List<TuningCandidate> = when (dimension) {
        TuningDimension.THREADS -> TuningCandidates.threadCandidates(cores, profile.threads).map { count ->
            TuningCandidate("$count threads") { it.copy(threads = count) }
        }
        TuningDimension.CPU_MASK -> TuningCandidates.maskCandidates(clusters).map { mask ->
            TuningCandidate(if (mask.isEmpty()) "All cores" else "Mask 0x$mask") {
                it.copy(cpuMask = mask, cpuStrict = mask.isNotEmpty())
            }
        }
        TuningDimension.POLL -> TuningCandidates.pollCandidates().map { poll ->
            TuningCandidate(if (poll == 0) "No polling" else "Aggressive polling") {
                it.copy(poll = poll)
            }
        }
        TuningDimension.KV_CACHE -> listOf(
            TuningCandidate(KvCacheType.F16.label) { it.copy(kvCacheType = KvCacheType.F16) },
            TuningCandidate(KvCacheType.Q8_0.label) { it.copy(kvCacheType = KvCacheType.Q8_0) },
        )
        TuningDimension.FLASH_ATTENTION -> FlashAttentionMode.entries.map { mode ->
            TuningCandidate(mode.label) { it.copy(flashAttention = mode) }
        }
        TuningDimension.LOAD_MODE -> TuningCandidates.loadModeCandidates().map { mode ->
            TuningCandidate(mode.label) { it.copy(loadMode = mode) }
        }
        TuningDimension.HEX_FLAGS -> TuningCandidates.hexFlagCandidates().map { flags ->
            TuningCandidate(if (flags.isDefault) "Backend defaults" else "HMX + host buffers") {
                it.copy(hexFlags = flags)
            }
        }
        TuningDimension.BATCH -> emptyList()
    }

    /**
     * Loads a candidate's configuration. Shared by the cheap hang probe and the full
     * measurement, so both exercise exactly the same load request.
     */
    private suspend fun loadTunedCandidate(
        tuned: ModelProfile,
        profile: ModelProfile,
        model: LocalModelRecord,
        backend: RuntimeBackend,
        referenceThreads: Int,
        visibleCores: Int,
        gpuLayers: Int,
    ) {
        container.llamaCppClient.load(
            model = model.copy(preferredContextTokens = profile.contextTokens),
            threads = if (tuned.threads > 0) tuned.threads.coerceIn(1, visibleCores) else referenceThreads,
            gpuLayers = gpuLayers,
            deviceFilter = backend.devicePrefix,
            enableThinking = profile.thinkingEnabled,
            flashAttention = tuned.flashAttention,
            kvCacheType = tuned.kvCacheType,
            batchTokens = profile.batchTokens,
            ubatchTokens = profile.ubatchTokens,
            cpuMask = tuned.cpuMask,
            cpuStrict = tuned.cpuStrict,
            poll = tuned.poll,
            threadPriority = tuned.threadPriority,
            loadMode = tuned.loadMode,
            hexFlags = tuned.hexFlags,
        )
    }

    /**
     * Loads one tuning candidate, replays the CPU reference through it under teacher forcing,
     * and — when it agrees — times the greedy reference path. Returns true when the candidate
     * agreed; a candidate that does not agree is recorded by the caller either way.
     */
    private suspend fun measureDimensionCandidate(
        candidate: TuningCandidate,
        tuned: ModelProfile,
        profile: ModelProfile,
        model: LocalModelRecord,
        backend: RuntimeBackend,
        reference: List<Int>,
        forced: IntArray,
        referenceThreads: Int,
        visibleCores: Int,
        gpuLayers: Int,
        scored: MutableList<DimensionScore>,
        padTokens: Int = 0,
    ): Boolean {
        mutableState.update {
            it.copy(
                status = "Measuring ${candidate.label} on ${backend.label}…",
                autoConfigure = it.autoConfigure?.copy(current = "Measuring ${candidate.label}…"),
            )
        }
        loadTunedCandidate(
            tuned = tuned,
            profile = profile,
            model = model,
            backend = backend,
            referenceThreads = referenceThreads,
            visibleCores = visibleCores,
            gpuLayers = gpuLayers,
        )
        // The teacher-forced replay now carries timing too (prompt_ms + decode_ms of its
        // own run), so one call serves both agreement and speed — the separate referenceDecode
        // the sweep used to do is gone, which halves the cost of each padded candidate.
        val forcedResult = container.llamaCppClient.teacherForced(forced, padTokens)
        val predicted = forcedResult.optJSONArray("predictions").toIntList()
        val agreement = AcceleratorAgreement.score(reference, predicted)
        if (AcceleratorAgreement.isUsable(agreement)) {
            scored += DimensionScore(
                candidate = candidate,
                promptMillis = forcedResult.optLong("promptMillis", 0L),
                decodeMillis = forcedResult.optLong("decodeMillis", 0L),
            )
            return true
        }
        return false
    }

    /**
     * Sweeps one dimension under teacher forcing and keeps the fastest candidate that still
     * reproduces the CPU reference. The reference is recorded on CPU with the fixed validation
     * threadpool — never the profile's tuned values — so the yardstick cannot move.
     */
    private suspend fun runDimensionTune(
        profileId: String,
        dimension: TuningDimension,
        fromAutoConfigure: Boolean,
    ) {
        val state = mutableState.value
        val profile = state.profiles.firstOrNull { it.id == profileId } ?: return
        val model = state.localModels.firstOrNull { it.id == profile.modelId } ?: return
        val restoreLoaded = mutableState.value.loadedModelId
        val backend = resolveLoadBackend(profile.backendId)
        val visibleCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        refreshMeasurementFingerprint()
        // The override is not reset here: a run inside auto-configure inherits the pass's
        // Continue-anyway, so one tap covers every remaining dimension instead of just one.
        sweepGate()?.let { reason ->
            mutableState.update { it.copy(error = reason) }
            finishDimensionTune()
            return
        }
        waitForCooldown()
        val candidates = dimensionCandidates(
            dimension,
            profile,
            visibleCores,
            CpuTopology.clusters(),
        )
        val previouslyHung = previouslyTimedOutLabels(
            profile.tuning.firstOrNull { it.dimension == dimension }?.results.orEmpty(),
        )
        if (dimension == TuningDimension.HEX_FLAGS && backend != RuntimeBackend.HEXAGON) {
            finishDimensionTune()
            return
        }
        if (candidates.size < 2) {
            // Nothing to compare is a result worth saying, not a failure: the note explains why.
            val note = DimensionTuneNote(
                dimension = dimension,
                chosen = "Default",
                note = "${dimension.label} left at the device default: this device has no " +
                    "alternative worth measuring.",
                measuredAtEpochMillis = System.currentTimeMillis(),
            )
            container.modelProfileStore.save(
                profile.copy(
                    tuning = listOf(note) + profile.tuning.filterNot { it.dimension == dimension },
                    measuredFingerprint = mutableState.value.measurementFingerprint,
                ),
            )
            syncProfiles(mutableState.value.localModels, profile.id)
            finishDimensionTune()
            return
        }
        mutableState.update {
            it.copy(
                tuningProfileId = profile.id,
                tuningDimension = dimension,
                error = null,
                status = "Tuning ${dimension.label}…",
            )
        }
        runCatching {
            val referenceThreads = (visibleCores - 2).coerceIn(1, 4)
            android.util.Log.d("BramTune", "dim=${dimension.wire} backend=${backend.name} candidates=${candidates.map { it.label }}")
            // The yardstick: CPU, fixed threadpool, nothing tuned. The profile's thinking setting
            // is part of it: the reference must open the assistant turn the same way the
            // candidates do, or a thinking-enabled profile measures two different prompts and
            // every candidate fails the agreement bar on the first token.
            // The reference is also the throttle detector: a 24-token reference that takes
            // minutes says the CPU is clock-limited even when Android's thermal status still
            // reads "none" (it tracks skin temperature, not CPU banding). A slow reference is
            // retried after a park instead of being used — numbers taken through it would be
            // lies with timestamps, and the park lets the phone actually cool down.
            // Only the context-sensitive dimensions pay for the padded prompt: KV cache and
            // flash attention matter because the cache holds thousands of tokens, so their
            // effect is invisible at the bare 63-token prompt. Everything else is context
            // independent and stays fast.
            val padTokens = if (dimension == TuningDimension.KV_CACHE ||
                dimension == TuningDimension.FLASH_ATTENTION
            ) {
                REFERENCE_PAD_TOKENS
            } else {
                0
            }
            var cpuReference: JSONObject? = null
            var referenceWedges = 0
            while (cpuReference == null) {
                var slow = false
                val result = referenceAttempt(
                    model,
                    profile,
                    referenceThreads,
                    padTokens = padTokens,
                    onSlow = { millis ->
                        slow = true
                        android.util.Log.d("BramTune", "reference slow (${millis}ms); parking 60s and retrying")
                        mutableState.update {
                            it.copy(
                                status = "The phone is throttling (reference took ${millis / 1000}s); waiting for it to cool…",
                                autoConfigure = it.autoConfigure?.copy(
                                    current = "The phone is throttling (reference took ${millis / 1000}s); waiting…",
                                ),
                            )
                        }
                        withContext(Dispatchers.Default) { Thread.sleep(60_000L) }
                    },
                )
                when {
                    result != null -> cpuReference = result
                    slow -> Unit // parked and retried; a slow phone needs time, not a failure count
                    else -> {
                        referenceWedges++
                        if (referenceWedges >= MAX_REFERENCE_ATTEMPTS) {
                            throw IllegalStateException(
                                "The CPU reference could not be measured (it hung repeatedly); " +
                                    "${dimension.label} stays at the device default.",
                            )
                        }
                    }
                }
            }
            val cpuRef = cpuReference!!
            val reference = cpuRef.optJSONArray("tokens").toIntList()
            check(reference.isNotEmpty()) { "The CPU reference decode returned no tokens" }
            val forced = reference.toIntArray()
            // The yardstick's own wall time scales the candidate deadlines: the fixed bounds
            // catch hangs on a fast device, but a thermally throttled phone can legitimately
            // need minutes for a decode the fixed probe window would misread as a hang.
            val referenceMillis = cpuRef.optLong("promptMillis", 0L) +
                cpuRef.optLong("decodeMillis", 0L)
            val probeDeadlineMillis = maxOf(PROBE_TIMEOUT_MILLIS, referenceMillis * 2 + 5_000L)
            val candidateDeadlineMillis = maxOf(CANDIDATE_TIMEOUT_MILLIS, referenceMillis * 4 + 10_000L)
            android.util.Log.d(
                "BramTune",
                "reference took ${referenceMillis}ms; probe deadline ${probeDeadlineMillis}ms, candidate ${candidateDeadlineMillis}ms",
            )
            val gpuLayers = if (backend.offloadsToAccelerator) FULL_GPU_OFFLOAD else 0
            val planPhaseIndex = mutableState.value.autoConfigure?.plan
                ?.indexOfFirst { phase -> phase.dimension == dimension } ?: -1
            val tried = mutableListOf<String>()
            val scored = mutableListOf<DimensionScore>()
            val timedOutCandidates = mutableSetOf<TuningCandidate>()
            var agreed = 0
            for ((candidateIndex, candidate) in candidates.withIndex()) {
                // Skip candidates that hung in a previous sweep: they hang again on this device
                // (verified across pins), and their timed-out result is carried into the note so
                // the next run skips them too — the note always carries every candidate's outcome.
                if (candidate.label in previouslyHung) {
                    android.util.Log.d("BramTune", "candidate=${candidate.label} previously timed out; skipping")
                    timedOutCandidates += candidate
                    tried += "${candidate.label} (previously timed out)"
                    markPlanCandidateTimedOut(planPhaseIndex, candidateIndex)
                    continue
                }
                waitForCooldown()
                val tuned = candidate.apply(profile)
                val candidateThreads = if (tuned.threads > 0) {
                    tuned.threads.coerceIn(1, visibleCores)
                } else {
                    referenceThreads
                }
                android.util.Log.d("BramTune", "candidate=${candidate.label} hex=${tuned.hexFlags} starting")
                // The probe can sit on a hanging load for its whole window, so the overlay must
                // name the candidate being probed — otherwise it keeps the previous candidate's
                // text and the run looks stuck.
                mutableState.update {
                    it.copy(
                        status = "Measuring ${candidate.label} on ${backend.label}…",
                        autoConfigure = it.autoConfigure?.copy(
                            current = "Measuring ${candidate.label}…",
                            measuringPlanPhase = planPhaseIndex.takeIf { index -> index >= 0 },
                            measuringPlanCandidate = candidateIndex,
                        ),
                    )
                }
                // A cheap probe first: the candidate's load plus a handful of tokens. A config
                // that hangs is abandoned in seconds and recorded, instead of burning the full
                // measurement timeout — the converted-Q8_0 hangs showed that pathological
                // candidates, not healthy ones, dominate tuning time.
                // The probe runs on its own thread and the sweep watches the thread, not the
                // coroutine: a wedged native call also wedges coroutine cancellation (a blocked
                // binder call never resumes), so withTimeout cannot be relied on here.
                val probeThread = thread(name = "bram-tune-probe") {
                    runCatching {
                        runBlocking {
                            loadTunedCandidate(
                                tuned = tuned,
                                profile = profile,
                                model = model,
                                backend = backend,
                                referenceThreads = referenceThreads,
                                visibleCores = visibleCores,
                                gpuLayers = gpuLayers,
                            )
                            // The hang lives in the teacher-forced replay, so the probe runs the
                            // same path the measurement will. It is sub-second on healthy
                            // candidates.
                            container.llamaCppClient.teacherForced(forced)
                            container.llamaCppClient.referenceDecode(PROBE_TOKENS)
                        }
                    }.onFailure { error ->
                        if (error !is android.os.DeadObjectException) {
                            android.util.Log.d(
                                "BramTune",
                                "probe ${candidate.label} error: ${error::class.simpleName}: ${error.message}",
                            )
                        }
                    }
                }
                val probeDeadline = System.currentTimeMillis() + probeDeadlineMillis
                watchThread(probeThread, probeDeadline, 250L) { refreshThermalIndicator() }
                if (probeThread.isAlive) {
                    android.util.Log.d("BramTune", "candidate=${candidate.label} hung in probe; abandoned")
                    runCatching { container.llamaCppClient.restartInferenceProcess() }
                    timedOutCandidates += candidate
                    tried += "${candidate.label} (timed out)"
                    markPlanCandidateTimedOut(planPhaseIndex, candidateIndex)
                    mutableState.update {
                        it.copy(autoConfigure = it.autoConfigure?.copy(current = "${candidate.label} timed out"))
                    }
                    continue
                }
                // The full measurement: a broken backend kernel can hang a load or decode without
                // ever reporting an error — the HMX path on Snapdragon 8 Elite is one. A stuck
                // candidate must be a recorded failure, not a stall the user waits out forever.
                val measured = AtomicBoolean(false)
                val measureThread = thread(name = "bram-tune-measure") {
                    runCatching {
                        runBlocking {
                            measured.set(
                                measureDimensionCandidate(
                                    candidate = candidate,
                                    tuned = tuned,
                                    profile = profile,
                                    model = model,
                                    backend = backend,
                                    reference = reference,
                                    forced = forced,
                                    referenceThreads = referenceThreads,
                                    visibleCores = visibleCores,
                                    gpuLayers = gpuLayers,
                                    scored = scored,
                                    padTokens = padTokens,
                                ),
                            )
                        }
                    }.onFailure { error ->
                        if (error !is android.os.DeadObjectException) {
                            android.util.Log.d(
                                "BramTune",
                                "measure ${candidate.label} error: ${error::class.simpleName}: ${error.message}",
                            )
                        }
                    }
                }
                val measureDeadline = System.currentTimeMillis() + candidateDeadlineMillis
                watchThread(measureThread, measureDeadline, 500L) { refreshThermalIndicator() }
                val agreedNow = if (measureThread.isAlive) {
                    android.util.Log.d("BramTune", "candidate=${candidate.label} TIMED OUT")
                    // A hung native call wedges the inference process's single executor thread,
                    // so the app-side timeout alone cannot make the next candidate runnable: the
                    // process itself has to go. Restart it, and the next load binds fresh.
                    runCatching { container.llamaCppClient.restartInferenceProcess() }
                    timedOutCandidates += candidate
                    markPlanCandidateTimedOut(planPhaseIndex, candidateIndex)
                    mutableState.update {
                        it.copy(autoConfigure = it.autoConfigure?.copy(current = "${candidate.label} timed out"))
                    }
                    null
                } else {
                    measured.get()
                }
                tried += if (agreedNow == null) "${candidate.label} (timed out)" else candidate.label
                if (agreedNow == true) agreed++
            }
            if (scored.isEmpty()) {
                // Nothing met the bar is a recorded fact, not an error: the note carries
                // per-candidate results so the overlay's up-front rows can say which ones timed
                // out and which simply did not agree, and the profile stays on the default.
                val failureResults = candidates.map { candidate ->
                    TuneCandidateResult(
                        label = candidate.label,
                        promptTokPerSec = 0.0,
                        decodeTokPerSec = 0.0,
                        agreed = false,
                        timedOut = candidate in timedOutCandidates,
                    )
                }
                val failedNote = DimensionTuneNote(
                    dimension = dimension,
                    chosen = "Default",
                    note = "${dimension.label} tuning failed (None of the ${dimension.label.lowercase()} " +
                        "configurations met the ${(AcceleratorAgreement.USABLE_THRESHOLD * 100).toInt()}% " +
                        "agreement bar against the CPU reference " +
                        "(${tried.joinToString(", ")} all failed)); left at the device default.",
                    measuredAtEpochMillis = System.currentTimeMillis(),
                    results = failureResults,
                )
                container.modelProfileStore.save(
                    profile.copy(
                        tuning = listOf(failedNote) + profile.tuning.filterNot { it.dimension == dimension },
                        measuredFingerprint = mutableState.value.measurementFingerprint,
                    ),
                )
                syncProfiles(mutableState.value.localModels, profile.id)
                mutableState.update {
                    it.copy(
                        error = "None of the ${dimension.label.lowercase()} configurations met the " +
                            "agreement bar; ${dimension.label} stays at the device default.",
                        autoConfigure = it.autoConfigure?.copy(
                            current = "${dimension.label}: every candidate timed out or disagreed",
                        ),
                    )
                }
                return@runCatching
            }
            val best = scored.minBy { it.totalMillis }
            val winner = best.candidate
            val promptTokens = cpuRef.optInt("promptTokens", 0)
            val promptRate = if (best.promptMillis > 0 && promptTokens > 0) {
                promptTokens * 1000 / best.promptMillis
            } else {
                0
            }
            val decodeRate = if (best.decodeMillis > 0) {
                reference.size * 1000 / best.decodeMillis
            } else {
                0
            }
            val tunedProfile = winner.apply(profile)
            val candidateResults = candidates.map { candidate ->
                val score = scored.firstOrNull { it.candidate == candidate }
                TuneCandidateResult(
                    label = candidate.label,
                    promptTokPerSec = score?.let { scoreEntry ->
                        if (scoreEntry.promptMillis > 0 && promptTokens > 0) {
                            promptTokens * 1000.0 / scoreEntry.promptMillis
                        } else 0.0
                    } ?: 0.0,
                    decodeTokPerSec = score?.let { scoreEntry ->
                        if (scoreEntry.decodeMillis > 0 && reference.isNotEmpty()) {
                            reference.size * 1000.0 / scoreEntry.decodeMillis
                        } else 0.0
                    } ?: 0.0,
                    agreed = score != null,
                    timedOut = candidate in timedOutCandidates,
                    winner = candidate == winner,
                )
            }
            val note = DimensionTuneNote(
                dimension = dimension,
                chosen = winner.label,
                note = "Tuned ${dimension.label} to ${winner.label} on ${backend.label}: " +
                    "~$promptRate prompt tok/s, ~$decodeRate decode tok/s, " +
                    "$agreed of ${candidates.size} candidates matched the CPU reference. " +
                    "Tried ${tried.joinToString(", ")}.",
                measuredAtEpochMillis = System.currentTimeMillis(),
                results = candidateResults,
            )
            container.modelProfileStore.save(
                tunedProfile.copy(
                    tuning = listOf(note) + tunedProfile.tuning.filterNot { it.dimension == dimension },
                    measuredFingerprint = mutableState.value.measurementFingerprint,
                ),
            )
            syncProfiles(mutableState.value.localModels, profile.id)
        }.onFailure { error ->
            // A cancelled sweep is not a failed sweep: the user left the screen (the ViewModel
            // was cleared), so nothing was measured and nothing should be recorded.
            if (error is CancellationException) {
                android.util.Log.d("BramTune", "dim=${dimension.wire} sweep cancelled; nothing recorded")
                return@onFailure
            }
            android.util.Log.d("BramTune", "dim=${dimension.wire} sweep failed: ${error::class.simpleName}: ${error.message}")
            mutableState.update {
                it.copy(error = error.message ?: "Could not tune ${dimension.label.lowercase()}")
            }
            // A failed sweep is a recorded fact, not a silence: the note says what happened and
            // that the profile stayed on the device default, so the card can explain itself.
            val saved = mutableState.value.profiles.firstOrNull { it.id == profileId }
            if (saved != null) {
                val failedNote = DimensionTuneNote(
                    dimension = dimension,
                    chosen = "Default",
                    note = "${dimension.label} tuning failed (${error.message ?: "unknown error"}); " +
                        "left at the device default.",
                    measuredAtEpochMillis = System.currentTimeMillis(),
                )
                runCatching {
                    container.modelProfileStore.save(
                        saved.copy(
                            tuning = listOf(failedNote) + saved.tuning.filterNot { it.dimension == dimension },
                            measuredFingerprint = mutableState.value.measurementFingerprint,
                        ),
                    )
                    syncProfiles(mutableState.value.localModels, profile.id)
                }
            }
        }
        runCatching { unloadModelInternal(forget = false) }
        finishDimensionTune()
        refreshDeviceProfile()
        restoreLoaded?.let { loadModel(it) }
    }

    /**
     * Clears the per-dimension tuning state. Runs after every dimension sweep, including the
     * ones inside auto-configure: leaving the flags set would put the card in a permanent
     * "Tuning…" state and the guards would silently block the next tune or auto-configure.
     */
    private fun finishDimensionTune() {
        mutableState.update {
            it.copy(
                tuningProfileId = null,
                tuningDimension = null,
                status = null,
                autoConfigure = it.autoConfigure?.copy(
                    waitingForCooldown = false,
                    measuringPlanPhase = null,
                    measuringPlanCandidate = null,
                ),
            )
        }
    }

    /**
     * Converts a profile's GGUF to another quant on the device, so a file whose weights the NPU
     * cannot offload gets an NPU-runnable copy. Runs through llama.cpp's quantize in the isolated
     * inference process (it blocks chat there while it runs), then catalogues the result like an
     * import — SHA-256, metadata, a default profile — and measures it.
     */
    fun convertQuant(profileId: String, targetType: String) {
        val state = mutableState.value
        if (state.isLoadingModel || state.isGenerating || state.isValidatingAccelerator ||
            state.batchTuneProfileId != null || state.tuningProfileId != null ||
            state.convertingProfileId != null
        ) return
        val profile = state.profiles.firstOrNull { it.id == profileId } ?: return
        val model = state.localModels.firstOrNull { it.id == profile.modelId } ?: return
        if (model.localPath.isBlank()) return
        viewModelScope.launch {
            val outFile = java.io.File(model.localPath.removeSuffix(".gguf") + "-$targetType.gguf")
            runCatching { outFile.delete() }
            mutableState.update {
                it.copy(
                    convertingProfileId = profileId,
                    error = null,
                    status = "Converting to ${targetType.uppercase()}… this can take a few minutes",
                )
            }
            val conversion = runCatching {
                container.llamaCppClient.quantize(model.localPath, outFile.absolutePath, targetType)
            }
            if (conversion.isSuccess) {
                android.util.Log.d("BramTune", "convertQuant: quantization succeeded, registering output")
                val registered = runCatching {
                    container.localModelStore.registerLocalFile(outFile, targetType.uppercase())
                }.getOrNull()
                mutableState.update { it.copy(convertingProfileId = null, status = null) }
                if (registered != null) {
                    android.util.Log.d("BramTune", "convertQuant: registered ${registered.id.value}, kicking off auto-configure")
                    val models = runCatching { container.localModelStore.list() }
                        .getOrDefault(mutableState.value.localModels)
                    syncProfiles(models, "profile:${registered.id.value}:default")
                    // Like an import: the new profile is measured right away, so the card can say
                    // what this device makes of the converted file.
                    autoConfigure("profile:${registered.id.value}:default")
                } else {
                    mutableState.update {
                        it.copy(error = "The conversion finished but the new file could not be catalogued")
                    }
                }
            } else {
                mutableState.update {
                    it.copy(
                        convertingProfileId = null,
                        status = null,
                        error = conversion.exceptionOrNull()?.message ?: "Quant conversion failed",
                    )
                }
            }
        }
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

    /**
     * Parses the propose_skill arguments (for the human-readable name) and its result (for
     * success, since a rejected draft returns an error), and posts the review nudge. Best-effort:
     * a malformed payload simply posts nothing.
     */
    private fun maybePostSkillDraftNotification(argumentsJson: String, resultJson: String) {
        val result = runCatching { JSONObject(resultJson) }.getOrNull() ?: return
        if (result.has("error")) return
        val name = runCatching { JSONObject(argumentsJson).optString("name") }.getOrDefault("")
            .takeIf(String::isNotBlank) ?: return
        val version = result.optString("version").ifBlank {
            runCatching { JSONObject(argumentsJson).optString("version") }.getOrDefault("")
        }
        AgentTaskService.postSkillDraft(container.appContext, name, version)
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
    private suspend fun runInstructions(
        snapshot: AppUiState,
        contextWindowTokens: Int = 8_192,
    ): String {
        val base = snapshot.activeProfile?.systemPrompt.orEmpty()
        val skills = runCatching { container.skillStore.activeSkills() }.getOrDefault(emptyList())
        // Rank skills by description similarity to this turn's ask, so the ones that matter land
        // first and the character budget trims the rest. With no embedding model designated the
        // ranker returns the input unchanged, so every active skill still joins the prompt.
        val query = snapshot.messages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        val ranked = container.skillSelection.rank(skills, query, container.embedder)
        // Evaluate the same selection the orchestrator will make (deterministic, and its
        // embeddings are cached) so a skill that merely duplicates an offered tool is not
        // advertised at all — on a small model the duplicate is a lure away from the tool.
        val offered = runCatching {
            container.toolSelector.select(
                query = query,
                contextWindowTokens = contextWindowTokens,
                available = container.toolRegistry.definitions(),
            )
        }.getOrDefault(emptyList())
        val advertised = container.skillSelection.withoutCovered(ranked, offered, container.embedder)
        for (skill in ranked) {
            val coverage = container.skillSelection.toolCoverage(skill, offered, container.embedder)
            android.util.Log.d(
                "BramSkill",
                "skill ${skill.name}: max tool coverage ${coverage ?: "<unavailable>"}, " +
                    "${if (skill in advertised) "advertised" else "suppressed"}",
            )
        }
        var prompt = SkillPrompt.append(base, advertised)
        // One nudge per conversation: surface a drafted (inactive) skill whose description matches
        // this task so the user learns it exists. Drafts are unreviewed text and are never followed
        // — only their existence is named — and the set keeps a match from being suggested twice.
        val packages = runCatching { container.skillStore.packages() }.getOrDefault(emptyList())
        val draft = container.skillSelection.topDraft(packages, query, container.embedder)
            ?.takeIf { it.id !in hintedDraftSkills }
        if (draft != null) {
            hintedDraftSkills += draft.id
            draft.versions.firstOrNull { it.version == draft.draftVersion }?.let { version ->
                prompt = SkillPrompt.appendDraftHint(prompt, draft.name, version.description)
            }
        }
        val memories = runCatching { container.memoryStore.mostImportant(MEMORY_INJECTION_LIMIT) }
            .getOrDefault(emptyList())
        return MemoryPrompt.append(prompt, memories)
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
        // Stopping is an intent to halt, so anything waiting in the queue goes with it.
        mutableState.update { it.copy(queuedMessages = emptyList(), status = "Stopping…") }
        generationJob?.cancel(CancellationException("Stopped by user"))
    }

    /**
     * Moves the status notification to the current phase. No-op when nothing is loaded or
     * selected, which is when there is nothing to hold a foreground service for.
     */
    private fun pushModelStatus(phase: ModelPhase, detail: String? = null) {
        mutableState.update { it.copy(modelPhase = phase) }
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
        // A turn in flight holds the transcript: appending now would be wiped when it settles.
        // The message queues here instead and starts its own turn the moment the reply lands.
        if (mutableState.value.isGenerating) {
            mutableState.update { it.copy(queuedMessages = it.queuedMessages + prompt) }
            return
        }
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
        // Bind the Termux shell session to this conversation so `shell` calls keep their working
        // directory across the turn (and across turns in the same thread).
        container.termuxTool.setSession(conversationId.value)
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
        // Persist the prompt up front so the thread on disk shows it even if Bram is killed before
        // the turn settles: what the user sent must never disappear with the reply. Also gives a
        // cold reopen a truthful transcript instead of hiding the in-flight message.
        persistActiveConversation(requestMessages)

        // Run on the application scope, not the ViewModel's: agent work is expected to continue
        // while the user is elsewhere, and a run tied to the screen would be cancelled the moment
        // the ViewModel is cleared. AgentTaskService keeps the process alive for the duration.
        generationJob = container.appScope.launch {
            container.turnMutex.withLock { runTurnInProgress(requestMessages, snapshot) }
            // Anything the user sent mid-turn now runs, one message per turn, newest transcript
            // first. runTurn refuses while a turn is live, and the previous finally has already
            // cleared isGenerating, so the first queued message simply starts the next turn.
            val queued = mutableState.value.queuedMessages
            if (queued.isNotEmpty()) {
                val next = queued.first()
                mutableState.update { it.copy(queuedMessages = queued.drop(1)) }
                val messages = mutableState.value.messages
                runTurn(messages + ConversationMessage(role = MessageRole.USER, content = next))
            }
        }
    }

    private suspend fun runTurnInProgress(
        requestMessages: List<ConversationMessage>,
        snapshot: AppUiState,
    ) {
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
            return
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
        var streamedTokens = 0
        var decodeStartedAt = 0L
        var lastRateUpdateAt = 0L
        return try {
            agent.run(
                request = AgentRunRequest(
                    conversationId = conversationId,
                    messages = requestMessages,
                    identity = BramDefaults.IDENTITY,
                    maxOutputTokens = minOf(1_024, selection.runtime.model.contextWindowTokens / 8),
                    sampler = snapshot.activeProfile?.sampler ?: SamplerSettings(),
                    profileInstructions = runInstructions(snapshot, selection.runtime.model.contextWindowTokens),
                ),
                runtime = selection.runtime,
            ).collect { event ->
                    when (event) {
                        is AgentEvent.Status -> {
                            mutableState.update { it.copy(status = event.text) }
                            // Preparation labels only before the first token: a status event that
                            // mentions "prompt" or "context" mid-turn (memory recall, a tool
                            // retry) must not flip an already-writing turn back to "System prompt".
                            val preparing = assistantText.isEmpty() && (
                                event.text.contains("context", ignoreCase = true) ||
                                    event.text.contains("prompt", ignoreCase = true) ||
                                    event.text.contains("prepar", ignoreCase = true)
                                )
                            pushModelStatus(if (preparing) ModelPhase.PREPARING else ModelPhase.GENERATING)
                        }
                        is AgentEvent.Reasoning -> reasoningFormat = event.format
                        is AgentEvent.ToolsSelected -> {
                            android.util.Log.d("BramTools", "offering ${event.names.size} tools: ${event.names.joinToString()}")
                        }
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
                            val rateNow = android.os.SystemClock.elapsedRealtime()
                            if (decodeStartedAt == 0L) {
                                decodeStartedAt = rateNow
                                // The first token is writing, whatever the last status event
                                // said: a late "…prompt…" status used to strand the phase here
                                // while the reply streamed under a "System prompt" label.
                                if (mutableState.value.modelPhase == ModelPhase.PREPARING) {
                                    pushModelStatus(ModelPhase.GENERATING)
                                }
                            }
                            // Local runtimes emit one delta per decoded token. Remote providers
                            // generally do the same, making this a useful live estimate until their
                            // authoritative metrics arrive at the end of the turn.
                            streamedTokens += 1
                            if (rateNow - lastRateUpdateAt >= 200L) {
                                val elapsed = (rateNow - decodeStartedAt).coerceAtLeast(1L)
                                val liveRate = streamedTokens * 1_000.0 / elapsed
                                mutableState.update { it.copy(liveDecodeTokensPerSecond = liveRate) }
                                lastRateUpdateAt = rateNow
                            }
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
                            if (event.call.name == "propose_skill") {
                                refreshSkills()
                                // A draft is untrusted text, so it must not wait unseen: nudge the
                                // user to review it when they are not already watching the turn.
                                if (!appForeground) maybePostSkillDraftNotification(event.call.argumentsJson, event.result)
                            }
                        }
                        is AgentEvent.Usage -> mutableState.update { it.copy(lastUsage = event.usage) }
                        is AgentEvent.Metrics -> mutableState.update {
                            it.copy(
                                lastMetrics = event.metrics,
                                liveDecodeTokensPerSecond = event.metrics.decodeTokensPerSecond,
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
                    it.copy(
                        messages = settled,
                        isGenerating = false,
                        status = null,
                        liveDecodeTokensPerSecond = null,
                    )
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
// Neutral filler tokens prepended to every measurement prompt, so the decode rate is measured
// against a realistic context instead of the bare 63-token reference prompt. A real chat turn
// caches thousands of tokens, and attention over all of them is what makes long replies slow —
// a number taken at a fresh context would promise chat speed it cannot deliver. 1024 balances
// realism against measurement cost: each padded call has to decode the filler on top of the
// timing work, and the whole pass multiplies that.
private const val REFERENCE_PAD_TOKENS = 1024
// A 24-token reference decode that takes longer than this is a throttled CPU, not a healthy
// measurement: on a cool phone it takes under a second, and even a warm one stays well under
// 30s. Sweeps park and retry the reference instead of using a number taken through throttling.
private const val MAX_REFERENCE_MILLIS = 30_000L
// How long one reference attempt may wedge before it counts as hung (not slow) and the
// inference process is restarted. A throttled-but-returning reference on the S25 Ultra took up
// to 5.3 minutes, so the deadline must clear that; a true hang is caught soon after.
private const val REFERENCE_TIMEOUT_MILLIS = 7 * 60 * 1_000L
// Wedged references are retried this many times before the sweep gives up with a note; slow
// references park and retry until the phone cools (or Continue-anyway is pressed).
private const val MAX_REFERENCE_ATTEMPTS = 3
// A broken backend kernel can hang a load or decode without ever reporting an error, so a
// candidate measurement is bounded: 5 minutes is 30-60x the expected time for a phone-sized
// model, which turns a hang into a recorded failure instead of a stall.
private const val CANDIDATE_TIMEOUT_MILLIS = 5 * 60 * 1_000L
// The prompt batches the batch sweep compares. The default (512/128) is in the list so there is
// always a baseline to fall back to, and the wide end covers the Hexagon reference
// configuration, which runs ubatch 1024. Shared with the overlay's up-front plan.
private val BATCH_CANDIDATES = listOf(
    256 to 128,
    512 to 128,
    512 to 256,
    1024 to 128,
    1024 to 256,
    1024 to 512,
)

// Before the full measurement, every candidate runs a cheap probe — its load, a full
// teacher-forced replay, and a handful of decode tokens — so a configuration that hangs is
// abandoned faster than the full measurement timeout. The floor is generous on purpose: a
// genuinely slow-but-healthy candidate (mask-restricted threadpools, a warm phone) can need
// ~45s for the same probe, and calling that a hang poisons the sweep; the real hang guard is
// the measurement timeout, and the probe only shortens the wait for configurations that stop
// responding entirely.
private const val PROBE_TIMEOUT_MILLIS = 90_000L
private const val PROBE_TOKENS = 4
// Sweeps measure the device itself, so they refuse to run through throttling or memory pressure:
// a number taken while throttling or swapping is a lie with a timestamp.
// The thermal statuses measurements are allowed to run under. Only "none" qualifies: a device
// that reports "light" is often already throttling hard (the S25 Ultra measures 500x slower at
// "light" after sustained load), so the sweep parks until the phone is properly cool. The
// overlay's Continue-anyway button overrides this for the rest of the pass.
private val ALLOWED_SWEEP_THERMAL = setOf("none")
private const val MIN_SWEEP_RAM_BYTES = 1_500_000_000L

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
