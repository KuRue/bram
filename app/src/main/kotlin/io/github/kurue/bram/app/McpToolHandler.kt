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
 * Each call uses a fresh session: a handshake per call is a few extra small POSTs, and it means a
 * stale session can never be half-replayed after the process was killed, which matters more than
 * the round trips.
 */
class McpToolHandler(
    private val server: McpServer,
    private val tool: McpServerTool,
    private val token: String?,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "mcp_${slug(server)}_${tool.name}",
        description = tool.description.ifBlank {
            "A tool provided by the MCP server ${server.displayName}. Its arguments are whatever " +
                "the server defined in its input schema."
        },
        inputSchemaJson = tool.inputSchemaJson,
        requiredPermissions = setOf("internet"),
        readOnly = false,
        // Whatever the server returns is text from another process, which is the same problem a
        // fetched page is. Bram cannot see what it wrapped or where the server got it.
        returnsUntrustedContent = true,
        approvalScopeKeys = scopeKeys(tool.inputSchemaJson),
    )

    override suspend fun execute(argumentsJson: String): String {
        val client = McpClient(server, token)
        return runCatching {
            client.connect()
            client.callTool(tool.name, argumentsJson)
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
    }
}
