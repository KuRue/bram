package io.github.kurue.bram.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kurue.bram.core.domain.AcceleratorAgreement
import io.github.kurue.bram.core.domain.AgentActivity
import io.github.kurue.bram.core.domain.AgentEvent
import io.github.kurue.bram.core.domain.AgentRunRequest
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.findOffloadBoundary
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.ConversationSummary
import io.github.kurue.bram.core.domain.DeviceProfile
import io.github.kurue.bram.core.domain.GenerationMetrics
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ReasoningFormat
import io.github.kurue.bram.core.domain.ModelProfile
import io.github.kurue.bram.core.domain.SamplerSettings
import io.github.kurue.bram.core.domain.ToolApprovalDecision
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.RemoteApiKind
import io.github.kurue.bram.core.domain.RemoteEndpoint
import io.github.kurue.bram.core.domain.TokenUsage
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
    VULKAN("Adreno (Vulkan)", "Vulkan"),
    // ggml names the OpenCL device GPUOpenCL, which is what the runtime reports back.
    OPENCL("Adreno (OpenCL)", "GPUOpenCL"),
    HEXAGON("Hexagon NPU", "HTP"),
}

/**
 * Where a model actually runs. Separate from [AcceleratorTarget] because CPU is a real choice for
 * running, not something to validate against itself.
 */
enum class RuntimeBackend(val label: String, val devicePrefix: String) {
    CPU("CPU", ""),
    VULKAN("Adreno GPU", "Vulkan"),
    OPENCL("Adreno OpenCL", "GPUOpenCL"),
    HEXAGON("Hexagon NPU", "HTP"),
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
    if (!format.isUsable) return StreamingReply(text, emptyList(), null)
    val startTag = format.startTag
    // A model that writes the opening tag itself is authoritative: read from the marker wherever it
    // is. Only when there is none does the prompt's own opening apply.
    val startsInReasoning = format.startsOpen && !text.contains(startTag)
    if (!startsInReasoning && !text.contains(startTag)) {
        return StreamingReply(text, emptyList(), null)
    }

    val visible = StringBuilder()
    val closed = mutableListOf<String>()
    var open: String? = null
    var cursor = 0
    var inReasoning = startsInReasoning
    while (cursor < text.length) {
        if (inReasoning) {
            val end = format.firstEndTagFrom(text, cursor)
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
    val isImporting: Boolean = false,
    val importProgress: ModelImportProgress? = null,
    val isLoadingModel: Boolean = false,
    val modelLoadDetail: String? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val lastUsage: TokenUsage? = null,
    val lastMetrics: GenerationMetrics? = null,
    val modelStorageBytes: Long = 0,
    val conversations: List<ConversationSummary> = emptyList(),
    val activeConversationId: String? = null,
    /** Backends this build found on the device, CPU always included. */
    val availableBackends: List<RuntimeBackend> = listOf(RuntimeBackend.CPU),
    /** What the currently loaded model is actually running on. */
    val loadedBackend: RuntimeBackend? = null,
    val isValidatingAccelerator: Boolean = false,
    val acceleratorReport: AcceleratorReport? = null,
    val acceleratorBisection: AcceleratorBisection? = null,
) {
    val selectedLocalModel: LocalModelRecord?
        get() = localModels.firstOrNull { it.id.value == selectedRuntimeId }

    val selectedEndpoint: RemoteEndpoint?
        get() = endpoints.firstOrNull { remoteRuntimeId(it.id) == selectedRuntimeId }

    val selectedLocalModelIsLoaded: Boolean
        get() = selectedLocalModel?.id?.value == loadedModelId && cpuValidated

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
}

data class EndpointDraft(
    val displayName: String,
    val baseUrl: String,
    val modelName: String,
    val contextWindowTokens: Int,
    val apiKey: String,
    val allowInsecureHttp: Boolean,
)

class MainViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var conversationId = ConversationId(UUID.randomUUID().toString())
    private var generationJob: Job? = null

    init {
        container.llamaCppClient.processFailureListener = { message ->
            mutableState.update {
                it.copy(
                    loadedModelId = null,
                    cpuValidated = false,
                    modelLoadDetail = null,
                    error = message,
                )
            }
            refreshDeviceProfile()
        }
        viewModelScope.launch {
            container.approvalGate.pending.collect { pending ->
                mutableState.update { it.copy(pendingApproval = pending) }
            }
        }
        refreshToolPermissions()
        refreshDeviceProfile()
        reloadCatalogs()
        detectBackends()
        restoreConversations()
    }

    /** Reopens the most recent conversation so closing Bram does not discard the thread. */
    private fun restoreConversations() {
        viewModelScope.launch {
            val summaries = runCatching { container.conversationStore.list() }.getOrDefault(emptyList())
            val mostRecent = summaries.firstOrNull()
            val messages = mostRecent
                ?.let { summary -> runCatching { container.conversationStore.load(summary.id) }.getOrDefault(emptyList()) }
                .orEmpty()
            mostRecent?.let { conversationId = it.id }
            mutableState.update {
                it.copy(
                    conversations = summaries,
                    activeConversationId = mostRecent?.id?.value,
                    messages = messages,
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
        mutableState.update {
            it.copy(
                messages = emptyList(),
                activeConversationId = conversationId.value,
                status = null,
                error = null,
                lastUsage = null,
                lastMetrics = null,
            )
        }
    }

    fun openConversation(id: String) {
        if (mutableState.value.isGenerating) return
        viewModelScope.launch {
            val target = ConversationId(id)
            val messages = runCatching { container.conversationStore.load(target) }.getOrDefault(emptyList())
            conversationId = target
            mutableState.update {
                it.copy(
                    messages = messages,
                    activeConversationId = id,
                    status = null,
                    error = null,
                    lastUsage = null,
                    lastMetrics = null,
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
            val detected = runCatching {
                val devices = container.llamaCppClient.devices()
                val names = (0 until devices.optJSONArray("devices")?.length().orZero())
                    .map { index -> devices.getJSONArray("devices").getJSONObject(index).optString("name") }
                listOf(RuntimeBackend.CPU) + RuntimeBackend.entries.filter { backend ->
                    backend.offloadsToAccelerator && names.any { it.startsWith(backend.devicePrefix) }
                }
            }.getOrDefault(listOf(RuntimeBackend.CPU))
            mutableState.update { current ->
                current.copy(availableBackends = detected)
            }
            refreshDeviceProfile()
        }
    }

    /** Removes model copies nothing in the catalog references and reports what was reclaimed. */
    fun reclaimModelStorage() {
        viewModelScope.launch {
            runCatching { container.localModelStore.deleteOrphanedCopies() }
                .onSuccess { reclaimed ->
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
                .onFailure { error ->
                    mutableState.update {
                        it.copy(error = error.message ?: "Could not reclaim model storage")
                    }
                }
        }
    }

    private fun refreshModelStorage() {
        viewModelScope.launch {
            val bytes = runCatching { container.localModelStore.storageBytesUsed() }.getOrDefault(0L)
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
            runCatching {
                container.localModelStore.importModel(uri) { progress ->
                    mutableState.update { it.copy(importProgress = progress) }
                }
            }.onSuccess { model ->
                reloadLocalModels(selectId = model.id.value)
            }.onFailure { error ->
                mutableState.update { it.copy(error = error.message ?: "Could not import the GGUF") }
            }
            mutableState.update { it.copy(isImporting = false, importProgress = null) }
        }
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
        if (snapshot.isLoadingModel || snapshot.isGenerating || snapshot.isValidatingAccelerator) return
        val restoreLoaded = snapshot.loadedModelId

        viewModelScope.launch {
            mutableState.update {
                it.copy(isValidatingAccelerator = true, error = null, status = "Measuring backends…")
            }
            val visibleCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val threads = (visibleCores - 2).coerceIn(1, 4)

            val candidates = snapshot.availableBackends
                .filter(RuntimeBackend::offloadsToAccelerator)
                .mapNotNull { backend ->
                    AcceleratorTarget.entries
                        .firstOrNull { it.devicePrefix == backend.devicePrefix }
                        ?.let { backend to it }
                }

            val measured = mutableListOf<Pair<RuntimeBackend, AcceleratorReport>>()
            for ((backend, target) in candidates) {
                mutableState.update { it.copy(status = "Measuring ${backend.label}…") }
                // One backend failing is a result, not an error: it means do not use that one.
                val report = runCatching {
                    measureBackend(model, target, threads) { message ->
                        mutableState.update { it.copy(status = message) }
                    }
                }.getOrNull()
                if (report != null) measured += backend to report
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

            container.modelProfileStore.save(
                profile.copy(
                    backendId = best?.first?.takeIf { it != RuntimeBackend.CPU }?.name.orEmpty(),
                    autoConfiguredNote = note,
                    autoConfiguredAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            runCatching { unloadModelInternal(forget = false) }
            mutableState.update { it.copy(isValidatingAccelerator = false, status = note) }
            refreshDeviceProfile()
            reloadProfiles(selectId = profile.id)
            restoreLoaded?.let { loadModel(it) }
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
            // A model with no profile cannot be loaded, and the store would just recreate a default
            // on the next sync, so the last one stays.
            if (state.profiles.count { it.modelId == profile.modelId } <= 1) return@launch
            if (state.activeProfileId == profileId && state.loadedModelId != null) {
                unloadModelInternal(forget = false)
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
                    previous.thinkingEnabled != profile.thinkingEnabled
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
        val profile = state.profiles.firstOrNull { it.id == profileId } ?: return
        val model = state.localModels.firstOrNull { it.id == profile.modelId } ?: return
        val modelId = model.id.value
        if (state.isLoadingModel || state.isGenerating) return
        val backend = RuntimeBackend.fromId(profile.backendId)
            .takeIf { it in state.availableBackends } ?: RuntimeBackend.CPU
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    selectedRuntimeId = modelId,
                    activeProfileId = profile.id,
                    isLoadingModel = true,
                    status = "Loading ${profile.name} on ${backend.label}…",
                    error = null,
                    modelLoadDetail = null,
                )
            }
            runCatching {
                val visibleCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val threads = (visibleCores - 2).coerceIn(1, 4)
                container.llamaCppClient.load(
                    // The profile's context size, not the file's: the same GGUF may be configured
                    // for a long context in one profile and a cheap one in another.
                    model = model.copy(preferredContextTokens = profile.contextTokens),
                    threads = threads,
                    gpuLayers = if (backend.offloadsToAccelerator) FULL_GPU_OFFLOAD else 0,
                    deviceFilter = backend.devicePrefix,
                    enableThinking = profile.thinkingEnabled,
                )
            }.onSuccess { result ->
                viewModelScope.launch {
                    container.localModelStore.setLastLoadedModelId(modelId)
                    container.modelProfileStore.setLastUsedProfileId(profile.id)
                }
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
                refreshDeviceProfile()
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        loadedModelId = null,
                        loadedBackend = null,
                        cpuValidated = false,
                        error = error.message ?: "Could not load the local model",
                    )
                }
                refreshDeviceProfile()
            }
            mutableState.update { it.copy(isLoadingModel = false, status = null) }
        }
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

            container.llamaCppClient.load(model, threads, gpuLayers = 0)
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
        val model = mutableState.value.localModels.firstOrNull { it.id.value == modelId } ?: return
        val state = mutableState.value
        if (state.isLoadingModel || state.isGenerating || state.isValidatingAccelerator) return
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
                measureBackend(model, target, threads) { message ->
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
     * Narrows a failing accelerator down to a layer boundary. Records the CPU reference once, then
     * binary-searches the offload count for the largest value that still reproduces it. Whether the
     * boundary lands at zero or partway through separates "a shared operation is broken" from
     * "one layer's operation is broken".
     */
    fun bisectAccelerator(modelId: String, target: AcceleratorTarget = AcceleratorTarget.VULKAN) {
        val model = mutableState.value.localModels.firstOrNull { it.id.value == modelId } ?: return
        val state = mutableState.value
        if (state.isLoadingModel || state.isGenerating || state.isValidatingAccelerator) return
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

                container.llamaCppClient.load(model, threads, gpuLayers = 0)
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
                apiKind = RemoteApiKind.CHAT_COMPLETIONS,
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
            container.endpointStore.remove(endpointId)
            reloadEndpoints()
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

        val selection = selectedRuntime(snapshot)
        if (selection == null) {
            mutableState.update { it.copy(error = "Import and load a GGUF, or select an optional remote provider.") }
            return
        }
        if (selection.localModel != null && !snapshot.selectedLocalModelIsLoaded) {
            mutableState.update { it.copy(error = "Load ${selection.localModel.displayName} before chatting.") }
            return
        }
        val prompt = requestMessages.lastOrNull { it.role == MessageRole.USER }?.content.orEmpty()
        mutableState.update {
            it.copy(
                messages = requestMessages,
                isGenerating = true,
                status = "Preparing context…",
                error = null,
                lastUsage = null,
                lastMetrics = null,
            )
        }

        // Run on the application scope, not the ViewModel's: agent work is expected to continue
        // while the user is elsewhere, and a run tied to the screen would be cancelled the moment
        // the ViewModel is cleared. AgentTaskService keeps the process alive for the duration.
        AgentTaskService.start(container.appContext, "Answering: ${prompt.take(40)}")
        generationJob = container.appScope.launch {
            val agent = container.agent()
            // Reported by the runtime before any text arrives, since only it knows what the loaded
            // chat template uses. Until it does, an empty format leaves the stream alone rather
            // than splitting it on tags that may not be this model's.
            var reasoningFormat = ReasoningFormat()
            var assistantText = ""
            var completedMessage: ConversationMessage? = null
            val activity = mutableListOf<AgentActivity>()
            var thinkingStartedAt = 0L
            val finishedThinking = mutableListOf<AgentActivity.Thinking>()
            var thinkingMillisTotal = 0L
            try {
                agent.run(
                    request = AgentRunRequest(
                        conversationId = conversationId,
                        messages = requestMessages,
                        identity = BramDefaults.IDENTITY,
                        maxOutputTokens = minOf(2_048, selection.runtime.model.contextWindowTokens / 4),
                        sampler = snapshot.activeProfile?.sampler ?: SamplerSettings(),
                        profileInstructions = snapshot.activeProfile?.systemPrompt.orEmpty(),
                    ),
                    runtime = selection.runtime,
                ).collect { event ->
                    when (event) {
                        is AgentEvent.Status -> mutableState.update { it.copy(status = event.text) }
                        is AgentEvent.Reasoning -> reasoningFormat = event.format
                        is AgentEvent.ContextPrepared -> mutableState.update {
                            it.copy(
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
                            val streaming = streamingReply(assistantText, reasoningFormat)
                            val now = System.currentTimeMillis()
                            // A block that has just closed keeps the time it actually took; leaving
                            // it on the running clock would have every finished block claim the
                            // duration of the whole turn.
                            while (finishedThinking.size < streaming.closedReasoning.size) {
                                val index = finishedThinking.size
                                val took = if (thinkingStartedAt > 0) now - thinkingStartedAt else 0L
                                finishedThinking += AgentActivity.Thinking(
                                    text = streaming.closedReasoning[index],
                                    durationMillis = took,
                                    inProgress = false,
                                )
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
                            mutableState.update {
                                it.copy(
                                    messages = requestMessages + ConversationMessage(
                                        role = MessageRole.ASSISTANT,
                                        content = streaming.visibleText,
                                        activity = activity + finishedThinking + listOfNotNull(inFlight),
                                    ),
                                )
                            }
                        }
                        is AgentEvent.ToolStarted -> {
                            activity += AgentActivity.ToolInvocation(
                                id = event.call.id,
                                name = event.call.name,
                                argumentsJson = event.call.argumentsJson,
                            )
                            mutableState.update {
                                it.copy(
                                    status = "Running ${event.call.name}…",
                                    messages = requestMessages + inFlightMessage(streamingReply(assistantText, reasoningFormat).visibleText, activity + finishedThinking),
                                )
                            }
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
                                    messages = requestMessages + inFlightMessage(streamingReply(assistantText, reasoningFormat).visibleText, activity + finishedThinking),
                                )
                            }
                        }
                        is AgentEvent.Usage -> mutableState.update { it.copy(lastUsage = event.usage) }
                        is AgentEvent.Metrics -> mutableState.update { it.copy(lastMetrics = event.metrics) }
                        is AgentEvent.Completed -> completedMessage = event.message
                        is AgentEvent.Failed -> mutableState.update {
                            val localPlanFailed = selection.localModel != null
                            it.copy(
                                error = event.message,
                                status = if (event.recoverable) "You can reload or choose another runtime." else null,
                                loadedModelId = if (localPlanFailed) null else it.loadedModelId,
                                cpuValidated = if (localPlanFailed) false else it.cpuValidated,
                            )
                        }
                    }
                }
            } catch (_: CancellationException) {
                mutableState.update { it.copy(status = "Generation stopped") }
            } catch (error: Throwable) {
                mutableState.update { it.copy(error = error.message ?: error::class.java.simpleName) }
            } finally {
                // Separate reasoning from the answer once the reply is complete: the transcript
                // shows thinking collapsed, and mid-stream the split is not yet determinable.
                val rawReply = completedMessage?.content ?: assistantText
                val reply = if (rawReply.isBlank() || selection.localModel == null) {
                    rawReply to ""
                } else {
                    runCatching {
                        val parsed = container.llamaCppClient.parseReply(rawReply)
                        parsed.optString("content").ifBlank { rawReply } to parsed.optString("reasoning")
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
                AgentTaskService.stop(container.appContext)
                refreshDeviceProfile()
            }
        }
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
        refreshDeviceProfile()
    }

    private fun reloadCatalogs() {
        viewModelScope.launch {
            val models = container.localModelStore.list()
            val endpoints = container.endpointStore.list()
            mutableState.update { current ->
                val selected = current.selectedRuntimeId?.takeIf { id ->
                    models.any { it.id.value == id } ||
                        endpoints.any { remoteRuntimeId(it.id) == id }
                } ?: models.firstOrNull()?.id?.value
                    ?: endpoints.firstOrNull()?.let { remoteRuntimeId(it.id) }
                current.copy(
                    localModels = models,
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
            if (current.loadedModelId != null || current.isLoadingModel) return@launch
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
                ?: return@launch
            val model = current.localModels.firstOrNull { it.id.value == lastId } ?: return@launch
            selectLocalModel(model.id.value)
            loadModel(model.id.value)
        }
    }

    private fun reloadLocalModels(selectId: String? = null) {
        viewModelScope.launch {
            val models = container.localModelStore.list()
            mutableState.update { current ->
                val selected = selectId
                    ?: current.selectedRuntimeId?.takeIf { id -> models.any { it.id.value == id } }
                    ?: current.selectedRuntimeId?.takeIf { it.startsWith(REMOTE_PREFIX) }
                    ?: models.firstOrNull()?.id?.value
                current.copy(localModels = models, selectedRuntimeId = selected, error = null)
            }
            refreshModelStorage()
            syncProfiles(models)
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

    private fun selectedRuntime(snapshot: AppUiState): RuntimeSelection? {
        snapshot.selectedLocalModel?.let { return RuntimeSelection(container.runtime(it), it) }
        snapshot.selectedEndpoint?.let { return RuntimeSelection(container.runtime(it), null) }
        return null
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
    )
}

internal const val REMOTE_PREFIX = "remote:"
internal fun remoteRuntimeId(endpointId: String): String = "$REMOTE_PREFIX$endpointId"

/** Tokens compared between backends. Long enough to catch drift, short enough to stay quick. */
private const val REFERENCE_TOKENS = 24

/** llama.cpp clamps this to the model's layer count, so it means "offload everything". */
private const val FULL_GPU_OFFLOAD = 999

private fun Int?.orZero(): Int = this ?: 0

private fun org.json.JSONArray?.toIntList(): List<Int> {
    val array = this ?: return emptyList()
    return (0 until array.length()).map(array::getInt)
}
