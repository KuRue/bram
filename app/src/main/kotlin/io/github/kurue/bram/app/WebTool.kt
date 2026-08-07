package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tools that reach the public web.
 *
 * Both leave the device and expose what is asked for to whoever serves the request, so neither is
 * read-only: they always reach the approval gate, which shows the query or URL before anything is
 * sent. Output is plain text, capped, because a model can use a digest and raw HTML is both large
 * and a place to hide prompts.
 */
class WebSearchTool : ToolHandler {
    override val definition = ToolDefinition(
        name = "web_search",
        description = "Search the public web (DuckDuckGo) and return the top results as title, " +
            "URL, and a snippet. Use for current or factual information you do not already have.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "query":{"type":"string","description":"What to search for."},
               "count":{"type":"integer","description":"Maximum results to return. Default 5."}},
             "required":["query"],
             "additionalProperties":false}
        """.trimIndent(),
        requiredPermissions = setOf("internet"),
        readOnly = false,
        approvalScopeKeys = listOf("query"),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext toolError("invalid_arguments", "Arguments were not valid JSON")
        val query = arguments.optString("query").trim()
        if (query.isEmpty()) return@withContext toolError("invalid_query", "A search needs a query")
        val count = arguments.optInt("count", 5).coerceIn(1, 8)
        runCatching {
            val html = fetchRaw(URL("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")), 300_000)
            val results = parseDuckDuckGo(html, count)
            if (results.isEmpty()) {
                JSONObject().put("query", query).put("results", JSONArray()).put("note", "No results.").toString()
            } else {
                JSONObject().put("query", query).put("results", JSONArray().also { array -> results.forEach(array::put) }).toString()
            }
        }.getOrElse { failure -> toolError("search_failed", failure.message ?: failure::class.java.simpleName) }
    }
}

class WebFetchTool : ToolHandler {
    override val definition = ToolDefinition(
        name = "web_fetch",
        description = "Download a web page and return its text with HTML stripped. Use after " +
            "web_search to read a specific page in full.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "url":{"type":"string","description":"Absolute http(s) URL to fetch."}},
             "required":["url"],
             "additionalProperties":false}
        """.trimIndent(),
        requiredPermissions = setOf("internet"),
        readOnly = false,
        approvalScopeKeys = listOf("url"),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext toolError("invalid_arguments", "Arguments were not valid JSON")
        val raw = arguments.optString("url").trim()
        val url = runCatching { URL(raw) }.getOrNull()
            ?: return@withContext toolError("invalid_url", "The URL was not valid")
        if (url.protocol != "http" && url.protocol != "https") {
            return@withContext toolError("invalid_url", "Only http and https are supported")
        }
        runCatching {
            val text = stripHtml(fetchRaw(url, 200_000))
            JSONObject().put("url", url.toString()).put("chars", text.length).put("text", text).toString()
        }.getOrElse { failure -> toolError("fetch_failed", failure.message ?: failure::class.java.simpleName) }
    }
}

private const val WEB_UA =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

/** Most redirects a fetch will follow before giving up, so a redirect loop fails fast and cleanly. */
private const val MAX_REDIRECTS = 5

/**
 * Reads up to [maxChars] of a response body as UTF-8.
 *
 * Redirects are followed by hand with a small cap rather than through HttpURLConnection's auto-follow:
 * some pages (developer.android.com among them) issue a loop, and auto-follow runs to its hard limit
 * of 21 and throws "Too many follow-up requests", which is noisy and looks like a rate limit to the
 * model. A manual cap turns a loop into an ordinary tool error after five hops.
 */
internal fun fetchRaw(url: URL, maxChars: Int): String {
    var current = url
    repeat(MAX_REDIRECTS + 1) {
        val connection = (current.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", WEB_UA)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            // A browser-style compression header reads as a real client rather than a bot, which is
            // part of what was making some sites (developer.android.com) redirect-loop; the body is
            // decompressed in decodeBody.
            setRequestProperty("Accept-Encoding", "gzip, deflate")
        }
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw java.io.IOException("Redirect had no Location")
                current = URL(current, location)
                return@repeat
            }
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val body = decodeBody(connection.inputStream, connection.contentEncoding)
            return if (body.length > maxChars) body.substring(0, maxChars) else body
        } finally {
            connection.disconnect()
        }
    }
    throw java.io.IOException("Too many redirects (more than $MAX_REDIRECTS)")
}

/**
 * Reads a response body, transparently inflating it when the server compressed it.
 *
 * Pulled out so the gzip path is unit-testable without a network: a server that saw
 * Accept-Encoding: gzip answers gzipped regardless of what the caller would prefer.
 */
internal fun decodeBody(stream: java.io.InputStream, encoding: String?): String {
    val inflated = when (encoding?.lowercase()) {
        "gzip" -> java.util.zip.GZIPInputStream(stream)
        "deflate" -> java.util.zip.InflaterInputStream(stream)
        else -> stream
    }
    return inflated.bufferedReader(Charsets.UTF_8).readText()
}

/**
 * Pulls result links and snippets out of a DuckDuckGo HTML results page.
 *
 * DuckDuckGo's no-key HTML endpoint wraps each real result URL in a redirect of the form
 * /l/?uddg=<encoded url>. The title and snippet sit in anchors of known class, so a tolerant regex
 * scan is enough; it degrades to fewer results if the markup shifts rather than throwing.
 */
internal fun parseDuckDuckGo(html: String, count: Int): List<JSONObject> {
    val link = Regex("""class="result__a"[^>]*?href="([^"]*)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    val snippet = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
    val links = link.findAll(html).toList()
    val snippets = snippet.findAll(html).map { stripHtml(it.groupValues[1]).trim() }.toList()
    val out = ArrayList<JSONObject>()
    for ((index, match) in links.withIndex()) {
        if (index >= count) break
        val href = match.groupValues[1]
        val title = stripHtml(match.groupValues[2]).trim()
        val resolved = decodeUddg(href) ?: href
        if (title.isEmpty() && resolved.isBlank()) continue
        out += JSONObject()
            .put("title", title.ifBlank { resolved })
            .put("url", resolved)
            .put("snippet", snippets.getOrNull(index) ?: "")
    }
    return out
}

/** Returns the real URL hidden in a DuckDuckGo redirect, or null when [href] is not one. */
internal fun decodeUddg(href: String): String? {
    val marker = "uddg="
    val start = href.indexOf(marker)
    if (start < 0) return null
    val value = href.substring(start + marker.length).substringBefore('&')
    return runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrNull()
}

/**
 * Reduces HTML to its visible text. Removes script and style blocks wholesale, then every tag,
 * then un-escapes the common entities and collapses runs of whitespace. It is deliberately a
 * stripper rather than a parser: a model reads it, and approximate text is more useful than a
 * dependency on a full HTML library for the same approximate text.
 */
internal fun stripHtml(html: String): String {
    val withoutBlocks = html.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
    val withoutTags = withoutBlocks.replace(Regex("(?s)<[^>]+>"), " ")
    return decodeEntities(withoutTags)
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s+([.,;:!?])"), "$1")
        .trim()
}

internal fun decodeEntities(text: String): String = text
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&nbsp;", " ")

internal fun toolError(code: String, message: String): String =
    JSONObject().put("error", JSONObject().put("code", code).put("message", message)).toString()
