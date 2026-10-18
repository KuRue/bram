package io.github.kurue.bram.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.File
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class HttpsRecipeDownloadTest {
    private class Connection(
        private val status: Int = 200,
        private val body: ByteArray = "{}".toByteArray(),
        private val declaredSize: Long = -1,
    ) : HttpsURLConnection(URL("https://example.org/index.json")) {
        var disconnected = false
        var openedBody = false
        var closedBody = false
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun connect() {}
        override fun getCipherSuite() = "test"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode() = status
        override fun getContentLengthLong() = declaredSize
        override fun getInputStream(): InputStream {
            openedBody = true
            return object : ByteArrayInputStream(body) {
                override fun close() { closedBody = true; super.close() }
            }
        }
    }

    private fun read(connection: Connection, limit: Int = 1024, deadline: Long = System.nanoTime() + 45_000_000_000L) =
        HttpsRecipeDownload { connection }.read("https://example.org/index.json", limit, deadline)

    @Test fun `successful download closes its body uses timeouts and refuses automatic redirects`() {
        val connection = Connection()
        assertArrayEquals("{}".toByteArray(), read(connection))
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.connectTimeout in 1..10_000)
        assertTrue(connection.readTimeout in 1..10_000)
        assertTrue(connection.closedBody)
        assertTrue(connection.disconnected)
    }

    @Test fun `redirect is never followed or read as a document`() {
        val connection = Connection(status = 302)
        assertTrue(runCatching { read(connection) }.exceptionOrNull()!!.message!!.contains("HTTP 302"))
        assertFalse(connection.openedBody)
        assertTrue(connection.disconnected)
    }

    @Test fun `declared oversized body is refused before reading`() {
        val connection = Connection(declaredSize = 1025)
        assertTrue(runCatching { read(connection) }.isFailure)
        assertFalse(connection.openedBody)
        assertTrue(connection.disconnected)
    }

    @Test fun `chunked or lying oversized body is bounded and closed`() {
        listOf(-1L, 1L).forEach { size ->
            val connection = Connection(body = ByteArray(1025), declaredSize = size)
            assertTrue(runCatching { read(connection) }.exceptionOrNull()!!.message!!.contains("exceeds 1024"))
            assertTrue(connection.closedBody)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun `body exactly at the limit is accepted`() {
        val connection = Connection(body = ByteArray(1024))
        assertEquals(1024, read(connection).size)
    }

    @Test fun `expired shared budget prevents the next connection`() {
        val connection = Connection()
        assertTrue(runCatching { read(connection, deadline = System.nanoTime() - 1) }.isFailure)
        assertFalse(connection.openedBody)
        assertTrue(connection.disconnected)
    }

    @Test fun `the recipe transport accepts recipe documents as well as indexes`() {
        val connection = Connection()
        var requested: String? = null
        val reader = HttpsRecipeDownload { url -> requested = url; connection }
        val url = "https://example.org/snapshot/cpu-recipe.json"
        assertArrayEquals("{}".toByteArray(), reader.read(url, 1024, System.nanoTime() + 45_000_000_000L))
        assertEquals(url, requested)
    }

    @Test fun `recipe documents retain every HTTPS transport restriction`() {
        listOf("http://example.org/recipe.json", "https://user:pass@example.org/recipe.json", "https://example.org:8443/recipe.json", "https://example.org/recipe.json?secret=x", "https://example.org/recipe.json#x", "https://example.org/%2e%2e/recipe.json").forEach { url ->
            val reader = HttpsRecipeDownload { throw AssertionError("Must reject $url before connecting") }
            val failure = runCatching { reader.read(url, 1024, System.nanoTime() + 45_000_000_000L) }.exceptionOrNull()
            assertTrue("must refuse $url before connecting", failure is IllegalArgumentException)
        }
    }

    @Test fun `a complete snapshot passes through the production HTTPS reader`() {
        val assets = File("src/main/assets/hardware-recipes/official-v1")
        val directory = java.nio.file.Files.createTempDirectory("recipe-https-test").toFile()
        val requests = mutableListOf<String>()
        val signer = RecipeTestSigner()
        try {
            val store = RecipeSources(directory, { File(assets, it).readBytes() }, HttpsRecipeDownload { url ->
                requests += url
                Connection(body = if (url.endsWith("/index.signature.json")) signer.sign(File(assets, "index.json").readBytes())
                    else File(assets, java.net.URI(url).path.substringAfterLast('/')).readBytes())
            })
            val review = store.review("https://example.org/snapshot/index.json")
            assertEquals(3, requests.size)
            assertTrue(requests.last().endsWith("-cpu.json"))
            store.accept(review, review.snapshot.signature!!.fingerprint)
            assertEquals(review.snapshot.pin, store.sources().single().current.pin)
        } finally {
            directory.deleteRecursively()
        }
    }
}
