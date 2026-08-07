package io.github.kurue.bram.runtime.openai

import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.EndpointCredentialResolver
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.RemoteApiKind
import io.github.kurue.bram.core.domain.RemoteEndpoint
import io.github.kurue.bram.core.domain.RuntimeAvailability
import io.github.kurue.bram.core.domain.TokenUsage
import io.github.kurue.bram.core.domain.ToolCall
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class OpenAiCompatibleRuntime(
    private val endpoint: RemoteEndpoint,
    private val credentialResolver: EndpointCredentialResolver,
) : ModelRuntime {
    override val model = endpoint.asModelDescriptor()
    private val activeConnections = ConcurrentHashMap<String, HttpURLConnection>()

    override suspend fun availability(): RuntimeAvailability {
        if (endpoint.apiKind != RemoteApiKind.CHAT_COMPLETIONS) {
            return RuntimeAvailability(
                available = false,
                summary = "Responses adapter not implemented",
                detail = "This scaffold currently supports OpenAI-compatible Chat Completions endpoints.",
            )
        }

        val uri = runCatching { URI(endpoint.baseUrl) }.getOrNull()
            ?: return RuntimeAvailability(false, "Invalid endpoint URL")
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("https", "http")) {
            return RuntimeAvailability(false, "Endpoint must use HTTPS or explicitly approved HTTP")
        }
        if (scheme == "http" && !endpoint.allowInsecureHttp) {
            return RuntimeAvailability(
                available = false,
                summary = "Insecure HTTP is blocked",
                detail = "Edit the endpoint and explicitly allow HTTP if this is a trusted local server.",
            )
        }
        return RuntimeAvailability(true, "Configured", "Connectivity is checked on the first request")
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        emit(GenerationEvent.Started("Remote: ${endpoint.displayName}/${endpoint.modelName}"))
        val result = runCatching { execute(request) }
        result.onSuccess { parsed ->
            if (parsed.text.isNotEmpty()) emit(GenerationEvent.TextDelta(parsed.text))
            parsed.toolCalls.forEach { emit(GenerationEvent.ToolCallReady(it)) }
            parsed.usage?.let { emit(GenerationEvent.Usage(it)) }
            emit(GenerationEvent.Finished(parsed.finishReason))
        }.onFailure { error ->
            emit(
                GenerationEvent.Failed(
                    message = error.message ?: error::class.java.simpleName,
                    recoverable = error !is EndpointConfigurationException,
                    cause = error,
                ),
            )
        }
    }

    override suspend fun cancel(requestId: String) {
        activeConnections.remove(requestId)?.disconnect()
    }

    private suspend fun execute(request: GenerationRequest): ParsedResponse = withContext(Dispatchers.IO) {
        val apiKey = credentialResolver.resolve(endpoint.id, endpoint.credentialAlias).orEmpty()
        val connection = (URL(chatCompletionsUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 0 // Local servers and storage-assisted models can legitimately take minutes.
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Bram-Android/0.1")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        activeConnections[request.requestId] = connection

        try {
            val payload = request.toChatJson().toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }
                    .getOrNull()
                    .takeUnless { it.isNullOrBlank() }
                    ?: body.take(4_096).ifBlank { "HTTP $status" }
                throw RemoteEndpointException(status, message)
            }
            parseResponse(JSONObject(body))
        } finally {
            activeConnections.remove(request.requestId)
            connection.disconnect()
        }
    }

    private fun chatCompletionsUrl(): String {
        val base = endpoint.baseUrl.trimEnd('/')
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    private fun GenerationRequest.toChatJson(): JSONObject = JSONObject()
        .put("model", endpoint.modelName)
        .put("messages", JSONArray().also { array -> messages.forEach { array.put(it.toChatJson()) } })
        // max_tokens remains the broadest common denominator across Ollama, LM Studio, vLLM,
        // llama.cpp server, and older OpenAI-compatible implementations. A Responses/native
        // OpenAI adapter can use max_output_tokens/max_completion_tokens independently.
        .put("max_tokens", maxOutputTokens)
        .put("temperature", sampler.temperature)
        // top_k is deliberately absent: it is not part of the OpenAI-compatible schema, and servers
        // that do accept it disagree on where it belongs.
        .put("top_p", sampler.topP)
        .put("stream", false)
        .also { root ->
            if (tools.isNotEmpty()) {
                root.put(
                    "tools",
                    JSONArray().also { array ->
                        tools.forEach { tool ->
                            val schema = runCatching { JSONObject(tool.inputSchemaJson) }
                                .getOrElse { JSONObject().put("type", "object") }
                            array.put(
                                JSONObject()
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", tool.name)
                                            .put("description", tool.description)
                                            .put("parameters", schema),
                                    ),
                            )
                        }
                    },
                )
                root.put("tool_choice", "auto")
            }
        }

    private fun ConversationMessage.toChatJson(): JSONObject {
        val root = JSONObject().put("role", role.name.lowercase())
        when (role) {
            MessageRole.TOOL -> {
                root.put("content", content)
                root.put("tool_call_id", requireNotNull(toolCallId) { "Tool messages require toolCallId" })
            }
            else -> {
                root.put("content", if (content.isBlank() && toolCalls.isNotEmpty()) JSONObject.NULL else content)
                if (toolCalls.isNotEmpty()) {
                    root.put(
                        "tool_calls",
                        JSONArray().also { calls ->
                            toolCalls.forEach { call ->
                                calls.put(
                                    JSONObject()
                                        .put("id", call.id)
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject()
                                                .put("name", call.name)
                                                .put("arguments", call.argumentsJson),
                                        ),
                                )
                            }
                        },
                    )
                }
            }
        }
        return root
    }

    private fun parseResponse(root: JSONObject): ParsedResponse {
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
            ?: throw RemoteEndpointException(200, "Response did not contain choices[0]")
        val message = choice.optJSONObject("message")
            ?: throw RemoteEndpointException(200, "Response did not contain choices[0].message")

        val calls = buildList {
            val array = message.optJSONArray("tool_calls") ?: JSONArray()
            for (index in 0 until array.length()) {
                val raw = array.getJSONObject(index)
                val function = raw.getJSONObject("function")
                add(
                    ToolCall(
                        id = raw.optString("id", "tool-call-$index"),
                        name = function.getString("name"),
                        argumentsJson = function.optString("arguments", "{}"),
                    ),
                )
            }
        }

        val usageJson = root.optJSONObject("usage")
        val usage = usageJson?.let {
            TokenUsage(
                inputTokens = it.optionalInt("prompt_tokens") ?: it.optionalInt("input_tokens"),
                outputTokens = it.optionalInt("completion_tokens") ?: it.optionalInt("output_tokens"),
            )
        }

        return ParsedResponse(
            text = message.optString("content", "").takeUnless { it == "null" }.orEmpty(),
            toolCalls = calls,
            usage = usage,
            finishReason = choice.optString("finish_reason", null),
        )
    }

    private fun JSONObject.optionalInt(name: String): Int? =
        if (has(name) && !isNull(name)) getInt(name) else null
}

private data class ParsedResponse(
    val text: String,
    val toolCalls: List<ToolCall>,
    val usage: TokenUsage?,
    val finishReason: String?,
)

private class EndpointConfigurationException(message: String) : IllegalArgumentException(message)

private class RemoteEndpointException(
    val statusCode: Int,
    message: String,
) : RuntimeException("Endpoint error ($statusCode): $message")
