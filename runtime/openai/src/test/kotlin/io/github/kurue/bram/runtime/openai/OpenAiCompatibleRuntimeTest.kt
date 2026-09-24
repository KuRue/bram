package io.github.kurue.bram.runtime.openai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.EndpointCredentialResolver
import io.github.kurue.bram.core.domain.GenerationEvent
import io.github.kurue.bram.core.domain.GenerationRequest
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.RemoteApiKind
import io.github.kurue.bram.core.domain.RemoteEndpoint
import io.github.kurue.bram.core.domain.ToolCall
import io.github.kurue.bram.core.domain.ToolDefinition
import java.net.InetSocketAddress
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract fixtures for OpenAI-compatible servers, per the testing matrix: the runtime must
 * tolerate missing and extra fields, and must send the wire shape each API kind expects.
 */
class OpenAiCompatibleRuntimeTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private var capturedPath: String? = null
    private var capturedBody: JSONObject? = null
    private var capturedAuth: String? = null
    private var capturedHeaders: Map<String, String> = emptyMap()
    private var responseStatus = 200
    private var responseBody = "{}"

    /** When set, the server answers `text/event-stream` with these frames instead of a JSON body. */
    private var sseFrames: List<String>? = null

    /** When set, the server answers this many requests with the configured failure before succeeding (retry tests). */
    private var failuresBeforeSuccess = 0
    private var requestCount = 0

    /**
     * When set, the server announces an event stream and then says nothing, holding the response
     * open. This is the silent peer a stalled turn looks like from the app's side.
     */
    private var silentSse = false

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            requestCount++
            capturedPath = exchange.requestURI.path
            capturedAuth = exchange.requestHeaders.getFirst("Authorization")
            capturedHeaders = exchange.requestHeaders.entries
                .associate { it.key to it.value.firstOrNull().orEmpty() }
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            capturedBody = runCatching { JSONObject(body) }.getOrNull()
            val frames = sseFrames
            when {
                silentSse -> staySilent(exchange)
                failuresBeforeSuccess > 0 -> {
                    failuresBeforeSuccess--
                    respond(exchange, responseStatus, responseBody)
                }
                frames != null -> respondSse(exchange, frames)
                else -> respond(exchange, responseStatus, responseBody)
            }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}/v1"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `chat completions keeps its wire shape and parses text, tool calls, and usage`() = runBlocking {
        responseBody = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put(
                            "message",
                            JSONObject()
                                .put("content", "Hello from chat")
                                .put(
                                    "tool_calls",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("id", "call-1")
                                            .put(
                                                "function",
                                                JSONObject().put("name", "lookup").put("arguments", "{\"q\":\"x\"}"),
                                            ),
                                    ),
                                ),
                        )
                        .put("finish_reason", "tool_calls"),
                ),
            )
            .put("usage", JSONObject().put("prompt_tokens", 12).put("completion_tokens", 7))
            .toString()

        val events = generate(apiKind = RemoteApiKind.CHAT_COMPLETIONS).toList()

        assertEquals("/v1/chat/completions", capturedPath)
        assertEquals("chat-model", capturedBody!!.getString("model"))
        assertEquals(8, capturedBody!!.getInt("max_tokens"))
        assertTrue(capturedBody!!.has("messages"))
        assertTrue(events.any { it is GenerationEvent.TextDelta && it.text == "Hello from chat" })
        assertTrue(events.any { it is GenerationEvent.ToolCallReady && it.call.name == "lookup" })
        assertTrue(events.any { it is GenerationEvent.Usage && it.usage.inputTokens == 12 })
        assertTrue(events.any { it is GenerationEvent.Finished })
    }

    @Test
    fun `responses puts system into instructions and the conversation into input items`() = runBlocking {
        val messages = listOf(
            ConversationMessage(role = MessageRole.SYSTEM, content = "You are Bram."),
            ConversationMessage(role = MessageRole.USER, content = "What is 2+2?"),
            ConversationMessage(
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall(id = "call-1", name = "lookup", argumentsJson = "{\"q\":\"x\"}")),
            ),
            ConversationMessage(role = MessageRole.TOOL, content = "answer: 4", toolCallId = "call-1"),
        )
        val tools = listOf(
            ToolDefinition(
                name = "lookup",
                description = "Looks things up.",
                inputSchemaJson = """{"type":"object","properties":{"q":{"type":"string"}}}""",
            ),
        )

        generate(apiKind = RemoteApiKind.RESPONSES, messages = messages, tools = tools).toList()

        assertEquals("/v1/responses", capturedPath)
        assertEquals("chat-model", capturedBody!!.getString("model"))
        assertEquals("You are Bram.", capturedBody!!.getString("instructions"))
        assertEquals(3, capturedBody!!.getJSONArray("input").length())
        assertEquals("message", capturedBody!!.getJSONArray("input").getJSONObject(0).getString("type"))
        assertEquals("user", capturedBody!!.getJSONArray("input").getJSONObject(0).getString("role"))
        assertEquals(
            "function_call",
            capturedBody!!.getJSONArray("input").getJSONObject(1).getString("type"),
        )
        assertEquals(
            "function_call_output",
            capturedBody!!.getJSONArray("input").getJSONObject(2).getString("type"),
        )
        assertEquals("call-1", capturedBody!!.getJSONArray("input").getJSONObject(2).getString("call_id"))
        assertEquals(1, capturedBody!!.getJSONArray("tools").length())
        assertEquals("lookup", capturedBody!!.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertEquals("auto", capturedBody!!.getString("tool_choice"))
        assertEquals("Bearer test-key", capturedAuth)
    }

    @Test
    fun `responses parses output text parts, function calls, and usage`() = runBlocking {
        responseBody = JSONObject()
            .put(
                "output",
                JSONArray()
                    .put(
                        JSONObject()
                            .put(
                                "content",
                                JSONArray()
                                    .put(JSONObject().put("type", "output_text").put("text", "The "))
                                    .put(JSONObject().put("type", "output_text").put("text", "answer is 4.")),
                            )
                            .put("role", "assistant")
                            .put("type", "message"),
                    )
                    .put(
                        JSONObject()
                            .put("type", "function_call")
                            .put("call_id", "call-9")
                            .put("name", "lookup")
                            .put("arguments", "{\"q\":\"y\"}"),
                    ),
            )
            .put("status", "completed")
            .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 5))
            .toString()

        val events = generate(apiKind = RemoteApiKind.RESPONSES).toList()

        val text = events.filterIsInstance<GenerationEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("The answer is 4.", text)
        val call = events.filterIsInstance<GenerationEvent.ToolCallReady>().single().call
        assertEquals("call-9", call.id)
        assertEquals("lookup", call.name)
        assertEquals("{\"q\":\"y\"}", call.argumentsJson)
        val usage = events.filterIsInstance<GenerationEvent.Usage>().single().usage
        assertEquals(10, usage.inputTokens)
        assertEquals(5, usage.outputTokens)
        assertTrue(events.any { it is GenerationEvent.Finished })
    }

    @Test
    fun `responses tolerates missing and extra fields`() = runBlocking {
        responseBody = JSONObject()
            .put(
                "output",
                JSONArray()
                    .put(JSONObject().put("type", "reasoning").put("summary", "hidden thinking"))
                    .put(
                        JSONObject()
                            .put("type", "message")
                            .put("role", "assistant")
                            .put("content", "plain string content"),
                    ),
            )
            .put("status", "incomplete")
            .toString()

        val events = generate(apiKind = RemoteApiKind.RESPONSES).toList()

        val text = events.filterIsInstance<GenerationEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("plain string content", text)
        assertTrue(events.none { it is GenerationEvent.ToolCallReady })
        assertTrue(events.none { it is GenerationEvent.Usage })
        val finished = events.filterIsInstance<GenerationEvent.Finished>().single()
        assertEquals("incomplete", finished.finishReason)
    }

    @Test
    fun `responses failed status surfaces the server error message`() = runBlocking {
        responseBody = JSONObject()
            .put("status", "failed")
            .put("error", JSONObject().put("message", "context window exceeded"))
            .toString()

        val events = generate(apiKind = RemoteApiKind.RESPONSES).toList()

        val failed = events.filterIsInstance<GenerationEvent.Failed>().single()
        assertEquals("Endpoint error (200): context window exceeded", failed.message)
        assertTrue(failed.recoverable)
    }

    @Test
    fun `an http error surfaces the server message on both kinds`() = runBlocking {
        responseStatus = 429
        responseBody = JSONObject().put("error", JSONObject().put("message", "rate limited")).toString()

        val events = generate(apiKind = RemoteApiKind.RESPONSES).toList()

        val failed = events.filterIsInstance<GenerationEvent.Failed>().single()
        assertEquals("Endpoint error (429): rate limited", failed.message)
    }

    @Test
    fun `availability accepts both api kinds`() = runBlocking {
        val chat = runtime(RemoteApiKind.CHAT_COMPLETIONS).availability()
        val responses = runtime(RemoteApiKind.RESPONSES).availability()

        assertTrue(chat.available)
        assertTrue(chat.summary.contains("Chat Completions"))
        assertTrue(responses.available)
        assertTrue(responses.summary.contains("Responses"))
    }

    @Test
    fun `custom headers are sent, session id substituted, and authorization protected`() = runBlocking {
        responseBody = chatResponse("ok")

        generate(
            apiKind = RemoteApiKind.CHAT_COMPLETIONS,
            sessionId = "conv-42",
            customHeaders = mapOf(
                "X-Session" to "{session_id}",
                "X-Static" to "on",
                "Authorization" to "Bearer hijack",
            ),
        )

        assertEquals("conv-42", header("X-Session"))
        assertEquals("on", header("X-Static"))
        assertEquals("Bearer test-key", capturedAuth)
        assertNull(header("x-opencode-session"))
    }

    @Test
    fun `opencode zen base urls gain the session header`() = runBlocking {
        responseBody = chatResponse("ok")

        generate(
            apiKind = RemoteApiKind.CHAT_COMPLETIONS,
            sessionId = "conv-7",
            url = "$baseUrl/opencode.ai/zen/go",
        )

        assertEquals("conv-7", header("x-opencode-session"))
    }

    @Test
    fun `body options merge with the reserved fields winning`() = runBlocking {
        responseBody = chatResponse("ok")

        generate(
            apiKind = RemoteApiKind.CHAT_COMPLETIONS,
            bodyOptionsJson = """{"top_k":40,"model":"hijack","stream":false}""",
        )

        assertEquals(40, capturedBody!!.getInt("top_k"))
        assertEquals("chat-model", capturedBody!!.getString("model"))
        assertEquals("the runtime asks for a stream whatever the options say", true, capturedBody!!.getBoolean("stream"))
    }

    @Test
    fun `malformed body options are ignored rather than failing the request`() = runBlocking {
        responseBody = chatResponse("ok")

        val events = generate(apiKind = RemoteApiKind.CHAT_COMPLETIONS, bodyOptionsJson = "not json")

        assertTrue(events.any { it is GenerationEvent.Finished })
        assertEquals("chat-model", capturedBody!!.getString("model"))
    }

    @Test
    fun `reasoning effort is spelled per api kind`() = runBlocking {
        responseBody = chatResponse("ok")
        generate(apiKind = RemoteApiKind.CHAT_COMPLETIONS, reasoningEffort = "high")
        assertEquals("high", capturedBody!!.getString("reasoning_effort"))
        assertTrue(!capturedBody!!.has("reasoning"))

        responseBody = responsesText("ok")
        generate(apiKind = RemoteApiKind.RESPONSES, reasoningEffort = "high")
        assertEquals("high", capturedBody!!.getJSONObject("reasoning").getString("effort"))
    }

    @Test
    fun `chat streaming emits reasoning, text, and a fragmented tool call as they arrive`() = runBlocking {
        sseFrames = listOf(
            chatChunk(JSONObject().put("role", "assistant").put("reasoning_content", "Let me ")),
            chatChunk(JSONObject().put("reasoning", "think.")),
            chatChunk(JSONObject().put("content", "Hel")),
            chatChunk(JSONObject().put("content", "lo")),
            chatChunk(
                JSONObject().put(
                    "tool_calls",
                    JSONArray().put(
                        JSONObject()
                            .put("index", 0)
                            .put("id", "call_1")
                            .put("function", JSONObject().put("name", "look").put("arguments", "{\"q\":")),
                    ),
                ),
            ),
            chatChunk(
                JSONObject().put(
                    "tool_calls",
                    JSONArray().put(
                        JSONObject().put("index", 0).put("function", JSONObject().put("arguments", "\"x\"}")),
                    ),
                ),
                finishReason = "tool_calls",
            ),
            "data: [DONE]",
        )

        val events = generate(apiKind = RemoteApiKind.CHAT_COMPLETIONS).toList()

        assertTrue("the request must ask for a stream", capturedBody!!.getBoolean("stream"))
        assertTrue(header("Accept")!!.contains("text/event-stream"))
        assertEquals(
            "Let me think.",
            events.filterIsInstance<GenerationEvent.ReasoningDelta>().joinToString("") { it.text },
        )
        assertEquals("Hello", events.filterIsInstance<GenerationEvent.TextDelta>().joinToString("") { it.text })
        val call = events.filterIsInstance<GenerationEvent.ToolCallReady>().single().call
        assertEquals("call_1", call.id)
        assertEquals("look", call.name)
        assertEquals("""{"q":"x"}""", call.argumentsJson)
        assertEquals("tool_calls", events.filterIsInstance<GenerationEvent.Finished>().single().finishReason)
    }

    @Test
    fun `responses streaming emits text and reasoning and takes tool calls from the completed payload`() =
        runBlocking {
            val completed = JSONObject()
                .put("status", "completed")
                .put(
                    "output",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "message")
                                .put("role", "assistant")
                                .put(
                                    "content",
                                    JSONArray().put(JSONObject().put("type", "output_text").put("text", "Hi there")),
                                ),
                        )
                        .put(
                            JSONObject()
                                .put("type", "function_call")
                                .put("call_id", "call_9")
                                .put("name", "lookup")
                                .put("arguments", """{"q":"y"}"""),
                        ),
                )
                .put("usage", JSONObject().put("input_tokens", 3).put("output_tokens", 4))
            sseFrames = listOf(
                responsesEvent("response.output_text.delta", JSONObject().put("delta", "Hi ")),
                responsesEvent("response.reasoning_summary_text.delta", JSONObject().put("delta", "Because")),
                responsesEvent("response.completed", JSONObject().put("response", completed)),
            )

            val events = generate(apiKind = RemoteApiKind.RESPONSES).toList()

            // The delta arrived and the completed payload must not double the text.
            assertEquals("Hi ", events.filterIsInstance<GenerationEvent.TextDelta>().joinToString("") { it.text })
            assertEquals(
                "Because",
                events.filterIsInstance<GenerationEvent.ReasoningDelta>().joinToString("") { it.text },
            )
            assertEquals("call_9", events.filterIsInstance<GenerationEvent.ToolCallReady>().single().call.id)
            assertEquals(3, events.filterIsInstance<GenerationEvent.Usage>().single().usage.inputTokens)
            assertEquals("completed", events.filterIsInstance<GenerationEvent.Finished>().single().finishReason)
        }

    @Test
    fun `a transient failure is retried before any tokens arrive`() = runBlocking {
        failuresBeforeSuccess = 1
        responseStatus = 503
        responseBody = JSONObject().put("error", JSONObject().put("message", "busy")).toString()
        sseFrames = listOf(chatChunk(JSONObject().put("content", "after retry")), "data: [DONE]")

        val events = generate(apiKind = RemoteApiKind.CHAT_COMPLETIONS).toList()

        assertEquals(2, requestCount)
        assertEquals("after retry", events.filterIsInstance<GenerationEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.none { it is GenerationEvent.Failed })
    }

    @Test
    fun `an auth failure is not retried and is unrecoverable`() = runBlocking {
        // 403 rather than 401: the JDK's HttpURLConnection special-cases 401 (it drops the error
        // body when no Authenticator is set), and the taxonomy under test is "any other 4xx".
        responseStatus = 403
        responseBody = JSONObject().put("error", JSONObject().put("message", "bad key")).toString()

        val events = generate(apiKind = RemoteApiKind.CHAT_COMPLETIONS).toList()

        assertEquals("a configuration failure must not be retried", 1, requestCount)
        val failed = events.filterIsInstance<GenerationEvent.Failed>().single()
        assertEquals(false, failed.recoverable)
        assertTrue("message was: ${failed.message}", failed.message.contains("bad key"))
    }

    @Test
    fun `a non-streaming server is still accepted`() = runBlocking {
        responseBody = chatResponse("whole body")

        val events = generate(apiKind = RemoteApiKind.CHAT_COMPLETIONS).toList()

        assertEquals("whole body", events.filterIsInstance<GenerationEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.any { it is GenerationEvent.Finished })
    }

    @Test
    fun `chat non-streaming parse keeps reasoning fields`() = runBlocking {
        responseBody = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", "answer").put("reasoning_content", "why"),
                    ),
                ),
            )
            .toString()

        val events = generate(apiKind = RemoteApiKind.CHAT_COMPLETIONS).toList()

        assertEquals(
            "why",
            events.filterIsInstance<GenerationEvent.ReasoningDelta>().joinToString("") { it.text },
        )
    }

    @Test
    fun `json null fields never surface as the word null`() = runBlocking {
        // llama-server spells an empty field as null (`"content": null` on a reasoning-only chunk),
        // and optString renders that as the literal text "null" — which a real run then showed in
        // the transcript.
        sseFrames = listOf(
            chatChunk(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONObject.NULL)
                    .put("reasoning_content", "thinking"),
            ),
            chatChunk(JSONObject().put("content", "answer")),
            "data: [DONE]",
        )

        val events = generate(apiKind = RemoteApiKind.CHAT_COMPLETIONS).toList()

        assertEquals(
            "thinking",
            events.filterIsInstance<GenerationEvent.ReasoningDelta>().joinToString("") { it.text },
        )
        assertEquals("answer", events.filterIsInstance<GenerationEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun `a null finish reason and null tool fields are absent, not the word null`() = runBlocking {
        responseBody = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put(
                            "message",
                            JSONObject()
                                .put("content", JSONObject.NULL)
                                .put("reasoning_content", JSONObject.NULL)
                                .put(
                                    "tool_calls",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("id", JSONObject.NULL)
                                            .put(
                                                "function",
                                                JSONObject()
                                                    .put("name", "look")
                                                    .put("arguments", JSONObject.NULL),
                                            ),
                                    ),
                                ),
                        )
                        .put("finish_reason", JSONObject.NULL),
                ),
            )
            .toString()

        val events = generate(apiKind = RemoteApiKind.CHAT_COMPLETIONS).toList()

        assertTrue(events.filterIsInstance<GenerationEvent.TextDelta>().isEmpty())
        val call = events.filterIsInstance<GenerationEvent.ToolCallReady>().single().call
        assertEquals("look", call.name)
        assertEquals("{}", call.argumentsJson)
        assertTrue("a null id must not become the text 'null'", call.id != "null")
        assertNull(events.filterIsInstance<GenerationEvent.Finished>().single().finishReason)
    }

    private suspend fun generate(
        apiKind: RemoteApiKind,
        messages: List<ConversationMessage> = listOf(
            ConversationMessage(role = MessageRole.USER, content = "hi"),
        ),
        tools: List<ToolDefinition> = emptyList(),
        sessionId: String? = null,
        customHeaders: Map<String, String> = emptyMap(),
        bodyOptionsJson: String = "{}",
        reasoningEffort: String? = null,
        url: String = baseUrl,
    ) = runtime(apiKind, url, customHeaders, bodyOptionsJson, reasoningEffort).generate(
        GenerationRequest(
            messages = messages,
            tools = tools,
            maxOutputTokens = 8,
            requestId = "req-1",
            sessionId = sessionId,
        ),
    ).toList()

    @Test
    fun `cancelling a silent stream ends the turn instead of waiting out the read timeout`() = runBlocking {
        // Seen on the phone: a turn was stopped while the provider had gone quiet, and the app sat
        // on "Stopping…" long past the tap. HttpURLConnection parks a thread in a socket read that
        // cancelling a coroutine cannot interrupt, so the turn ended only when the peer answered or
        // READ_TIMEOUT_MILLIS (120s) expired — `disconnect()` left the read parked and it returned
        // only on its own 20s read timeout. This is the gate for the OkHttp migration: a stop must
        // close the connection instead of waiting that out.
        silentSse = true
        val runtime = runtime(RemoteApiKind.CHAT_COMPLETIONS)
        val turn = launch(Dispatchers.Default) {
            runtime.generate(
                GenerationRequest(
                    messages = listOf(ConversationMessage(role = MessageRole.USER, content = "hi")),
                    maxOutputTokens = 8,
                    requestId = "req-silent",
                ),
            ).collect()
        }
        // Let the request reach the server so the client is genuinely blocked mid-read, not still
        // connecting: otherwise this would pass without ever exercising the blocked read.
        withTimeout(REQUEST_TIMEOUT_MILLIS) {
            while (requestCount == 0) delay(20)
        }
        delay(250)

        val elapsed = measureTimeMillis {
            withTimeout(CANCEL_BUDGET_MILLIS) { turn.cancelAndJoin() }
        }
        assertTrue(
            "cancellation took ${elapsed}ms; it must not wait out the 120s read timeout",
            elapsed < CANCEL_BUDGET_MILLIS,
        )
    }

    private fun runtime(
        apiKind: RemoteApiKind,
        url: String = baseUrl,
        customHeaders: Map<String, String> = emptyMap(),
        bodyOptionsJson: String = "{}",
        reasoningEffort: String? = null,
    ) = OpenAiCompatibleRuntime(
        endpoint = RemoteEndpoint(
            id = "e1",
            displayName = "Provider",
            baseUrl = url,
            modelName = "chat-model",
            apiKind = apiKind,
            allowInsecureHttp = true,
            customHeaders = customHeaders,
            bodyOptionsJson = bodyOptionsJson,
            reasoningEffort = reasoningEffort,
        ),
        credentialResolver = EndpointCredentialResolver { _, _ -> "test-key" },
    )

    private fun chatResponse(text: String): String = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", text))))
        .toString()

    private fun responsesText(text: String): String = JSONObject()
        .put(
            "output",
            JSONArray().put(
                JSONObject()
                    .put("type", "message")
                    .put("role", "assistant")
                    .put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "output_text").put("text", text)),
                    ),
            ),
        )
        .put("status", "completed")
        .toString()

    private fun header(name: String): String? =
        capturedHeaders.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respondSse(exchange: HttpExchange, frames: List<String>) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0) // chunked, so frames arrive as written
        exchange.responseBody.use { out ->
            frames.forEach { frame ->
                out.write((frame + "\n\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
    }

    /**
     * Announces an event stream and then holds the response open without writing a frame, so the
     * client blocks mid-read exactly as it does against a peer that has gone quiet mid-answer.
     */
    private fun staySilent(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        // No body writes, and the exchange is left open on purpose: closing it would let the read
        // return immediately, which is the opposite of what this fixture is for.
    }

    private fun chatChunk(delta: JSONObject, finishReason: String? = null): String = "data: " + JSONObject()
        .put(
            "choices",
            JSONArray().put(
                JSONObject().put("delta", delta).apply {
                    finishReason?.let { put("finish_reason", it) }
                },
            ),
        )

    private fun responsesEvent(type: String, payload: JSONObject): String =
        "event: $type\ndata: " + JSONObject().put("type", type).apply {
            payload.keys().forEach { key -> put(key, payload.get(key)) }
        }

    private companion object {
        /** Generous: it only has to outlast a local connect, not assert anything about latency. */
        const val REQUEST_TIMEOUT_MILLIS = 10_000L

        /** Far below the runtime's 120s read timeout, and far above a local socket close. */
        const val CANCEL_BUDGET_MILLIS = 5_000L
    }
}
