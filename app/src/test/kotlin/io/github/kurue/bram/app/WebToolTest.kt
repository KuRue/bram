package io.github.kurue.bram.app

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // --- redirect policy -------------------------------------------------------
    // The policy lives in followRedirects, which is pure over a single-request function. These
    // drive it with a script of canned responses so the cases that mattered for the
    // developer.android.com loop — cycle, scheme downgrade, cookie replay, the hop cap — are
    // covered without a network.

    /** A single-request fake that returns canned [Fetched] responses and records what it was asked. */
    private class ScriptedFetch(responses: Map<String, Fetched>) {
        private val responses = responses.toMap()
        val calls = mutableListOf<Pair<String, String?>>() // url → cookie sent
        fun fetch(url: java.net.URL, cookie: String?): Fetched {
            calls += url.toString() to cookie
            return responses[url.toString()] ?: error("unexpected fetch of $url")
        }
    }

    private fun page(body: String) = Fetched(200, null, "text/html", emptyList(), body)
    private fun redirect(to: String, setCookie: List<String> = emptyList()) =
        Fetched(302, to, "text/html", setCookie, null)

    @Test
    fun `follows a redirect chain to the final page`() {
        val script = ScriptedFetch(
            mapOf(
                "http://a/" to redirect("https://a/real"),
                "https://a/real" to page("the page"),
            ),
        )
        val result = followRedirects(java.net.URL("http://a/"), 5, script::fetch)
        assertEquals("the page", result.body)
        // Both hops happened, in order.
        assertEquals(listOf("http://a/", "https://a/real"), script.calls.map { it.first })
    }

    @Test
    fun `refuses an https to http downgrade`() {
        val script = ScriptedFetch(mapOf("https://a/" to redirect("http://a/insecure")))
        val error = org.junit.Assert.assertThrows(IOException::class.java) {
            followRedirects(java.net.URL("https://a/"), 5, script::fetch)
        }
        assertTrue("names both ends of the downgrade", error.message!!.contains("Refused insecure redirect"))
        assertTrue("did not follow the downgrade", script.calls.map { it.first } == listOf("https://a/"))
    }

    @Test
    fun `allows an http to https upgrade`() {
        val script = ScriptedFetch(
            mapOf(
                "http://a/" to redirect("https://a/secure"),
                "https://a/secure" to page("ok"),
            ),
        )
        val result = followRedirects(java.net.URL("http://a/"), 5, script::fetch)
        assertEquals("ok", result.body)
    }

    @Test
    fun `detects a cycle on the second hit instead of bouncing to the hop cap`() {
        // Points back at itself: with only a hop counter this would run the cap; cycle detection
        // fails it on the second hit.
        val script = ScriptedFetch(mapOf("http://a/" to redirect("http://a/")))
        val error = org.junit.Assert.assertThrows(IOException::class.java) {
            followRedirects(java.net.URL("http://a/"), 5, script::fetch)
        }
        assertTrue("says it is a cycle", error.message!!.contains("Redirect cycle"))
        assertTrue("nudges toward https for a plain-http loop", error.message!!.contains("https"))
        assertEquals("only fetched once before failing", 1, script.calls.size)
    }

    @Test
    fun `still caps a long legitimate chain`() {
        // Six distinct hops, cap of five: the backstop fires.
        val script = ScriptedFetch(
            mapOf(
                "http://a/1" to redirect("http://a/2"),
                "http://a/2" to redirect("http://a/3"),
                "http://a/3" to redirect("http://a/4"),
                "http://a/4" to redirect("http://a/5"),
                "http://a/5" to redirect("http://a/6"),
                "http://a/6" to redirect("http://a/7"),
                "http://a/7" to page("done"),
            ),
        )
        val error = org.junit.Assert.assertThrows(IOException::class.java) {
            followRedirects(java.net.URL("http://a/1"), 5, script::fetch)
        }
        assertTrue(error.message!!.contains("Too many redirects"))
    }

    @Test
    fun `replays a cookie set on a redirect on the next hop`() {
        // The case the host-scoped jar exists for: an edge sets a cookie on the 302 and would loop
        // without it.
        val script = ScriptedFetch(
            mapOf(
                "http://a/" to redirect("http://a/x", setCookie = listOf("session=abc; Path=/")),
                "http://a/x" to page("ok"),
            ),
        )
        followRedirects(java.net.URL("http://a/"), 5, script::fetch)
        // The first hop had no cookie to send; the second got the one the first set.
        assertNull(script.calls[0].second)
        assertEquals("session=abc", script.calls[1].second)
    }

    @Test
    fun `does not follow a 304 not modified`() {
        val script = ScriptedFetch(mapOf("http://a/" to Fetched(304, null, null, emptyList(), null)))
        val result = followRedirects(java.net.URL("http://a/"), 5, script::fetch)
        assertEquals(304, result.code)
        assertEquals("only the one request", 1, script.calls.size)
    }
}
