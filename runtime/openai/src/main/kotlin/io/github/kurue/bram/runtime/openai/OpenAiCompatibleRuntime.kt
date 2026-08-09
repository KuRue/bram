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
        val kind = when (endpoint.apiKind) {
            RemoteApiKind.CHAT_COMPLETIONS -> "Chat Completions"
            RemoteApiKind.RESPONSES -> "Responses API"
        }
        return RuntimeAvailability(true, "Configured ($kind)", "Connectivity is checked on the first request")
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
        val connection = (URL(targetUrl()).openConnection() as HttpURLConnection).apply {
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
            val payload = request.toApiJson().toString().toByteArray(Charsets.UTF_8)
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
            request.parseApiResponse(JSONObject(body))
        } finally {
            activeConnections.remove(request.requestId)
            connection.disconnect()
        }
    }

    private fun targetUrl(): String {
        val base = endpoint.baseUrl.trimEnd('/')
        return when (endpoint.apiKind) {
            RemoteApiKind.CHAT_COMPLETIONS ->
                if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
            RemoteApiKind.RESPONSES ->
                if (base.endsWith("/responses")) base else "$base/responses"
        }
    }

    /**
     * Each API kind gets its own wire schema, but the semantics the orchestrator needs are the
     * same: the conversation in, text and tool calls out.
     */
    private fun GenerationRequest.toApiJson(): JSONObject = when (endpoint.apiKind) {
        RemoteApiKind.CHAT_COMPLETIONS -> toChatJson()
        RemoteApiKind.RESPONSES -> toResponsesJson()
    }

    private fun GenerationRequest.parseApiResponse(root: JSONObject): ParsedResponse =
        when (endpoint.apiKind) {
            RemoteApiKind.CHAT_COMPLETIONS -> parseChatResponse(root)
            RemoteApiKind.RESPONSES -> parseResponsesResponse(root)
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

    /**
     * The Responses API folds system messages into the response-level `instructions` and carries
     * the conversation as `input` items: messages for user/assistant turns, `function_call` items
     * for assistant tool calls, and `function_call_output` items for the tool results.
     */
    private fun GenerationRequest.toResponsesJson(): JSONObject {
        val system = messages
            .filter { it.role == MessageRole.SYSTEM }
            .joinToString("\n\n") { it.content }
        val root = JSONObject()
            .put("model", endpoint.modelName)
            .put(
                "input",
                JSONArray().also { array ->
                    messages.forEach { message ->
                        message.toResponsesItems().forEach(array::put)
                    }
                },
            )
            .put("max_output_tokens", maxOutputTokens)
            .put("temperature", sampler.temperature)
            .put("top_p", sampler.topP)
            .put("stream", false)
        if (system.isNotBlank()) root.put("instructions", system)
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
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("parameters", schema),
                        )
                    }
                },
            )
            root.put("tool_choice", "auto")
        }
        return root
    }

    private fun ConversationMessage.toResponsesItems(): List<JSONObject> = when (role) {
        MessageRole.SYSTEM -> emptyList() // folded into instructions above
        MessageRole.TOOL -> listOf(
            JSONObject()
                .put("type", "function_call_output")
                .put("call_id", requireNotNull(toolCallId) { "Tool messages require toolCallId" })
                .put("output", content),
        )
        else -> buildList {
            add(
                JSONObject()
                    .put("type", "message")
                    .put("role", role.name.lowercase())
                    .put(
                        "content",
                        JSONArray().also { parts ->
                            if (content.isNotBlank()) {
                                parts.put(
                                    JSONObject()
                                        .put(
                                            "type",
                                            if (role == MessageRole.ASSISTANT) "output_text" else "input_text",
                                        )
                                        .put("text", content),
                                )
                            }
                        },
                    ),
            )
            toolCalls.forEach { call ->
                add(
                    JSONObject()
                        .put("type", "function_call")
                        .put("call_id", call.id)
                        .put("name", call.name)
                        .put("arguments", call.argumentsJson),
                )
            }
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

    private fun parseChatResponse(root: JSONObject): ParsedResponse {
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

    /**
     * Parses a non-streaming Responses API reply. Tolerant by design: content may be parts or a
     * plain string, unknown output types are skipped, and a missing usage stays null — the server
     * may implement the 2025-03-26 schema loosely.
     */
    private fun parseResponsesResponse(root: JSONObject): ParsedResponse {
        val status = root.optString("status", "completed")
        if (status == "failed") {
            val message = root.optJSONObject("error")?.optString("message")
                ?.takeIf(String::isNotBlank)
                ?: "The response ended with status failed"
            throw RemoteEndpointException(200, message)
        }

        val output = root.optJSONArray("output") ?: JSONArray()
        val text = buildString {
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                if (item.optString("type") != "message") continue
                when (val content = item.opt("content")) {
                    is String -> append(content)
                    is JSONArray -> {
                        for (partIndex in 0 until content.length()) {
                            val part = content.optJSONObject(partIndex) ?: continue
                            if (part.optString("type") == "output_text") append(part.optString("text"))
                        }
                    }
                    else -> Unit
                }
            }
        }

        val calls = buildList {
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                if (item.optString("type") != "function_call") continue
                add(
                    ToolCall(
                        id = item.optString("call_id", "call-$index"),
                        name = item.optString("name").takeIf(String::isNotBlank) ?: "unknown",
                        argumentsJson = item.optString("arguments", "{}"),
                    ),
                )
            }
        }

        val usage = root.optJSONObject("usage")?.let {
            TokenUsage(
                inputTokens = it.optionalInt("input_tokens"),
                outputTokens = it.optionalInt("output_tokens"),
            )
        }
        return ParsedResponse(
            text = text.toString(),
            toolCalls = calls,
            usage = usage,
            finishReason = status,
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
