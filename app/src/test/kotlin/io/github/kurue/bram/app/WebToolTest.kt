package io.github.kurue.bram.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebToolTest {

    @Test
    fun `stripHtml removes tags and collapses whitespace`() {
        val text = stripHtml("<p>Hello <b>world</b>.</p>\n<script>ignore()</script> Next.")
        assertEquals("Hello world. Next.", text)
    }

    @Test
    fun `stripHtml drops style and script blocks whole`() {
        val text = stripHtml("<style>a {color:red}</style><div>visible</div><script>x()</script>")
        assertEquals("visible", text)
    }

    @Test
    fun `decodeEntities unescapes the common entities`() {
        assertEquals("a & b < c > d \"e\" 'f'", decodeEntities("a &amp; b &lt; c &gt; d &quot;e&quot; &#39;f&#39;"))
    }

    @Test
    fun `decodeUddg pulls the real url out of a DuckDuckGo redirect`() {
        val href = "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpath&rut=abc&raf=1"
        assertEquals("https://example.com/path", decodeUddg(href))
    }

    @Test
    fun `decodeUddg returns null when there is no uddg parameter`() {
        assertEquals(null, decodeUddg("https://example.com/straight"))
    }

    @Test
    fun `parseDuckDuckGo reads titles urls and snippets`() {
        val html = """
            <div class="results">
              <a rel="nofollow" class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FCat&rut=x">Cats &amp; kittens</a>
              <a class="result__snippet" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FCat">The cat is a domestic <b>species</b> of small carnivorous mammal.</a>
              <a rel="nofollow" class="result__a" href="https://duckduckgo.com/l/?uddg=https%3A%2F%2Fdogs.example%2F&rut=y">Dogs home</a>
              <a class="result__snippet" href="https://duckduckgo.com/l/?uddg=...">A dog is a pet.</a>
            </div>
        """.trimIndent()

        val results = parseDuckDuckGo(html, 5)
        assertEquals(2, results.size)
        assertEquals("Cats & kittens", results[0].getString("title"))
        assertEquals("https://en.wikipedia.org/wiki/Cat", results[0].getString("url"))
        assertEquals("The cat is a domestic species of small carnivorous mammal.", results[0].getString("snippet"))
        assertEquals("Dogs home", results[1].getString("title"))
        assertEquals("https://dogs.example/", results[1].getString("url"))
    }

    @Test
    fun `parseDuckDuckGo caps at the requested count`() {
        val html = buildString {
            repeat(8) { i ->
                append("<a class=\"result__a\" href=\"https://duckduckgo.com/l/?uddg=https%3A%2F%2Fx%2Ecom%2F$i\">Result $i</a>")
            }
        }
        assertEquals(3, parseDuckDuckGo(html, 3).size)
    }

    @Test
    fun `parseDuckDuckGo is empty when the markup is not a results page`() {
        assertTrue(parseDuckDuckGo("<html><body>nothing here</body></html>", 5).isEmpty())
    }

    @Test
    fun `web_search tool definition is not read-only and needs the internet permission`() {
        val def = WebSearchTool().definition
        assertEquals("web_search", def.name)
        assertTrue(def.requiredPermissions.contains("internet"))
        assertTrue(!def.readOnly)
    }

    @Test
    fun `web_fetch tool definition scopes approval to the url`() {
        val def = WebFetchTool().definition
        assertEquals("web_fetch", def.name)
        assertEquals(listOf("url"), def.approvalScopeKeys)
    }

    @Test
    fun `decodeBody inflates a gzip response`() {
        val raw = "Hello, compressed world. All kinds of text that should survive a gzip round trip."
        val baos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(baos).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
        val decoded = decodeBody(java.io.ByteArrayInputStream(baos.toByteArray()), "gzip", 10_000)
        assertEquals(raw, decoded)
    }

    @Test
    fun `decodeBody passes plain bytes through when there is no content encoding`() {
        val raw = "plain text, no compression"
        val decoded = decodeBody(java.io.ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)), null, 10_000)
        assertEquals(raw, decoded)
    }

    @Test
    fun `decodeBody stops at maxChars rather than buffering a huge body`() {
        val raw = "0123456789".repeat(1_000) // 10k chars
        val decoded = decodeBody(java.io.ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)), null, 250)
        assertEquals(250, decoded.length)
        assertEquals("0123456789".repeat(25), decoded)
    }

    @Test
    fun `an empty query is rejected without a network call`() {
        // execute runs on Dispatchers.IO; the guard returns an error JSON before any fetch happens.
        val emptyArgs = org.json.JSONObject().put("query", "   ").toString()
        val out = kotlinx.coroutines.runBlocking { WebSearchTool().execute(emptyArgs) }
        val err = org.json.JSONObject(out).getJSONObject("error")
        assertEquals("invalid_query", err.getString("code"))
    }
}
