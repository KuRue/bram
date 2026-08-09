package io.github.kurue.bram.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kurue.bram.core.domain.McpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class McpClientTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val handlers = mutableMapOf<String, (HttpExchange) -> Unit>()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
        handlers.clear()
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            val method = runCatching { JSONObject(body).optString("method") }.getOrDefault("")
            handlers[method]?.invoke(exchange) ?: respond(
                exchange,
                """{"jsonrpc":"2.0","error":{"code":-32601,"message":"not handled: $method"}}""",
            )
        }
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    /** The client POSTs every JSON-RPC message to the one configured URL, so the stub routes on the `method` field of the request body. */
    private fun route(extra: Map<String, (HttpExchange) -> Unit>) {
        handlers.putAll(extra)
    }

    private fun respond(
        exchange: HttpExchange,
        body: String,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
    ) {
        headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
        exchange.responseHeaders.add("Content-Type", contentType)
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun client(token: String? = null) = McpClient(
        McpServer(
            id = "srv1",
            displayName = "test server",
            baseUrl = baseUrl,
            allowInsecureHttp = true,
        ),
        token,
    )

    /** A server that completes the handshake and answers tools/list with one real tool. */
    private fun handshakeServer() {
        route(
            mapOf(
                "initialize" to { exchange ->
                    respond(
                        exchange,
                        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"t"}}}""",
                        headers = mapOf("Mcp-Session-Id" to "sess-123"),
                    )
                },
                "notifications/initialized" to { exchange -> respond(exchange, """{"jsonrpc":"2.0"}""") },
            ),
        )
    }

    @Test
    fun `handshake lists tools and carries the session id to the next request`() {
        var toolsHeaders: List<Pair<String, String>>? = null
        handshakeServer()
        route(
            mapOf(
                "initialize" to { exchange ->
                    respond(
                        exchange,
                        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"t"}}}""",
                        headers = mapOf("Mcp-Session-Id" to "sess-123"),
                    )
                },
                "notifications/initialized" to { exchange -> respond(exchange, """{"jsonrpc":"2.0"}""") },
                "tools/list" to { exchange ->
                    toolsHeaders = exchange.requestHeaders.entries
                        .flatMap { entry -> entry.value.map { entry.key to it } }
                    respond(
                        exchange,
                        """{"jsonrpc":"2.0","id":3,"result":{"tools":[
                            {"name":"get_weather","description":"Current weather","inputSchema":{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}},
                            {"name":"a/b","description":"hostile"},
                            {"name":"","description":"blank"},
                            {"name":"ok.dot-dash_1","description":"fine"}
                        ]}}""",
                    )
                },
            ),
        )

        val tools = runBlocking {
            val c = client()
            c.connect()
            c.listTools()
        }

        assertEquals(listOf("get_weather", "ok.dot-dash_1"), tools.map { it.name })
        assertTrue(tools.first().inputSchemaJson.contains("\"city\""))
        // The session id from initialize reaches the next request, and every request names the
        // version. HttpServer lowercases request header names, so look up case-insensitively.
        val headers = toolsHeaders!!.toMap()
        val session = headers.entries.firstOrNull { it.key.equals("mcp-session-id", ignoreCase = true) }?.value
        val version = headers.entries.firstOrNull { it.key.equals("mcp-protocol-version", ignoreCase = true) }?.value
        assertEquals("sess-123", session)
        assertEquals("2025-03-26", version)
    }

    @Test
    fun `a tool call sends the bearer token and returns the text content`() {
        handshakeServer()
        var auth: String? = null
        route(
            mapOf(
                "tools/call" to { exchange ->
                    auth = exchange.requestHeaders.getFirst("Authorization")
                    respond(
                        exchange,
                        """{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"sunny in Paris"}]}}""",
                    )
                },
            ),
        )

        val result = runBlocking {
            val c = client("tok-abc")
            c.connect()
            c.callTool("get_weather", """{"city":"Paris"}""")
        }

        assertEquals("sunny in Paris", result)
        assertEquals("Bearer tok-abc", auth)
    }

    @Test
    fun `a tool call accepts an SSE response`() {
        handshakeServer()
        route(
            mapOf(
                "tools/call" to { exchange ->
                    respond(
                        exchange,
                        "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"notifications/progress\",\"params\":{}}\n\n" +
                            "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"from sse\"}]}}\n\n",
                        contentType = "text/event-stream",
                    )
                },
            ),
        )

        val result = runBlocking {
            val c = client()
            c.connect()
            c.callTool("get_weather", "{}")
        }

        assertEquals("from sse", result)
    }

    @Test
    fun `a server-side error is marked rather than mistaken for a success`() {
        handshakeServer()
        route(
            mapOf(
                "tools/call" to { exchange ->
                    respond(
                        exchange,
                        """{"jsonrpc":"2.0","id":2,"result":{"isError":true,"content":[{"type":"text","text":"no such city"}]}}""",
                    )
                },
            ),
        )

        val result = runBlocking {
            val c = client()
            c.connect()
            c.callTool("get_weather", "{}")
        }

        assertTrue(result.startsWith("SERVER ERROR:"))
    }

    @Test
    fun `non-text content is reported by type rather than dumped`() {
        handshakeServer()
        route(
            mapOf(
                "tools/call" to { exchange ->
                    respond(
                        exchange,
                        """{"jsonrpc":"2.0","id":2,"result":{"content":[
                            {"type":"image","mimeType":"image/png","data":"AAAA"},
                            {"type":"text","text":"and the text"}
                        ]}}""",
                    )
                },
            ),
        )

        val result = runBlocking {
            val c = client()
            c.connect()
            c.callTool("get_weather", "{}")
        }

        assertTrue(result.contains("[image content, image/png omitted; it is not text]"))
        assertTrue(result.contains("and the text"))
    }

    @Test
    fun `a jsonrpc error becomes an mcp error with its code`() {
        handshakeServer()
        route(
            mapOf(
                "tools/call" to { exchange ->
                    respond(
                        exchange,
                        """{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"Invalid params"}}""",
                    )
                },
            ),
        )

        try {
            runBlocking {
                val c = client()
                c.connect()
                c.callTool("get_weather", "{}")
            }
            fail("expected an McpException")
        } catch (expected: McpException) {
            assertEquals("jsonrpc_-32602", expected.errorCode)
        }
    }

    @Test
    fun `an http failure keeps its status code`() {
        handshakeServer()
        route(mapOf("tools/list" to { exchange -> exchange.sendResponseHeaders(500, -1) }))

        try {
            runBlocking {
                val c = client()
                c.connect()
                c.listTools()
            }
            fail("expected an McpException")
        } catch (expected: McpException) {
            assertEquals("http_500", expected.errorCode)
        }
    }

    @Test
    fun `a redirect to another host is refused`() {
        route(
            mapOf(
                "initialize" to { exchange ->
                    exchange.responseHeaders.add("Location", "http://evil.example.com/mcp")
                    exchange.sendResponseHeaders(302, -1)
                },
            ),
        )

        try {
            runBlocking { client().connect() }
            fail("expected an McpException")
        } catch (expected: McpException) {
            assertEquals("redirect", expected.errorCode)
            assertTrue(expected.message!!.contains("another host"))
        }
    }

    @Test
    fun `bad arguments are rejected before anything leaves the device`() {
        handshakeServer()
        var calls = 0
        route(
            mapOf(
                "tools/call" to { exchange ->
                    calls++
                    respond(exchange, """{"jsonrpc":"2.0","id":2,"result":{"content":[]}}""")
                },
            ),
        )

        try {
            runBlocking {
                val c = client()
                c.connect()
                c.callTool("get_weather", "not json")
            }
            fail("expected an McpException")
        } catch (expected: McpException) {
            assertEquals("bad_arguments", expected.errorCode)
        }
        assertEquals(0, calls)
    }
}
