package io.github.kurue.bram.runtime.openai

import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.EndpointCredentialResolver
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.ModelRuntime
import io.github.kurue.bram.core.domain.ProviderProfile
import io.github.kurue.bram.core.domain.RemoteApiKind
import io.github.kurue.bram.core.domain.RemoteEndpoint
import io.github.kurue.bram.core.domain.RuntimeAvailability
import io.github.kurue.bram.core.domain.TokenUsage
import io.github.kurue.bram.core.domain.ToolCall
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

    /**
     * One remote turn, streamed.
     *
     * The wire requests ask for SSE and the readers emit events as tokens arrive, so a slow model
     * fills the reply in instead of freezing the UI until its last token. A server that ignores
     * `stream: true` and answers one JSON body is still accepted: the readers fall back to the
     * whole-body parse, which is also what every non-streaming server sends.
     */
    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        emit(GenerationEvent.Started("Remote: ${endpoint.displayName}/${endpoint.modelName}"))
        try {
            when (endpoint.apiKind) {
                RemoteApiKind.CHAT_COMPLETIONS -> streamChat(request, this)
                RemoteApiKind.RESPONSES -> streamResponses(request, this)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            emit(
                GenerationEvent.Failed(
                    message = error.message ?: error::class.java.simpleName,
                    recoverable = error !is EndpointConfigurationException,
                    cause = error,
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(requestId: String) {
        activeConnections.remove(requestId)?.disconnect()
    }

    /** Chat Completions SSE: `choices[0].delta` fragments per chunk, `data: [DONE]` at the end. */
    private suspend fun streamChat(request: GenerationRequest, out: FlowCollector<GenerationEvent>) {
        val connection = openWithRetry(request)
        activeConnections[request.requestId] = connection
        try {
            if (!connection.isEventStream()) {
                emitWholeChatResponse(request, connection, out)
                return
            }
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val calls = sortedMapOf<Int, ChatToolCallBuilder>()
            var finishReason: String? = null
            connection.readSseLines { data ->
                if (data == "[DONE]") return@readSseLines false
                val chunk = runCatching { JSONObject(data) }.getOrNull() ?: return@readSseLines true
                chunk.optJSONObject("usage")?.let { usage ->
                    out.emit(GenerationEvent.Usage(parseChatUsage(usage)))
                }
                val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: return@readSseLines true
                choice.optString("finish_reason").takeIf(String::isNotBlank)?.let { finishReason = it }
                val delta = choice.optJSONObject("delta") ?: return@readSseLines true
                // Reasoning arrives on a separate field, per provider: reasoning_content
                // (DeepSeek-style) or reasoning (OpenRouter-style).
                val reasoningDelta = delta.optString("reasoning_content").ifBlank { delta.optString("reasoning") }
                if (reasoningDelta.isNotEmpty()) {
                    reasoning.append(reasoningDelta)
                    out.emit(GenerationEvent.ReasoningDelta(reasoningDelta))
                }
                delta.optString("content").takeIf(String::isNotEmpty)?.let { piece ->
                    text.append(piece)
                    out.emit(GenerationEvent.TextDelta(piece))
                }
                val toolDeltas = delta.optJSONArray("tool_calls")
                for (index in 0 until (toolDeltas?.length() ?: 0)) {
                    val toolDelta = toolDeltas?.optJSONObject(index) ?: continue
                    val slot = toolDelta.optInt("index", index)
                    val builder = calls.getOrPut(slot) { ChatToolCallBuilder() }
                    toolDelta.optString("id").takeIf(String::isNotBlank)?.let { builder.id = it }
                    val function = toolDelta.optJSONObject("function") ?: continue
                    function.optString("name").takeIf(String::isNotBlank)?.let { builder.name = it }
                    function.optString("arguments").takeIf(String::isNotEmpty)?.let { builder.arguments.append(it) }
                }
                true
            }
            calls.values.forEach { builder ->
                if (builder.name.isNotBlank()) {
                    out.emit(
                        GenerationEvent.ToolCallReady(
                            ToolCall(
                                id = builder.id.ifBlank { ToolCall.newId() },
                                name = builder.name,
                                argumentsJson = builder.arguments.toString().ifBlank { "{}" },
                            ),
                        ),
                    )
                }
            }
            out.emit(GenerationEvent.Finished(finishReason))
        } finally {
            activeConnections.remove(request.requestId)
            connection.disconnect()
        }
    }

    /**
     * Responses API SSE: typed events per delta, and a final `response.completed` carrying the whole
     * response object — which the non-streaming parser already understands, so tool calls and usage
     * come from there rather than from re-assembling fragments.
     */
    private suspend fun streamResponses(request: GenerationRequest, out: FlowCollector<GenerationEvent>) {
        val connection = openWithRetry(request)
        activeConnections[request.requestId] = connection
        try {
            if (!connection.isEventStream()) {
                emitWholeResponsesResponse(request, connection, out)
                return
            }
            var streamedText = false
            var completed: JSONObject? = null
            connection.readSseLines { data ->
                val event = runCatching { JSONObject(data) }.getOrNull() ?: return@readSseLines true
                when (event.optString("type")) {
                    "response.output_text.delta" -> {
                        val piece = event.optString("delta")
                        if (piece.isNotEmpty()) {
                            streamedText = true
                            out.emit(GenerationEvent.TextDelta(piece))
                        }
                    }
                    "response.reasoning_summary_text.delta", "response.reasoning_text.delta" -> {
                        val piece = event.optString("delta")
                        if (piece.isNotEmpty()) out.emit(GenerationEvent.ReasoningDelta(piece))
                    }
                    "response.completed", "response.incomplete" -> {
                        completed = event.optJSONObject("response")
                    }
                    "response.failed" -> {
                        val message = event.optJSONObject("response")
                            ?.optJSONObject("error")?.optString("message")
                            ?.takeIf(String::isNotBlank)
                            ?: "The response ended with status failed"
                        throw RemoteEndpointException(200, message)
                    }
                    "error" -> {
                        val message = event.optJSONObject("error")?.optString("message")
                            ?.takeIf(String::isNotBlank) ?: "The server reported an error"
                        throw RemoteEndpointException(200, message)
                    }
                }
                true
            }
            val finalPayload = completed
            if (finalPayload != null) {
                val parsed = request.parseApiResponse(finalPayload)
                // Text already streamed; a server that only sends the completed payload still gets
                // its text out here.
                if (!streamedText && parsed.text.isNotEmpty()) out.emit(GenerationEvent.TextDelta(parsed.text))
                parsed.toolCalls.forEach { out.emit(GenerationEvent.ToolCallReady(it)) }
                parsed.usage?.let { out.emit(GenerationEvent.Usage(it)) }
                out.emit(GenerationEvent.Finished(parsed.finishReason))
            } else {
                out.emit(GenerationEvent.Finished(null))
            }
        } finally {
            activeConnections.remove(request.requestId)
            connection.disconnect()
        }
    }

    private suspend fun emitWholeChatResponse(
        request: GenerationRequest,
        connection: HttpURLConnection,
        out: FlowCollector<GenerationEvent>,
    ) {
        val parsed = request.parseApiResponse(readWholeBody(connection))
        if (parsed.reasoning.isNotEmpty()) out.emit(GenerationEvent.ReasoningDelta(parsed.reasoning))
        if (parsed.text.isNotEmpty()) out.emit(GenerationEvent.TextDelta(parsed.text))
        parsed.toolCalls.forEach { out.emit(GenerationEvent.ToolCallReady(it)) }
        parsed.usage?.let { out.emit(GenerationEvent.Usage(it)) }
        out.emit(GenerationEvent.Finished(parsed.finishReason))
    }

    private suspend fun emitWholeResponsesResponse(
        request: GenerationRequest,
        connection: HttpURLConnection,
        out: FlowCollector<GenerationEvent>,
    ) {
        val parsed = request.parseApiResponse(readWholeBody(connection))
        if (parsed.reasoning.isNotEmpty()) out.emit(GenerationEvent.ReasoningDelta(parsed.reasoning))
        if (parsed.text.isNotEmpty()) out.emit(GenerationEvent.TextDelta(parsed.text))
        parsed.toolCalls.forEach { out.emit(GenerationEvent.ToolCallReady(it)) }
        parsed.usage?.let { out.emit(GenerationEvent.Usage(it)) }
        out.emit(GenerationEvent.Finished(parsed.finishReason))
    }

    private fun readWholeBody(connection: HttpURLConnection): JSONObject {
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        return runCatching { JSONObject(body) }.getOrElse {
            throw RemoteEndpointException(200, "The server answered with something that is not a JSON object")
        }
    }

    private fun HttpURLConnection.isEventStream(): Boolean =
        contentType?.lowercase()?.contains("text/event-stream") == true

    /** Reads `data:` frames, calling [onData] for each; returning false stops reading. */
    private suspend fun HttpURLConnection.readSseLines(onData: suspend (String) -> Boolean) {
        inputStream.bufferedReader().use { reader ->
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) {
                            val dispatch = onData(data.toString())
                            data.clear()
                            if (!dispatch) return
                        }
                    }
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trim())
                    }
                    // `event:` names, comments (`:`), and other fields are not needed by either
                    // wire kind: the payload carries its own type.
                }
            }
            if (data.isNotEmpty()) onData(data.toString())
        }
    }

    /**
     * Opens the request connection, retrying transient failures before anything has streamed.
     *
     * A 429 or a 5xx usually means "later", not "never", and a network hiccup before the first
     * byte costs nothing to retry. Once the body starts streaming there is no retry — tokens
     * already arrived — so those failures surface as they are. Auth, bad-request, and not-found
     * answers are configuration problems: retrying cannot fix them and a silent fallback would
     * hide a broken endpoint, so they carry [EndpointConfigurationException], which the flow
     * reports as unrecoverable.
     */
    private suspend fun openWithRetry(request: GenerationRequest): HttpURLConnection {
        var attempt = 0
        while (true) {
            attempt++
            val connection = openConnection(request)
            try {
                val status = connection.responseCode
                if (status in 200..299) return connection
                // Some servers deliver an error body on the input stream rather than the error
                // stream; read whichever one carries it.
                val errorStream = connection.errorStream ?: runCatching { connection.inputStream }.getOrNull()
                val body = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val message = extractErrorMessage(body, status)
                val retryable = status == 408 || status == 429 || status in 500..599
                if (retryable && attempt <= MAX_ATTEMPTS - 1) {
                    connection.disconnect()
                    delay(RETRY_BACKOFF_MILLIS * attempt)
                    continue
                }
                throw if (retryable) {
                    RemoteEndpointException(status, message)
                } else {
                    EndpointConfigurationException("Endpoint error ($status): $message")
                }
            } catch (io: java.io.IOException) {
                connection.disconnect()
                if (attempt <= MAX_ATTEMPTS - 1) {
                    delay(RETRY_BACKOFF_MILLIS * attempt)
                    continue
                }
                throw RemoteEndpointException(-1, io.message ?: "The endpoint could not be reached", io)
            }
        }
    }

    private fun extractErrorMessage(body: String, status: Int): String =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }
            .getOrNull()
            .takeUnless { it.isNullOrBlank() }
            ?: body.take(4_096).ifBlank { "HTTP $status" }

    private suspend fun openConnection(request: GenerationRequest): HttpURLConnection {
        val apiKey = credentialResolver.resolve(endpoint.id, endpoint.credentialAlias).orEmpty()
        return (URL(targetUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = READ_TIMEOUT_MILLIS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // Both are accepted: the request asks for a stream, but a server that answers a single
            // JSON body is still understood.
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("User-Agent", "Bram-Android/0.1")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            val provider = ProviderProfile.forBaseUrl(endpoint.baseUrl)
            endpoint.customHeaders.forEach { (name, value) ->
                if (!name.equals("authorization", ignoreCase = true)) {
                    setRequestProperty(name, value.replace("{session_id}", request.sessionId.orEmpty()))
                }
            }
            provider?.sessionHeader?.let { header ->
                request.sessionId?.let { setRequestProperty(header, it) }
            }
            try {
                val payload = request.toApiJson().toString().toByteArray(Charsets.UTF_8)
                setFixedLengthStreamingMode(payload.size)
                outputStream.use { it.write(payload) }
            } catch (io: java.io.IOException) {
                disconnect()
                throw io
            }
        }
    }

    private class ChatToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    private companion object {
        /**
         * How long a streamed body may go quiet before the read fails. Not zero: a streamed turn
         * can legitimately pause between chunks, but a hung connection must not pin the run.
         */
        const val READ_TIMEOUT_MILLIS = 120_000

        /** Connection attempts per request, including the first; retries happen before any token. */
        const val MAX_ATTEMPTS = 3

        const val RETRY_BACKOFF_MILLIS = 750L
    }

    private fun parseChatUsage(usage: JSONObject): TokenUsage = TokenUsage(
        inputTokens = usage.optInt("prompt_tokens").takeIf { usage.has("prompt_tokens") },
        outputTokens = usage.optInt("completion_tokens").takeIf { usage.has("completion_tokens") },
    )

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

    private fun GenerationRequest.toChatJson(): JSONObject = bodyOptions()
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
        .put("stream", true)
        .also { root ->
            endpoint.reasoningEffort?.let { root.put("reasoning_effort", it) }
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
        val root = bodyOptions()
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
            .put("stream", true)
        endpoint.reasoningEffort?.let { effort ->
            root.put("reasoning", JSONObject().put("effort", effort))
        }
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
            if (content.isNotBlank()) add(
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

    private fun bodyOptions(): JSONObject = runCatching { JSONObject(endpoint.bodyOptionsJson) }
        .getOrElse { JSONObject() }

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
            reasoning = message.optString("reasoning_content").ifBlank { message.optString("reasoning") },
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

        // Reasoning items carry their prose under `summary` or `content`, depending on how much of
        // the chain of thought the provider exposes.
        val reasoning = buildString {
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                if (item.optString("type") != "reasoning") continue
                appendParts(item.opt("summary"))
                appendParts(item.opt("content"))
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
            reasoning = reasoning,
            toolCalls = calls,
            usage = usage,
            finishReason = status,
        )
    }

    /** Appends the text parts of a Responses content field, which may be a string or part array. */
    private fun StringBuilder.appendParts(content: Any?) {
        when (content) {
            is String -> append(content)
            is JSONArray -> {
                for (index in 0 until content.length()) {
                    val part = content.optJSONObject(index) ?: continue
                    append(part.optString("text"))
                }
            }
            else -> Unit
        }
    }

    private fun JSONObject.optionalInt(name: String): Int? =
        if (has(name) && !isNull(name)) getInt(name) else null
}

private data class ParsedResponse(
    val text: String,
    val reasoning: String = "",
    val toolCalls: List<ToolCall>,
    val usage: TokenUsage?,
    val finishReason: String?,
)

private class EndpointConfigurationException(message: String) : IllegalArgumentException(message)

private class RemoteEndpointException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("Endpoint error ($statusCode): $message", cause)
