package io.github.kurue.bram.runtime.openai

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RemoteModelCatalogTest {
    private lateinit var server: HttpServer
    private lateinit var root: String
    private val requested = mutableListOf<String>()

    /** Path -> (status, body); anything unrouted answers 404 like a bare server. */
    private val routed = mutableMapOf<String, Pair<Int, String>>()

    @Before
    fun setUp() {
        routed["/v1/models"] = 200 to """{"data":[{"id":"z-model"},{"id":"a-model"}]}"""
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            requested += exchange.requestURI.path
            val (status, body) = routed[exchange.requestURI.path] ?: (404 to "{}")
            respond(exchange, status, body)
        }
        server.start()
        root = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun `server root discovers conventional v1 catalog`() = runBlocking {
        assertEquals(listOf("a-model", "z-model"), RemoteModelCatalog().list(root).map { it.id })
        assertEquals(listOf("/v1/models"), requested)
    }

    @Test
    fun `generation URL is reduced to its API root`() {
        assertEquals(listOf("https://example.test/prefix/v1/models"), modelUrls("https://example.test/prefix/v1/chat/completions"))
        assertEquals(listOf("https://example.test/v1/models"), modelUrls("https://example.test/v1/responses"))
    }

    @Test
    fun `falls back to plain models when v1 is absent`() = runBlocking {
        routed["/v1/models"] = 404 to "{}"
        routed["/models"] = 200 to """{"models":["y-model","x-model"]}"""

        assertEquals(listOf("x-model", "y-model"), RemoteModelCatalog().list(root).map { it.id })
        assertEquals(listOf("/v1/models", "/models"), requested)
    }

    @Test
    fun `context window is read from limit and duplicate ids collapse`() = runBlocking {
        routed["/v1/models"] = 200 to
            """{"data":[{"id":"m1","limit":{"context":4096}},{"id":"m1","context_window":2048}]}"""

        val models = RemoteModelCatalog().list(root)

        assertEquals(listOf("m1"), models.map { it.id })
        assertEquals(4096, models.single().contextWindowTokens)
    }

    @Test
    fun `a failed list surfaces the server message`() = runBlocking {
        routed["/v1/models"] = 401 to """{"error":{"message":"bad key"}}"""
        routed["/models"] = 401 to """{"error":{"message":"bad key"}}"""

        val failure = runCatching { RemoteModelCatalog().list(root) }.exceptionOrNull()

        assertEquals("Model list failed (401): bad key", failure?.message)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
