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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
    private var responseStatus = 200
    private var responseBody = "{}"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            capturedPath = exchange.requestURI.path
            capturedAuth = exchange.requestHeaders.getFirst("Authorization")
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            capturedBody = runCatching { JSONObject(body) }.getOrNull()
            respond(exchange, responseStatus, responseBody)
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
        assertEquals(4, capturedBody!!.getJSONArray("input").length())
        assertEquals("message", capturedBody!!.getJSONArray("input").getJSONObject(0).getString("type"))
        assertEquals("user", capturedBody!!.getJSONArray("input").getJSONObject(0).getString("role"))
        assertEquals(
            "function_call",
            capturedBody!!.getJSONArray("input").getJSONObject(2).getString("type"),
        )
        assertEquals(
            "function_call_output",
            capturedBody!!.getJSONArray("input").getJSONObject(3).getString("type"),
        )
        assertEquals("call-1", capturedBody!!.getJSONArray("input").getJSONObject(3).getString("call_id"))
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

    private suspend fun generate(
        apiKind: RemoteApiKind,
        messages: List<ConversationMessage> = listOf(
            ConversationMessage(role = MessageRole.USER, content = "hi"),
        ),
        tools: List<ToolDefinition> = emptyList(),
    ) = runtime(apiKind).generate(
        GenerationRequest(
            messages = messages,
            tools = tools,
            maxOutputTokens = 8,
            requestId = "req-1",
        ),
    ).toList()

    private fun runtime(apiKind: RemoteApiKind) = OpenAiCompatibleRuntime(
        endpoint = RemoteEndpoint(
            id = "e1",
            displayName = "Provider",
            baseUrl = baseUrl,
            modelName = "chat-model",
            apiKind = apiKind,
            allowInsecureHttp = true,
        ),
        credentialResolver = EndpointCredentialResolver { _, _ -> "test-key" },
    )

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
