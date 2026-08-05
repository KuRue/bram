package io.github.kurue.bram.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kurue.bram.core.domain.AcceleratorAgreement
import io.github.kurue.bram.core.domain.AgentEvent
import io.github.kurue.bram.core.domain.AgentRunRequest
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.findOffloadBoundary
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.DeviceProfile
import io.github.kurue.bram.core.domain.GenerationMetrics
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.MessageRole
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
    HEXAGON("Hexagon NPU", "HTP"),
}

/**
 * Where a model actually runs. Separate from [AcceleratorTarget] because CPU is a real choice for
 * running, not something to validate against itself.
 */
enum class RuntimeBackend(val label: String, val devicePrefix: String) {
    CPU("CPU", ""),
    VULKAN("Adreno GPU", "Vulkan"),
    HEXAGON("Hexagon NPU", "HTP"),
    ;

    val offloadsToAccelerator: Boolean get() = this != CPU

    companion object {
        /** Persisted as the enum name; anything unrecognised falls back to CPU. */
        fun fromId(id: String?): RuntimeBackend =
            entries.firstOrNull { it.name == id } ?: CPU
    }
}

data class AppUiState(
    val deviceProfile: DeviceProfile? = null,
    val localModels: List<LocalModelRecord> = emptyList(),
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
        RuntimeBackend.fromId(model.preferredBackendId).takeIf { it in availableBackends }
            ?: RuntimeBackend.CPU
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
    private val conversationId = ConversationId(UUID.randomUUID().toString())
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
        refreshDeviceProfile()
        reloadCatalogs()
        detectBackends()
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

    fun removeLocalModel(modelId: String) {
        viewModelScope.launch {
            if (mutableState.value.loadedModelId == modelId) unloadModelInternal()
            container.localModelStore.remove(io.github.kurue.bram.core.domain.ModelId(modelId))
            reloadLocalModels()
        }
    }

    fun setPreferredContext(modelId: String, tokens: Int) {
        viewModelScope.launch {
            if (mutableState.value.loadedModelId == modelId) unloadModelInternal()
            container.localModelStore.updatePreferredContext(
                io.github.kurue.bram.core.domain.ModelId(modelId),
                tokens,
            )
            reloadLocalModels(selectId = modelId)
        }
    }

    fun selectBackend(modelId: String, backend: RuntimeBackend) {
        viewModelScope.launch {
            if (mutableState.value.loadedModelId == modelId) unloadModelInternal()
            container.localModelStore.updatePreferredBackend(
                io.github.kurue.bram.core.domain.ModelId(modelId),
                backend.name,
            )
            reloadLocalModels(selectId = modelId)
        }
    }

    fun loadModel(modelId: String) {
        val model = mutableState.value.localModels.firstOrNull { it.id.value == modelId } ?: return
        if (mutableState.value.isLoadingModel || mutableState.value.isGenerating) return
        val backend = mutableState.value.backendFor(model)
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    selectedRuntimeId = modelId,
                    isLoadingModel = true,
                    status = "Loading ${model.displayName} on ${backend.label}…",
                    error = null,
                    modelLoadDetail = null,
                )
            }
            runCatching {
                val visibleCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val threads = (visibleCores - 2).coerceIn(1, 4)
                container.llamaCppClient.load(
                    model = model,
                    threads = threads,
                    gpuLayers = if (backend.offloadsToAccelerator) FULL_GPU_OFFLOAD else 0,
                    deviceFilter = backend.devicePrefix,
                )
            }.onSuccess { result ->
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
    fun validateAccelerator(modelId: String, target: AcceleratorTarget = AcceleratorTarget.VULKAN) {
        val model = mutableState.value.localModels.firstOrNull { it.id.value == modelId } ?: return
        val state = mutableState.value
        if (state.isLoadingModel || state.isGenerating || state.isValidatingAccelerator) return
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

                mutableState.update { it.copy(status = "Replaying the reference on $deviceName…") }
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
            }.onSuccess { report ->
                mutableState.update { it.copy(acceleratorReport = report) }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(error = error.message ?: "Could not validate the accelerator")
                }
            }
            // The comparison leaves the runtime in whatever state the last load produced; drop it
            // so the user always returns to a clean, explicitly chosen load.
            runCatching { unloadModelInternal() }
            mutableState.update { it.copy(isValidatingAccelerator = false, status = null) }
            refreshDeviceProfile()
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
            runCatching { unloadModelInternal() }
            mutableState.update { it.copy(isValidatingAccelerator = false, status = null) }
            refreshDeviceProfile()
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

    fun clearChat() {
        if (mutableState.value.isGenerating) return
        mutableState.update {
            it.copy(
                messages = emptyList(),
                status = null,
                error = null,
                lastUsage = null,
                lastMetrics = null,
            )
        }
    }

    fun stopGeneration() {
        if (!mutableState.value.isGenerating) return
        mutableState.update { it.copy(status = "Stopping…") }
        generationJob?.cancel(CancellationException("Stopped by user"))
    }

    fun send(text: String) {
        val prompt = text.trim()
        val snapshot = mutableState.value
        if (prompt.isEmpty() || snapshot.isGenerating) return

        val selection = selectedRuntime(snapshot)
        if (selection == null) {
            mutableState.update { it.copy(error = "Import and load a GGUF, or select an optional remote provider.") }
            return
        }
        if (selection.localModel != null && !snapshot.selectedLocalModelIsLoaded) {
            mutableState.update { it.copy(error = "Load ${selection.localModel.displayName} before chatting.") }
            return
        }

        val priorMessages = snapshot.messages
        val userMessage = ConversationMessage(role = MessageRole.USER, content = prompt)
        val requestMessages = priorMessages + userMessage
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

        generationJob = viewModelScope.launch {
            val agent = container.agent()
            var assistantText = ""
            var completedMessage: ConversationMessage? = null
            try {
                agent.run(
                    request = AgentRunRequest(
                        conversationId = conversationId,
                        messages = requestMessages,
                        identity = BramDefaults.IDENTITY,
                        maxOutputTokens = minOf(2_048, selection.runtime.model.contextWindowTokens / 4),
                    ),
                    runtime = selection.runtime,
                ).collect { event ->
                    when (event) {
                        is AgentEvent.Status -> mutableState.update { it.copy(status = event.text) }
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
                            mutableState.update {
                                it.copy(
                                    messages = requestMessages + ConversationMessage(
                                        role = MessageRole.ASSISTANT,
                                        content = assistantText,
                                    ),
                                )
                            }
                        }
                        is AgentEvent.ToolStarted -> mutableState.update { it.copy(status = "Running ${event.call.name}…") }
                        is AgentEvent.ToolFinished -> mutableState.update { it.copy(status = "Tool complete; returning result…") }
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
                val final = completedMessage
                mutableState.update {
                    it.copy(
                        messages = when {
                            final != null -> requestMessages + final
                            assistantText.isNotBlank() -> requestMessages + ConversationMessage(
                                role = MessageRole.ASSISTANT,
                                content = assistantText,
                            )
                            else -> requestMessages
                        },
                        isGenerating = false,
                        status = null,
                    )
                }
                generationJob = null
                refreshDeviceProfile()
            }
        }
    }

    private suspend fun unloadModelInternal() {
        runCatching { container.llamaCppClient.unload() }
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
        generationJob?.cancel()
        container.llamaCppClient.processFailureListener = null
        container.llamaCppClient.close()
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
