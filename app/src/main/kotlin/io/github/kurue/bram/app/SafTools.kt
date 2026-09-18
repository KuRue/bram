package io.github.kurue.bram.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.provider.DocumentsContract

/** One entry in the user-granted folder. */
data class AgentDocumentEntry(val name: String, val isDirectory: Boolean, val bytes: Long)

/** A SAF failure that carries a message the model can act on. */
class AgentDocumentException(message: String) : Exception(message)

/**
 * The folder the user granted Bram, behind an interface so the tools are testable without a device.
 *
 * Paths are relative and provider-resolved segment by segment: SAF document ids are opaque and
 * provider-specific, so a child is always found by querying the parent's children and matching the
 * display name, never by concatenating an id.
 */
interface AgentDocumentTree {
    suspend fun list(path: String): List<AgentDocumentEntry>
    suspend fun read(path: String): String
    suspend fun write(path: String, text: String)
}

/**
 * Remembers the folder the user granted through the system picker.
 *
 * The URI alone is not access: Android hands out a durable grant only when the app takes it, and
 * the grant can be revoked from system settings at any time. Both are checked on every read, so
 * the tools answer "no folder" the moment the user takes access away.
 */
object AgentFolderAccess {
    private const val PREFERENCES = "bram-agent-folder-v1"
    private const val KEY_TREE = "treeUri"

    fun grantedTree(context: Context): Uri? {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_TREE, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any {
            it.uri == stored && it.isReadPermission && it.isWritePermission
        }
        return if (granted) stored else null
    }

    fun grant(context: Context, treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE, treeUri.toString()).apply()
    }

    fun clear(context: Context) {
        runCatching {
            context.contentResolver.persistedUriPermissions
                .map { it.uri }
                .forEach { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(KEY_TREE).apply()
    }

    /** The tree root's own display name, for "Access to: Documents" style labels. */
    fun displayName(context: Context, treeUri: Uri): String? = runCatching {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        context.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
}

/** The production [AgentDocumentTree] over a granted SAF tree URI. */
class SafDocumentTree(
    private val context: Context,
    private val treeUri: Uri,
) : AgentDocumentTree {

    override suspend fun list(path: String): List<AgentDocumentEntry> = withContext(Dispatchers.IO) {
        val parentId = resolve(path, requireDirectory = true)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val entries = mutableListOf<AgentDocumentEntry>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext() && entries.size < MAX_ENTRIES) {
                val name = cursor.getString(0) ?: continue
                val isDirectory = cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR
                entries += AgentDocumentEntry(name, isDirectory, cursor.getLong(2))
            }
        } ?: throw AgentDocumentException("The granted folder is no longer reachable")
        entries.sortedWith(compareByDescending<AgentDocumentEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    override suspend fun read(path: String): String = withContext(Dispatchers.IO) {
        val documentId = resolve(path, requireDirectory = false)
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val bytes = context.contentResolver.openInputStream(documentUri)?.use { stream ->
            val buffer = ByteArray(MAX_READ_BYTES)
            var total = 0
            while (total < buffer.size) {
                val count = stream.read(buffer, total, buffer.size - total)
                if (count < 0) break
                total += count
            }
            buffer.copyOf(total)
        } ?: throw AgentDocumentException("Could not open \"$path\"")
        String(bytes, Charsets.UTF_8)
    }

    override suspend fun write(path: String, text: String): Unit = withContext(Dispatchers.IO) {
        val segments = splitPath(path)
        if (segments.isEmpty()) throw AgentDocumentException("A file path is required")
        val fileName = segments.last()
        val parentId = resolve(segments.dropLast(1).joinToString("/"), requireDirectory = true)
        val existing = findChild(parentId, fileName)
        val documentUri = if (existing != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existing)
        } else {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            runCatching {
                DocumentsContract.createDocument(context.contentResolver, parentUri, "text/plain", fileName)
            }.getOrNull() ?: throw AgentDocumentException("Could not create \"$path\" in the granted folder")
        }
        context.contentResolver.openOutputStream(documentUri, "wt")?.use { it.write(text.toByteArray()) }
            ?: throw AgentDocumentException("Could not write \"$path\"")
    }

    /** The document id for a relative path, resolving each segment by display name. */
    private fun resolve(path: String, requireDirectory: Boolean): String {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        var currentId = rootId
        for (segment in splitPath(path)) {
            val child = findChild(currentId, segment)
                ?: throw AgentDocumentException("No entry named \"$segment\" inside the granted folder")
            currentId = child
        }
        if (requireDirectory) {
            val mime = mimeTypeOf(currentId)
            if (mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                throw AgentDocumentException("\"$path\" is not a folder")
            }
        }
        return currentId
    }

    private fun findChild(parentId: String, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) == name) return cursor.getString(0)
            }
        }
        return null
    }

    private fun mimeTypeOf(documentId: String): String? {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        return context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private companion object {
        const val MAX_ENTRIES = 200
        const val MAX_READ_BYTES = 128_000
    }
}

/** A relative path split into safe segments; absolute paths and parent hops are refused. */
internal fun splitPath(path: String): List<String> {
    val segments = path.trim().trim('/').split('/').filter(String::isNotBlank)
    if (segments.any { it == "." || it == ".." }) return emptyList()
    return segments
}

/**
 * Reads, writes, and lists inside the folder the user granted Bram through the system picker.
 *
 * Deliberately separate from Bram's private file tools: this reaches the user's own documents, so
 * the folder is chosen explicitly, is visible in Capabilities, and can be revoked at any time —
 * every call re-checks the grant and answers "no folder" when it is gone. Reading is read-only;
 * writing is side-effecting and gated like any other change.
 */
class ListDocumentsTool(
    private val tree: () -> AgentDocumentTree?,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "list_documents",
        description = "List files and folders inside the folder the user granted Bram access to. " +
            "Pass path to look inside a subfolder.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "path":{"type":"string","description":"Subfolder to list, relative to the granted folder. Omit for the top level."}},
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = true,
    )

    override suspend fun execute(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return toolError("invalid_arguments", "Arguments were not valid JSON")
        val path = arguments.optString("path")
        val granted = tree() ?: return noFolderError()
        return runCatching {
            val entries = granted.list(path)
            JSONObject()
                .put(
                    "entries",
                    JSONArray().apply {
                        entries.forEach { entry ->
                            put(
                                JSONObject()
                                    .put("name", entry.name)
                                    .put("type", if (entry.isDirectory) "folder" else "file")
                                    .put("bytes", entry.bytes),
                            )
                        }
                    },
                )
                .put("count", entries.size)
                .toString()
        }.getOrElse { failure -> toolError("folder_error", failure.message ?: "Could not list the folder") }
    }
}

class ReadDocumentTool(
    private val tree: () -> AgentDocumentTree?,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "read_document",
        description = "Read a file from the folder the user granted Bram access to, truncated " +
            "beyond 64 KB.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "path":{"type":"string","description":"File path relative to the granted folder, e.g. notes/todo.txt."}},
             "required":["path"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = true,
        approvalScopeKeys = listOf("path"),
    )

    override suspend fun execute(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return toolError("invalid_arguments", "Arguments were not valid JSON")
        val path = arguments.optString("path").trim()
        if (path.isEmpty()) return toolError("invalid_path", "A file path is required")
        if (splitPath(path).isEmpty()) return toolError("invalid_path", "Path must stay inside the granted folder")
        val granted = tree() ?: return noFolderError()
        return runCatching {
            val text = granted.read(path)
            val truncated = text.length > MAX_READ_CHARS
            JSONObject()
                .put("path", path)
                .put("chars", text.length)
                .put("truncated", truncated)
                .put("text", if (truncated) text.take(MAX_READ_CHARS) + "\n…[truncated]…" else text)
                .toString()
        }.getOrElse { failure -> toolError("folder_error", failure.message ?: "Could not read the file") }
    }

    private companion object {
        const val MAX_READ_CHARS = 64_000
    }
}

class WriteDocumentTool(
    private val tree: () -> AgentDocumentTree?,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = "write_document",
        description = "Write a text file into the folder the user granted Bram access to, " +
            "replacing an existing file of the same name.",
        inputSchemaJson = """
            {"type":"object",
             "properties":{
               "path":{"type":"string","description":"File path relative to the granted folder, e.g. notes/todo.txt."},
               "text":{"type":"string","description":"The file's contents."}},
             "required":["path","text"],
             "additionalProperties":false}
        """.trimIndent(),
        readOnly = false,
        approvalScopeKeys = listOf("path"),
    )

    override suspend fun execute(argumentsJson: String): String {
        val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: return toolError("invalid_arguments", "Arguments were not valid JSON")
        val path = arguments.optString("path").trim()
        if (path.isEmpty()) return toolError("invalid_path", "A file path is required")
        if (splitPath(path).isEmpty()) return toolError("invalid_path", "Path must stay inside the granted folder")
        val granted = tree() ?: return noFolderError()
        return runCatching {
            granted.write(path, arguments.optString("text"))
            JSONObject().put("path", path).put("written", true).toString()
        }.getOrElse { failure -> toolError("folder_error", failure.message ?: "Could not write the file") }
    }
}

/** The answer when the user has not granted a folder (or has revoked it). */
private fun noFolderError(): String = toolError(
    "no_folder",
    "No folder is granted. The user can grant one in Capabilities, under Tools; continue without it.",
)
