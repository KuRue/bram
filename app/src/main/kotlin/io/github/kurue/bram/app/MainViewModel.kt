package io.github.kurue.bram.app

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kurue.bram.core.domain.AgentEvent
import io.github.kurue.bram.core.domain.AgentRunRequest
import io.github.kurue.bram.core.domain.ConversationId
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
) {
    val selectedLocalModel: LocalModelRecord?
        get() = localModels.firstOrNull { it.id.value == selectedRuntimeId }

    val selectedEndpoint: RemoteEndpoint?
        get() = endpoints.firstOrNull { remoteRuntimeId(it.id) == selectedRuntimeId }

    val selectedLocalModelIsLoaded: Boolean
        get() = selectedLocalModel?.id?.value == loadedModelId && cpuValidated
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
    }

    fun refreshDeviceProfile() {
        val validated = mutableState.value.cpuValidated
        mutableState.update { it.copy(deviceProfile = container.deviceProfiler.snapshot(validated)) }
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

    fun loadModel(modelId: String) {
        val model = mutableState.value.localModels.firstOrNull { it.id.value == modelId } ?: return
        if (mutableState.value.isLoadingModel || mutableState.value.isGenerating) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    selectedRuntimeId = modelId,
                    isLoadingModel = true,
                    status = "Loading ${model.displayName} and running CPU self-test…",
                    error = null,
                    modelLoadDetail = null,
                )
            }
            runCatching {
                val visibleCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
                val threads = (visibleCores - 2).coerceIn(1, 4)
                container.llamaCppClient.load(model, threads)
            }.onSuccess { result ->
                mutableState.update {
                    it.copy(
                        loadedModelId = modelId,
                        cpuValidated = result.optBoolean("cpuValidated"),
                        modelLoadDetail = buildString {
                            append(result.optString("description", model.displayName))
                            append(" · ")
                            append(result.optInt("contextTokens", model.preferredContextTokens))
                            append(" context · ")
                            append(result.optInt("threads", 1))
                            append(" threads")
                            result.optLong("processPssBytes").takeIf { bytes -> bytes > 0 }?.let { bytes ->
                                append(" · ")
                                append(bytes / 1_048_576L)
                                append(" MB process PSS")
                            }
                            append(" · CPU self-test passed")
                        },
                    )
                }
                refreshDeviceProfile()
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        loadedModelId = null,
                        cpuValidated = false,
                        error = error.message ?: "Could not load the local model",
                    )
                }
                refreshDeviceProfile()
            }
            mutableState.update { it.copy(isLoadingModel = false, status = null) }
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
