package io.github.kurue.bram.runtime.llamacpp.downloads

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HuggingFaceCatalogTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `tree parsing keeps ggufs with an lfs id, sorted smallest first`() {
        val body = """
            [
              {"type":"file","path":"model.Q4_K_M.gguf","size":2000,"lfs":{"oid":"aaa","size":2000}},
              {"type":"file","path":"README.md","size":42},
              {"type":"folder","path":"sub","size":0},
              {"type":"file","path":"sub/model.Q8_0.gguf","size":100,"lfs":{"oid":"bbb","size":100}},
              {"type":"file","path":"plain.gguf","size":500},
              {"type":"file","path":"no-lfs.gguf","size":300,"lfs":{}},
              {"type":"file","path":"config.json","size":10,"lfs":{"oid":"ccc","size":10}}
            ]
        """.trimIndent()

        val files = parseHuggingFaceTree(body, "owner/model-gguf")

        assertEquals(listOf("sub/model.Q8_0.gguf", "model.Q4_K_M.gguf"), files.map { it.fileName })
        assertEquals("bbb", files[0].sha256)
        assertEquals(100L, files[0].sizeBytes)
        assertEquals("owner/model-gguf", files[0].repoId)
    }

    @Test
    fun `tree parsing tolerates malformed entries and invalid json`() {
        val body = """
            [
              {"type":"file","path":"ok.gguf","size":10,"lfs":{"oid":"abc"}},
              "junk",
              {"type":"file"},
              null
            ]
        """.trimIndent()

        assertEquals(listOf("ok.gguf"), parseHuggingFaceTree(body, "r").map { it.fileName })
        assertTrue(parseHuggingFaceTree("not json at all", "r").isEmpty())
        assertTrue(parseHuggingFaceTree("[]", "r").isEmpty())
    }

    @Test
    fun `listGgufFiles fetches the tree api and parses the reply`() {
        server.createContext("/api/models/Owner/Model-GGUF/tree/main") { exchange ->
            val body = """
                [{"type":"file","path":"small.gguf","size":50,"lfs":{"oid":"s","size":50}},
                 {"type":"file","path":"big.gguf","size":900,"lfs":{"oid":"b","size":900}}]
            """.trimIndent().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val files = runBlocking {
            HuggingFaceCatalog(baseUrl = baseUrl).listGgufFiles("Owner/Model-GGUF")
        }

        assertEquals(listOf("small.gguf", "big.gguf"), files.map { it.fileName })
    }

    @Test
    fun `listGgufFiles explains a missing repository`() {
        server.createContext("/api/models/owner/nope/tree/main") { exchange ->
            val body = """{"error":"Entry not found"}""".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        try {
            runBlocking {
                HuggingFaceCatalog(baseUrl = baseUrl).listGgufFiles("owner/nope")
            }
            fail("expected DownloadSourceException")
        } catch (expected: DownloadSourceException) {
            assertTrue(expected.message.orEmpty().contains("No such repository"))
        }
    }

    @Test
    fun `listGgufFiles explains a gated repository`() {
        server.createContext("/api/models/owner/gated/tree/main") { exchange ->
            val body = "".toByteArray()
            exchange.responseHeaders.add("x-error-message", "Access to model is restricted")
            exchange.sendResponseHeaders(403, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        try {
            runBlocking {
                HuggingFaceCatalog(baseUrl = baseUrl).listGgufFiles("owner/gated")
            }
            fail("expected DownloadSourceException")
        } catch (expected: DownloadSourceException) {
            assertTrue(expected.message.orEmpty().contains("gated"))
        }
    }

    @Test
    fun `resolveDownloadUrl points at the downloadable file`() {
        val catalog = HuggingFaceCatalog(baseUrl = baseUrl)
        assertEquals(
            "$baseUrl/owner/model/resolve/main/model.gguf?download=true",
            catalog.resolveDownloadUrl("owner/model", "model.gguf"),
        )
    }
}
