package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.McpServer
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A transport-level or protocol-level MCP failure, surfaced to the model as a tool error. */
class McpException(code: String, message: String) : Exception(message) {
    val errorCode: String = code
}

/**
 * A tool a remote MCP server advertises. The description and schema are untrusted input: they are
 * parsed defensively below and are subject to the approval gate like any other tool.
 */
data class McpServerTool(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
)

/**
 * Client for the MCP streamable-HTTP transport (protocol version 2025-03-26).
 *
 * The protocol is JSON-RPC 2.0 over POSTs: an `initialize` handshake that returns a session id, an
 * `initialized` notification, then `tools/list` and `tools/call`. The identity is pinned to the
 * configured origin — redirects to another host are refused, so a configured server can never
 * silently forward its session (and credentials) somewhere the user did not choose. The server's
 * own identity is likewise fixed at configuration time: whatever answers the configured URL is the
 * server, and everything it says is treated as untrusted input.
 *
 * A server may answer a POST with `text/event-stream` rather than JSON; both are accepted, since
 * the spec permits either for POST responses.
 */
class McpClient(
    private val server: McpServer,
    private val token: String?,
) {
    private var nextId = 1
    private var sessionId: String? = null

    /** Handshake: `initialize`, then the `initialized` notification. Throws [McpException] on failure. */
    suspend fun connect() = withContext(Dispatchers.IO) {
        request(
            method = "initialize",
            params = JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "bram").put("version", CLIENT_VERSION)),
            timeoutMillis = HANDSHAKE_TIMEOUT_MILLIS,
        )
        // Notification: no id, no response. Some servers only start serving tools after it.
        request(
            method = "notifications/initialized",
            params = JSONObject(),
            notification = true,
            timeoutMillis = HANDSHAKE_TIMEOUT_MILLIS,
        )
    }

    /** Lists the tools the server advertises, after the handshake. */
    suspend fun listTools(): List<McpServerTool> = withContext(Dispatchers.IO) {
        val result = request(
            method = "tools/list",
            params = JSONObject(),
            timeoutMillis = LIST_TIMEOUT_MILLIS,
        ).optJSONObject("result")
        parseTools(result?.optJSONArray("tools"))
    }

    /** Runs one of the listed tools; the response is the tool result text for the model. */
    suspend fun callTool(name: String, argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrElse {
            throw McpException("bad_arguments", "Arguments were not valid JSON: ${it.message}")
        }
        val response = request(
            method = "tools/call",
            params = JSONObject().put("name", name).put("arguments", arguments),
            timeoutMillis = CALL_TIMEOUT_MILLIS,
        )
        renderResult(response)
    }

    private fun request(
        method: String,
        params: JSONObject,
        notification: Boolean = false,
        timeoutMillis: Int,
    ): JSONObject {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .apply { if (!notification) put("id", nextId++) }
            .put("method", method)
            .put("params", params)
        val body = post(payload.toString(), timeoutMillis)
        if (notification) return JSONObject()
        val response = runCatching { JSONObject(body) }.getOrElse {
            throw McpException("bad_response", "The server answered with something that is not JSON-RPC")
        }
        val error = response.optJSONObject("error")
        if (error != null) {
            throw McpException(
                "jsonrpc_${error.optInt("code", -1)}",
                error.optString("message").ifBlank { "The server returned a JSON-RPC error" },
            )
        }
        return response
    }

    /**
     * POSTs a JSON-RPC frame, follows only same-origin redirects, and returns the response body
     * whether it arrived as JSON or as SSE frames.
     */
    private fun post(payload: String, timeoutMillis: Int): String {
        var current = URL(server.baseUrl.trimEnd('/'))
        val originHost = current.host
        repeat(MAX_REDIRECTS + 1) {
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = timeoutMillis
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json, text/event-stream")
                setRequestProperty("MCP-Protocol-Version", PROTOCOL_VERSION)
                sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
                token?.takeIf(String::isNotBlank)?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
            try {
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw McpException(
                        "redirect", "The server redirected without a Location header",
                    )
                    val next = URL(current, location)
                    // The server's identity is pinned: a redirect that changes the host is refused
                    // rather than followed, or a compromised server could forward the session and
                    // its Authorization header anywhere.
                    if (next.host != originHost) {
                        throw McpException("redirect", "The server redirected to another host ($originHost → ${next.host}), which is refused")
                    }
                    val upgraded = current.protocol == "http" && next.protocol == "https"
                    if (next.protocol != current.protocol && !upgraded) {
                        throw McpException("redirect", "The server redirected to a different protocol, which is refused")
                    }
                    current = next
                    return@repeat
                }
                if (code !in 200..299) {
                    throw McpException("http_$code", "The server answered HTTP $code")
                }
                connection.getHeaderField("Mcp-Session-Id")?.takeIf(String::isNotBlank)?.let {
                    sessionId = it
                }
                val contentType = connection.contentType?.lowercase().orEmpty()
                val body = readCapped(connection.inputStream)
                return if (contentType.contains("text/event-stream")) parseSse(body) else body
            } finally {
                connection.disconnect()
            }
        }
        throw McpException("redirect", "The server redirected more than $MAX_REDIRECTS times")
    }

    /** A POST answered with SSE carries the JSON-RPC message in `data:` frames; the last one wins. */
    private fun parseSse(body: String): String {
        val frames = body.lineSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .toList()
        val last = frames.lastOrNull()
            ?: throw McpException("bad_response", "The server answered an empty event stream")
        // A response to one request arrives as one message; frames before it are server-initiated
        // messages with other ids, which this client has no use for.
        return runCatching { JSONObject(last) }.getOrElse {
            throw McpException("bad_response", "The server answered an event that is not JSON-RPC")
        }.toString()
    }

    private fun parseTools(tools: JSONArray?): List<McpServerTool> {
        if (tools == null) return emptyList()
        val out = mutableListOf<McpServerTool>()
        for (index in 0 until tools.length()) {
            if (out.size >= MAX_TOOLS) break
            val entry = tools.optJSONObject(index) ?: continue
            val name = entry.optString("name").trim()
            // Names become tool-call keys and appear in the transcript; reject anything that is
            // not a plain identifier rather than letting a hostile name break the registry.
            if (name.isEmpty() || !name.matches(IDENTIFIER)) continue
            val description = entry.optString("description").trim().take(MAX_DESCRIPTION_CHARS)
            val schema = entry.optJSONObject("inputSchema")
            val schemaJson = schema?.toString()?.take(MAX_SCHEMA_CHARS)
                ?: DEFAULT_SCHEMA
            out += McpServerTool(name, description, schemaJson)
        }
        return out
    }

    /**
     * Turns a `tools/call` result into the text a model reads. Content items may be text, images,
     * audio, video, or resources; everything that is not text is reported by its type and size
     * rather than dumped, and an `isError` result is marked as such so the run can respond to a
     * server-side failure instead of mistaking it for success.
     */
    private fun renderResult(response: JSONObject): String {
        val result = response.optJSONObject("result") ?: return "{}"
        val text = StringBuilder()
        val content = result.optJSONArray("content")
        if (content != null) {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                when (val type = item.optString("type")) {
                    "text" -> {
                        text.append(item.optString("text"))
                        text.append('\n')
                    }
                    "resource" -> {
                        text.append("[resource: ")
                        val resource = item.optJSONObject("resource")
                        if (resource != null) {
                            text.append(resource.optString("uri")).append("]\n")
                            text.append(resource.optString("text"))
                        } else {
                            text.append(item.optString("uri")).append("]\n")
                        }
                        text.append('\n')
                    }
                    else -> text.append("[$type content")
                        .append(item.optString("mimeType").takeIf(String::isNotBlank)?.let { ", $it" }.orEmpty())
                        .append(" omitted; it is not text]\n")
                }
            }
        }
        result.optJSONObject("structuredContent")?.let { structured ->
            if (text.isBlank()) text.append(structured.toString())
        }
        val body = text.toString().trim().ifBlank { "(no content)" }
        return if (result.optBoolean("isError")) "SERVER ERROR: $body" else body
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-03-26"
        const val CLIENT_VERSION = "0.2.0-alpha01"
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val HANDSHAKE_TIMEOUT_MILLIS = 30_000
        const val LIST_TIMEOUT_MILLIS = 30_000
        const val CALL_TIMEOUT_MILLIS = 600_000
        const val MAX_REDIRECTS = 3
        const val MAX_TOOLS = 100
        const val MAX_DESCRIPTION_CHARS = 2_000
        const val MAX_SCHEMA_CHARS = 64 * 1_024
        const val MAX_RESPONSE_CHARS = 4 * 1_024 * 1_024
        val IDENTIFIER = Regex("""[A-Za-z0-9_.-]{1,64}""")
        const val DEFAULT_SCHEMA = """{"type":"object","properties":{},"additionalProperties":false}"""

        /** Reads a body with a hard cap so a misbehaving server cannot exhaust device memory. */
        fun readCapped(stream: java.io.InputStream): String {
            val out = StringBuilder()
            val buffer = ByteArray(8 * 1_024)
            val reader = stream
            var total = 0
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_RESPONSE_CHARS) {
                    throw McpException("too_large", "The server answered more than the ${MAX_RESPONSE_CHARS / 1_048_576} MB cap")
                }
                out.append(String(buffer, 0, count, Charsets.UTF_8))
            }
            return out.toString()
        }
    }
}
