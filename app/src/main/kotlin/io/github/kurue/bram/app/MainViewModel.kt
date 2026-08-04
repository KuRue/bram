package io.github.kurue.bram.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kurue.bram.core.domain.AgentEvent
import io.github.kurue.bram.core.domain.AgentRunRequest
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.DeviceProfile
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.RemoteApiKind
import io.github.kurue.bram.core.domain.RemoteEndpoint
import io.github.kurue.bram.core.domain.TokenUsage
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppUiState(
    val deviceProfile: DeviceProfile? = null,
    val endpoints: List<RemoteEndpoint> = emptyList(),
    val selectedEndpointId: String? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val status: String? = null,
    val error: String? = null,
    val lastUsage: TokenUsage? = null,
)

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
        refreshDeviceProfile()
        reloadEndpoints()
    }

    fun refreshDeviceProfile() {
        mutableState.update { it.copy(deviceProfile = container.deviceProfiler.snapshot()) }
    }

    fun selectEndpoint(endpointId: String) {
        mutableState.update { it.copy(selectedEndpointId = endpointId, error = null) }
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
        mutableState.update { it.copy(messages = emptyList(), status = null, error = null, lastUsage = null) }
    }

    fun send(text: String) {
        val prompt = text.trim()
        val snapshot = mutableState.value
        if (prompt.isEmpty() || snapshot.isGenerating) return
        val endpoint = snapshot.endpoints.firstOrNull { it.id == snapshot.selectedEndpointId }
        if (endpoint == null) {
            mutableState.update { it.copy(error = "Add and select a model endpoint first.") }
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
            )
        }

        generationJob = viewModelScope.launch {
            val runtime = container.runtime(endpoint)
            val agent = container.agent()
            var assistantText = ""
            var completedMessage: ConversationMessage? = null

            try {
                agent.run(
                    request = AgentRunRequest(
                        conversationId = conversationId,
                        messages = requestMessages,
                        identity = BramDefaults.IDENTITY,
                        maxOutputTokens = minOf(2_048, endpoint.contextWindowTokens / 4),
                    ),
                    runtime = runtime,
                ).collect { event ->
                    when (event) {
                        is AgentEvent.Status -> mutableState.update { it.copy(status = event.text) }
                        is AgentEvent.ContextPrepared -> mutableState.update {
                            it.copy(
                                status = buildString {
                                    append("Context: ~${event.estimatedInputTokens} tokens")
                                    if (event.omittedMessageCount > 0) append(" · ${event.omittedMessageCount} older messages summarized/omitted")
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
                        is AgentEvent.ToolFinished -> mutableState.update { it.copy(status = "Tool complete; returning result to model…") }
                        is AgentEvent.Usage -> mutableState.update { it.copy(lastUsage = event.usage) }
                        is AgentEvent.Completed -> completedMessage = event.message
                        is AgentEvent.Failed -> mutableState.update {
                            it.copy(error = event.message, status = if (event.recoverable) "You can retry or choose another runtime." else null)
                        }
                    }
                }
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
            }
        }
    }

    private fun reloadEndpoints(selectId: String? = null) {
        viewModelScope.launch {
            val endpoints = container.endpointStore.list()
            mutableState.update { current ->
                val selected = selectId
                    ?: current.selectedEndpointId?.takeIf { id -> endpoints.any { it.id == id } }
                    ?: endpoints.firstOrNull()?.id
                current.copy(endpoints = endpoints, selectedEndpointId = selected, error = null)
            }
        }
    }

    private fun validate(draft: EndpointDraft): String? {
        if (draft.displayName.isBlank()) return "Provider name is required."
        if (draft.modelName.isBlank()) return "Model name is required."
        if (draft.contextWindowTokens !in 256..10_000_000) return "Context window must be between 256 and 10,000,000 tokens."
        val uri = runCatching { URI(draft.baseUrl.trim()) }.getOrNull() ?: return "Enter a valid endpoint URL."
        if (uri.host.isNullOrBlank()) return "Endpoint URL needs a hostname or IP address."
        if (uri.scheme !in setOf("https", "http")) return "Endpoint must use HTTPS or HTTP."
        if (uri.scheme == "http" && !draft.allowInsecureHttp) return "Enable insecure HTTP for this local endpoint or use HTTPS."
        return null
    }

    override fun onCleared() {
        generationJob?.cancel()
        super.onCleared()
    }
}
