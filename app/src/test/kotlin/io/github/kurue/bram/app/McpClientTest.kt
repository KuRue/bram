package io.github.kurue.bram.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kurue.bram.core.domain.McpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streamable-HTTP client against a local stand-in server: pagination across `tools/list`
 * pages, SSE frames matched by request id rather than arrival order, session reuse in
 * [McpSessions], and function names kept inside the provider limit.
 */
class McpClientTest {

    private val servers = mutableListOf<FakeMcpServer>()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        servers.clear()
    }

    private fun fakeServer(configure: FakeMcpServer.() -> Unit = {}): FakeMcpServer =
        FakeMcpServer().apply(configure).also { servers += it }

    private fun serverConfig(fake: FakeMcpServer) = McpServer(
        id = "test-server",
        displayName = "Test Server",
        baseUrl = fake.baseUrl,
        allowInsecureHttp = true,
    )

    @Test
    fun `tools_list follows every page past a hundred tools`() = runBlocking {
        val fake = fakeServer { pageTools = 130 }
        val client = McpClient(serverConfig(fake), token = null)
        client.connect()
        val tools = client.listTools()

        assertEquals("every page must be read", 130, tools.size)
        assertEquals("names must stay unique", 130, tools.map { it.name }.toSet().size)
        assertTrue("the read-only hint is parsed", tools.first { it.name == "read_only_tool" }.readOnly)
        assertTrue("unknown hints leave the flag false", !tools.first { it.name == "tool_0" }.readOnly)
        assertTrue("pagination actually happened", fake.listRequests.get() > 1)
    }

    @Test
    fun `an SSE answer is matched by request id, not arrival order`() = runBlocking {
        // The matching frame arrives first and a server-initiated notification last: reading the
        // last frame would return the wrong message.
        val fake = fakeServer {
            pageTools = 2
            sse = true
            foreignFrameLast = true
        }
        val client = McpClient(serverConfig(fake), token = null)
        client.connect()

        assertEquals(2, client.listTools().size)
    }

    @Test
    fun `an SSE answer with only foreign ids is refused`() = runBlocking {
        val fake = fakeServer {
            pageTools = 2
            sse = true
            foreignFrameLast = true
            omitMatchingFrame = true
        }
        val client = McpClient(serverConfig(fake), token = null)
        client.connect()

        val failure = runCatching { client.listTools() }.exceptionOrNull()
        assertTrue("expected an McpException, got $failure", failure is McpException)
    }

    @Test
    fun `sessions are reused between calls and dropped after a failure`() = runBlocking {
        val fake = fakeServer { pageTools = 1 }
        val server = serverConfig(fake)

        val first = McpSessions.withClient(server, token = null) { it.callTool("tool_0", "{}") }
        val second = McpSessions.withClient(server, token = null) { it.callTool("tool_0", "{}") }
        assertEquals("ok", first)
        assertEquals("ok", second)
        assertEquals("one handshake for two calls", 1, fake.handshakes.get())

        fake.failNextCall.set(true)
        runCatching { McpSessions.withClient(server, token = null) { it.callTool("tool_0", "{}") } }
        McpSessions.withClient(server, token = null) { it.callTool("tool_0", "{}") }
        assertEquals("a failed call forces a fresh handshake", 2, fake.handshakes.get())
        McpSessions.drop(server.id)
    }

    @Test
    fun `a long server tool name is capped for providers and stays unique`() {
        val server = McpServer(id = "abcdef", displayName = "Analytics Warehouse", baseUrl = "https://example.test/mcp")
        val longName = "query_the_entire_analytics_warehouse_for_everything_that_ever_happened"
        val otherName = "query_the_entire_analytics_warehouse_for_everything_that_ever_happens"

        val first = McpToolHandler(server, McpServerTool(longName, "d", "{}"), null).definition.name
        val second = McpToolHandler(server, McpServerTool(otherName, "d", "{}"), null).definition.name

        assertTrue("name '$first' is ${first.length} chars", first.length <= 64)
        assertTrue("name '$second' is ${second.length} chars", second.length <= 64)
        assertNotEquals("truncation must not merge two tools", first, second)
        assertTrue(first.startsWith("mcp_analytics_wareho_"))
    }

    /** A minimal JSON-RPC MCP server: paginated tools, optional SSE framing, one-shot failures. */
    private class FakeMcpServer {
        var pageTools = 0
        var sse = false
        var foreignFrameLast = false
        var omitMatchingFrame = false
        val handshakes = AtomicInteger()
        val listRequests = AtomicInteger()
        val failNextCall = java.util.concurrent.atomic.AtomicBoolean(false)

        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl: String get() = "http://127.0.0.1:${http.address.port}"

        init {
            http.createContext("/") { exchange -> handle(exchange) }
            http.start()
        }

        fun close() = http.stop(0)

        private fun handle(exchange: HttpExchange) {
            val request = runCatching {
                JSONObject(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            }.getOrElse { JSONObject() }
            val id = request.opt("id")
            when (request.optString("method")) {
                "initialize" -> {
                    handshakes.incrementAndGet()
                    exchange.responseHeaders.add("Mcp-Session-Id", "session-1")
                    respond(exchange, result = JSONObject().put("protocolVersion", "2025-03-26"), id = id)
                }
                "notifications/initialized" -> {
                    exchange.sendResponseHeaders(202, -1)
                    exchange.close()
                }
                "tools/list" -> {
                    val cursor = request.optJSONObject("params")?.optString("cursor").orEmpty()
                    val start = cursor.removePrefix("page_").toIntOrNull() ?: 0
                    val pageSize = 50
                    val tools = JSONArray()
                    for (index in start until minOf(start + pageSize, pageTools)) {
                        tools.put(
                            JSONObject()
                                .put("name", if (index == 1) "read_only_tool" else "tool_$index")
                                .put("description", "tool $index")
                                .put("inputSchema", JSONObject().put("type", "object"))
                                .apply {
                                    if (index == 1) put("annotations", JSONObject().put("readOnlyHint", true))
                                },
                        )
                    }
                    listRequests.incrementAndGet()
                    val result = JSONObject().put("tools", tools)
                    if (start + pageSize < pageTools) result.put("nextCursor", "page_${start + pageSize}")
                    respond(exchange, result = result, id = id, useSse = sse)
                }
                "tools/call" -> {
                    if (failNextCall.compareAndSet(true, false)) {
                        exchange.sendResponseHeaders(500, -1)
                        exchange.close()
                        return
                    }
                    val content = JSONArray().put(JSONObject().put("type", "text").put("text", "ok"))
                    respond(exchange, result = JSONObject().put("content", content), id = id)
                }
                else -> {
                    exchange.sendResponseHeaders(400, -1)
                    exchange.close()
                }
            }
        }

        /** Framed as SSE only when [useSse]; the handshake stays plain JSON. */
        private fun respond(exchange: HttpExchange, result: JSONObject, id: Any?, useSse: Boolean = false) {
            val message = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
            if (!useSse) {
                val bytes = message.toString().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                return
            }
            val foreign = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/message")
            val body = buildString {
                if (foreignFrameLast) {
                    if (!omitMatchingFrame) append("data: ").append(message).append("\n\n")
                    append("data: ").append(foreign).append("\n\n")
                } else {
                    append("data: ").append(foreign).append("\n\n")
                    if (!omitMatchingFrame) append("data: ").append(message).append("\n\n")
                }
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
