package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import java.io.IOException
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
        // Results are titles and snippets written by whoever owns the page.
        returnsUntrustedContent = true,
        description = "Search the public web (DuckDuckGo) and return top results as title, URL, " +
            "and snippet. Use only for current or factual information that no purpose-built " +
            "tool offered this run covers.",
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
        // The whole point of the tool is to put a page Bram did not write into the context.
        returnsUntrustedContent = true,
        description = "Download one web page and return its text with HTML stripped. Use after " +
            "web_search has picked a URL to read.",
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

/** HTTP status codes that mean "this request lives somewhere else" and should be followed. */
private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

/** One hop's outcome, in the shape the redirect policy needs to decide what to do next. */
internal data class Fetched(
    val code: Int,
    val location: String?,
    val contentType: String?,
    val setCookie: List<String>,
    val body: String?,
)

/**
 * Reads up to [maxChars] of a response body as UTF-8, following redirects by hand.
 *
 * Three things had been missing, and each had its own way of breaking a fetch on a bot-hostile edge
 * (developer.android.com was the reported case, looping under plain HTTP):
 *
 *  - Cookies were never replayed. A CDN that sets a cookie on the first 302 and re-redirects
 *    without it loops to the hop cap. Cookies are now kept in a host-scoped jar and sent back.
 *  - There was no cycle detection, only a hop counter, so a server pointing back at itself was hit
 *    the full number of times before failing. A visited set fails it on the second hit.
 *  - An https-to-http downgrade was followed silently. That is both a content-leak and one of the
 *    shapes a loop takes; it is now refused.
 *
 * The hop cap stays as a backstop. The policy itself lives in [followRedirects], which is pure over
 * a single-request function so it can be unit-tested without sockets.
 */
internal fun fetchRaw(url: URL, maxChars: Int): String {
    val response = followRedirects(url, MAX_REDIRECTS) { current, cookie ->
        fetchOneHttp(current, cookie, maxChars)
    }
    return response.body ?: throw IOException("Empty response")
}

/**
 * Follows redirects from [initial] using [fetchOne] for each hop. Pure orchestration over a
 * single-request function, so the redirect policy — cycle detection, scheme-downgrade refusal,
 * cookie replay, the hop cap — is unit-testable without a network.
 *
 * Cookies are kept in a host-scoped jar rather than a full RFC 6265 store: the case that matters
 * here is a bot-hostile edge that sets a cookie on a 302 and loops without it, which is always
 * same-host, and a pragmatic jar keeps the policy readable and testable.
 */
internal fun followRedirects(
    initial: URL,
    maxRedirects: Int,
    fetchOne: (url: URL, cookie: String?) -> Fetched,
): Fetched {
    val visited = LinkedHashSet<String>()
    val cookies = mutableMapOf<String, String>()
    var current = initial
    repeat(maxRedirects + 1) {
        if (!visited.add(current.toString())) {
            // A GET that returns to a URL it already hit this chain is a loop by definition. Say so,
            // and when it happened over plain http nudge the caller toward https, which is usually
            // where the real page lives and where the loop does not.
            val hint = if (current.protocol == "http") "; try the https:// URL instead" else ""
            throw IOException("Redirect cycle back to $current$hint")
        }
        val response = fetchOne(current, cookies[current.host])
        if (response.setCookie.isNotEmpty()) {
            // A Set-Cookie line carries attributes after the pair ("k=v; Path=/; Secure"); only the
            // pair goes into the Cookie request header.
            val received = response.setCookie.joinToString("; ") { it.substringBefore(";") }
            if (received.isNotBlank()) cookies.merge(current.host, received) { a, b -> "$a; $b" }
        }
        if (response.code in REDIRECT_CODES) {
            val location = response.location
                ?: throw IOException("Redirect (${response.code}) had no Location")
            val target = URL(current, location)
            if (current.protocol == "https" && target.protocol == "http") {
                throw IOException("Refused insecure redirect from https to http: $current → $target")
            }
            current = target
            return@repeat
        }
        return response
    }
    throw IOException("Too many redirects (more than $maxRedirects)")
}

/** One real HTTP request: opens the connection, sends the cookie jar, reads one response. */
private fun fetchOneHttp(url: URL, cookie: String?, maxChars: Int): Fetched {
    val connection = (url.openConnection() as HttpURLConnection).apply {
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
        if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
    }
    try {
        val code = connection.responseCode
        val setCookie = connection.headerFields?.get("Set-Cookie").orEmpty()
        if (code in REDIRECT_CODES) {
            return Fetched(
                code = code,
                location = connection.getHeaderField("Location"),
                contentType = connection.contentType,
                setCookie = setCookie,
                body = null,
            )
        }
        if (code !in 200..299) throw IOException("HTTP $code")
        val contentType = connection.contentType
        if (!isTextContentType(contentType)) {
            throw IOException("Refused non-text response ($contentType)")
        }
        return Fetched(
            code = code,
            location = null,
            contentType = contentType,
            setCookie = setCookie,
            body = decodeBody(connection.inputStream, connection.contentEncoding, maxChars),
        )
    } finally {
        connection.disconnect()
    }
}

/**
 * Reads a response body, transparently inflating it when the server compressed it.
 *
 * Pulled out so the gzip path is unit-testable without a network: a server that saw
 * Accept-Encoding: gzip answers gzipped regardless of what the caller would prefer.
 */
internal fun decodeBody(stream: java.io.InputStream, encoding: String?, maxChars: Int): String {
    val inflated = when (encoding?.lowercase()) {
        "gzip" -> java.util.zip.GZIPInputStream(stream)
        "deflate" -> java.util.zip.InflaterInputStream(stream)
        else -> stream
    }
    // Read with a ceiling so a server streaming gigabytes of body — or a binary the content-type
    // guard missed — cannot OOM the tool: stop once maxChars is reached rather than buffering the
    // whole response and slicing afterward.
    val reader = inflated.bufferedReader(Charsets.UTF_8)
    val buffer = CharArray(8192)
    val sb = StringBuilder()
    while (true) {
        val read = reader.read(buffer)
        if (read <= 0) break
        sb.append(buffer, 0, read)
        if (sb.length >= maxChars) {
            sb.setLength(maxChars)
            break
        }
    }
    return sb.toString()
}

/**
 * Whether a response is worth reading as text. A null or blank type is treated as text: many
 * servers omit it, and the tool's purpose is text, so refusing on absence would break pages that
 * load fine.
 */
private fun isTextContentType(contentType: String?): Boolean {
    if (contentType.isNullOrBlank()) return true
    val mime = contentType.substringBefore(';').trim().lowercase()
    return mime.startsWith("text/") || mime in TEXT_CONTENT_TYPES
}

private val TEXT_CONTENT_TYPES = setOf(
    "application/json",
    "application/xml",
    "application/xhtml+xml",
    "application/javascript",
    "application/ld+json",
    "application/rss+xml",
    "application/atom+xml",
)

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
