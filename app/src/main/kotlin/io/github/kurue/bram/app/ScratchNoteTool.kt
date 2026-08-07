package io.github.kurue.bram.app

import android.content.Context
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Writes a short note into app-private storage.
 *
 * This exists to exercise the approval flow, which until now had nothing to exercise it: the only
 * other tool is read-only and takes the fast path, so the approval card had never rendered on a
 * device. A tool that changes something, names a target, and can be run repeatedly without
 * consequence is what that needs.
 *
 * It is deliberately harmless. Notes go in the app's own files directory, so nothing outside Bram
 * can be reached, the name is stripped to a single path segment, and the body is capped. That is
 * not because the approval gate is expected to fail, but because a tool that only exists to test
 * the gate should not be the most dangerous thing in the app.
 */
class ScratchNoteTool(context: Context) : ToolHandler {
    private val notesDirectory = File(context.applicationContext.filesDir, "notes")

    override val definition = ToolDefinition(
        name = "write_note",
        description = "Write a short note to Bram's private storage under a given name.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "name":{"type":"string","description":"File name for the note, without a path."},
               "body":{"type":"string","description":"What to write."}},
             "required":["name","body"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = false,
        requiredPermissions = setOf("private_storage"),
        // The note's name is what an allowance is granted for. Allowing a rewrite of "shopping"
        // should not allow writing anything else.
        approvalScopeKeys = listOf("name"),
    )

    override suspend fun execute(argumentsJson: String): String = withContext(Dispatchers.IO) {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return@withContext error("invalid_arguments", "Arguments were not valid JSON")
        val name = sanitize(arguments.optString("name"))
            ?: return@withContext error("invalid_name", "A note needs a name")
        val body = arguments.optString("body").take(MAX_BODY_CHARS)

        runCatching {
            notesDirectory.mkdirs()
            val file = File(notesDirectory, name)
            file.writeText(body)
            JSONObject()
                .put("wrote", name)
                .put("bytes", body.toByteArray().size)
                .toString()
        }.getOrElse { failure ->
            error("write_failed", failure.message ?: failure::class.java.simpleName)
        }
    }

    /**
     * Reduces a name to one ordinary path segment.
     *
     * The model chooses this string, and a model repeating something it read is how a note called
     * `../../databases/x` gets attempted. Traversal is stripped rather than rejected so an
     * unhelpful name still writes somewhere sensible.
     */
    private fun sanitize(raw: String): String? = raw
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        .trim('.')
        .take(64)
        .takeIf(String::isNotEmpty)

    private fun error(code: String, message: String): String =
        JSONObject().put("error", JSONObject().put("code", code).put("message", message)).toString()

    private companion object {
        const val MAX_BODY_CHARS = 4_000
    }
}
