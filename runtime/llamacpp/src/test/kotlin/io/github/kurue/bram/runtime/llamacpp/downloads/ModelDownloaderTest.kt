package io.github.kurue.bram.runtime.llamacpp.downloads

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloaderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun downloader() = ModelDownloader(catalog = HuggingFaceCatalog(baseUrl = baseUrl))

    @Test
    fun `downloads, verifies the sha256, and lands a digest-named file`() {
        val payload = "hello bram".repeat(10_000).toByteArray()
        server.createContext("/owner/model/resolve/main/model.gguf") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        val staging = temporaryFolder.newFolder("staging")
        val file = RemoteModelFile("owner/model", "model.gguf", payload.size.toLong(), sha256(payload))
        val progress = mutableListOf<String>()

        val downloaded = runBlocking {
            downloader().download(file, staging) { progress.add("${it.stage}:${it.bytesRead}") }
        }

        assertEquals("${file.sha256.take(24)}.gguf", downloaded.name)
        assertEquals(payload.toList(), downloaded.readBytes().toList())
        assertTrue(progress.last().startsWith("Verified:"))
        assertTrue(progress.any { it.startsWith("Verifying") })
        assertEquals(payload.size, downloaded.readBytes().size)
    }

    @Test
    fun `a sha mismatch is reported and the staging file is removed`() {
        val payload = "wrong payload".toByteArray()
        server.createContext("/owner/model/resolve/main/model.gguf") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }

        val staging = temporaryFolder.newFolder("staging")
        val file = RemoteModelFile("owner/model", "model.gguf", payload.size.toLong(), "0".repeat(64))

        try {
            runBlocking { downloader().download(file, staging) }
            fail("expected sha verification to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("SHA-256"))
        }

        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a truncated response is rejected and cleaned up`() {
        val payload = ByteArray(1_000_000) { 1 }
        server.createContext("/owner/model/resolve/main/model.gguf") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong() * 2)
            exchange.responseBody.use { it.write(payload) }
        }

        val staging = temporaryFolder.newFolder("staging")
        val file = RemoteModelFile("owner/model", "model.gguf", payload.size.toLong() * 2, "a".repeat(64))

        try {
            runBlocking { downloader().download(file, staging) }
            fail("expected truncated download to fail")
        } catch (expected: DownloadSourceException) {
            assertTrue(expected.message.orEmpty().contains("ended early"))
        }

        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an http failure is surfaced with its status code`() {
        server.createContext("/owner/model/resolve/main/model.gguf") { exchange ->
            val body = "not found".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val staging = temporaryFolder.newFolder("staging")
        val file = RemoteModelFile("owner/model", "model.gguf", 10, "a".repeat(64))

        try {
            runBlocking { downloader().download(file, staging) }
            fail("expected http failure")
        } catch (expected: DownloadSourceException) {
            assertTrue(expected.message.orEmpty().contains("404"))
        }
    }

    @Test
    fun `cancellation stops the transfer and removes the staging file`() {
        server.createContext("/owner/model/resolve/main/model.gguf") { exchange ->
            exchange.sendResponseHeaders(200, Long.MAX_VALUE)
            val chunk = ByteArray(64 * 1024) { 2 }
            exchange.responseBody.use { body ->
                // Stream forever in a way that lets a blocked read still be interrupted.
                while (true) {
                    try {
                        body.write(chunk)
                        body.flush()
                    } catch (closed: java.io.IOException) {
                        break
                    }
                    Thread.sleep(5)
                }
            }
        }

        val staging = temporaryFolder.newFolder("staging")
        val file = RemoteModelFile("owner/model", "model.gguf", Long.MAX_VALUE, "a".repeat(64))

        runBlocking {
            val job = launch {
                try {
                    downloader().download(file, staging)
                    fail("expected cancellation")
                } catch (expected: CancellationException) {
                    // expected
                }
            }
            delay(200)
            job.cancel()
            job.join()
        }

        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }
}
