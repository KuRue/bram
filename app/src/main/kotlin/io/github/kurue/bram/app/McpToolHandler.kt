package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.McpServer
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import org.json.JSONObject

/**
 * One tool of a configured MCP server, presented to the agent like any other.
 *
 * The name, description, and schema come from the server, so they are untrusted input: the name is
 * prefixed with the configured server so two servers cannot collide and its provenance is visible
 * in the transcript, the description is shown to the user exactly as the server wrote it (the
 * approval gate quotes it, like every other tool's description), and the schema is whatever the
 * server sent. Like WebSearch and WebFetch, this tool leaves the device, so it always reaches the
 * approval gate; the gate shows which arguments the server asked for.
 *
 * The server's `readOnlyHint` is honored only as a classification — a read-only tool may join a
 * read-only batch — and never as a reason to skip the gate: the hint comes from the same untrusted
 * server the call is going to, so the ask stays. Calls share a cached session per server (see
 * [McpSessions]) rather than handshaking every time; a dropped session costs one extra round trip
 * and is retried once.
 */
class McpToolHandler(
    private val server: McpServer,
    private val tool: McpServerTool,
    private val token: String?,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = toolName(server, tool.name),
        description = tool.description.ifBlank {
            "A tool provided by the MCP server ${server.displayName}. Its arguments are whatever " +
                "the server defined in its input schema."
        },
        inputSchemaJson = tool.inputSchemaJson,
        requiredPermissions = setOf("internet"),
        readOnly = tool.readOnly,
        // Whatever the server returns is text from another process, which is the same problem a
        // fetched page is. Bram cannot see what it wrapped or where the server got it.
        returnsUntrustedContent = true,
        approvalScopeKeys = scopeKeys(tool.inputSchemaJson),
    )

    override suspend fun execute(argumentsJson: String): String {
        return runCatching {
            McpSessions.withClient(server, token) { client ->
                client.callTool(tool.name, argumentsJson)
            }
        }.getOrElse { failure ->
            toolError(
                "mcp_error",
                failure.message
                    ?: "The MCP server ${server.displayName} failed (${failure::class.java.simpleName})",
            )
        }
    }

    /** The approval gate names the arguments a call is about, like web tools name their URL. */
    private fun scopeKeys(schemaJson: String): List<String> {
        val properties = runCatching { JSONObject(schemaJson).optJSONObject("properties") }.getOrNull()
            ?: return emptyList()
        return properties.keys().asSequence().take(MAX_SCOPE_KEYS).toList()
    }

    private companion object {
        const val MAX_SCOPE_KEYS = 4

        /** Readable but unique: the server's display name, reduced to letters and digits. */
        fun slug(server: McpServer): String {
            val words = server.displayName.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
            return "${words.replace(" ", "_").take(16)}_${server.id.take(4)}"
        }

        /**
         * The name the model calls, kept inside the tightest function-name limit providers impose
         * (OpenAI accepts 64). A long server tool keeps its readable head and gains a short digest,
         * so two names that truncate alike stay distinct.
         */
        fun toolName(server: McpServer, name: String): String {
            val prefix = "mcp_${slug(server)}_"
            val candidate = prefix + name
            if (candidate.length <= MAX_TOOL_NAME_CHARS) return candidate
            val digest = digest8(name)
            val keep = (MAX_TOOL_NAME_CHARS - prefix.length - digest.length - 1).coerceAtLeast(0)
            return prefix + name.take(keep) + "_" + digest
        }

        fun digest8(value: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .take(4)
                .joinToString("") { "%02x".format(it) }

        const val MAX_TOOL_NAME_CHARS = 64
    }
}
