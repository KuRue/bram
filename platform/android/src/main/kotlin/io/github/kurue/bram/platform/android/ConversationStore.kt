package io.github.kurue.bram.platform.android

import android.content.Context
import io.github.kurue.bram.core.domain.AgentActivity
import io.github.kurue.bram.core.domain.ConversationId
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.ConversationSummary
import io.github.kurue.bram.core.domain.MessageId
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.PermissionMode
import io.github.kurue.bram.core.domain.PrivacyClass
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores conversations on disk so chat survives the app being closed or killed.
 *
 * One file per conversation rather than a single blob: a conversation grows without bound, and
 * rewriting every conversation to append one message would get slower the longer Bram is used.
 * Listing reads a small index instead of parsing every conversation, and the index is rebuilt from
 * the files themselves if it is missing or has drifted, so the files stay the source of truth.
 */
class ConversationStore(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "conversations")
    private val indexFile = File(directory, INDEX_FILE)

    suspend fun list(): List<ConversationSummary> = withContext(Dispatchers.IO) {
        readIndex().sortedByDescending(ConversationSummary::updatedAtEpochMillis)
    }

    suspend fun load(id: ConversationId): List<ConversationMessage> = withContext(Dispatchers.IO) {
        val file = conversationFile(id)
        if (!file.isFile) return@withContext emptyList()
        runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("messages") ?: JSONArray()
            (0 until array.length()).map { index -> array.getJSONObject(index).toMessage() }
        }.getOrDefault(emptyList())
    }

    /**
     * Writes the conversation and refreshes its index entry. The title is derived from the first
     * user message when the caller has not set one, because an untitled list is hard to scan.
     */
    suspend fun save(
        id: ConversationId,
        messages: List<ConversationMessage>,
        title: String? = null,
    ): ConversationSummary = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val resolvedTitle = title?.takeIf(String::isNotBlank)
            ?: existingTitle(id)
            ?: deriveTitle(messages)
        val updatedAt = messages.maxOfOrNull(ConversationMessage::createdAtEpochMillis)
            ?: System.currentTimeMillis()

        val root = JSONObject()
            .put("id", id.value)
            .put("title", resolvedTitle)
            .put("updatedAtEpochMillis", updatedAt)
            .put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
        writeAtomically(conversationFile(id), root.toString())

        val summary = ConversationSummary(id, resolvedTitle, updatedAt, messages.size)
        writeIndex(readIndex().filterNot { it.id == id } + summary)
        summary
    }

    suspend fun delete(id: ConversationId) = withContext(Dispatchers.IO) {
        conversationFile(id).delete()
        writeIndex(readIndex().filterNot { it.id == id })
    }

    /** The conversation's tool-permission mode, defaulting to AUTO for a fresh or pre-existing file. */
    suspend fun permissionMode(id: ConversationId): PermissionMode = withContext(Dispatchers.IO) {
        val file = conversationFile(id)
        if (!file.isFile) return@withContext PermissionMode.AUTO
        runCatching {
            PermissionMode.fromWire(JSONObject(file.readText()).optString("permissionMode"))
        }.getOrDefault(PermissionMode.AUTO)
    }

    /** Rewrites only the mode field, leaving messages untouched; called when the user changes it. */
    suspend fun setPermissionMode(id: ConversationId, mode: PermissionMode) {
        withContext(Dispatchers.IO) {
            val file = conversationFile(id)
            if (!file.isFile) return@withContext
            runCatching {
                val root = JSONObject(file.readText())
                root.put("permissionMode", mode.wire)
                writeAtomically(file, root.toString())
            }
        }
    }

    /**
     * The conversation's privacy class, defaulting to STANDARD for a fresh or pre-existing file.
     * Local-only means its content never leaves the device, so a conversation saved before the
     * routing feature existed still routes like today.
     */
    suspend fun privacyClass(id: ConversationId): PrivacyClass = withContext(Dispatchers.IO) {
        val file = conversationFile(id)
        if (!file.isFile) return@withContext PrivacyClass.STANDARD
        runCatching {
            PrivacyClass.fromWire(JSONObject(file.readText()).optString("privacyClass"))
        }.getOrDefault(PrivacyClass.STANDARD)
    }

    suspend fun setPrivacyClass(id: ConversationId, privacyClass: PrivacyClass) {
        withContext(Dispatchers.IO) {
            val file = conversationFile(id)
            if (!file.isFile) return@withContext
            runCatching {
                val root = JSONObject(file.readText())
                root.put("privacyClass", privacyClass.wire)
                writeAtomically(file, root.toString())
            }
        }
    }

    fun newId(): ConversationId = ConversationId(UUID.randomUUID().toString())

    private fun existingTitle(id: ConversationId): String? =
        readIndex().firstOrNull { it.id == id }?.title?.takeIf(String::isNotBlank)

    private fun deriveTitle(messages: List<ConversationMessage>): String {
        val firstUserLine = messages.firstOrNull { it.role == MessageRole.USER }
            ?.content
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?: return "New conversation"
        return if (firstUserLine.length <= TITLE_LIMIT) {
            firstUserLine
        } else {
            firstUserLine.take(TITLE_LIMIT).trimEnd() + "…"
        }
    }

    private fun conversationFile(id: ConversationId) = File(directory, "${id.value}.json")

    /** Falls back to scanning the conversation files so a lost index is not a lost history. */
    private fun readIndex(): List<ConversationSummary> {
        if (!directory.isDirectory) return emptyList()
        val fromIndex = runCatching {
            if (!indexFile.isFile) return@runCatching null
            val array = JSONArray(indexFile.readText())
            (0 until array.length()).map { index ->
                val entry = array.getJSONObject(index)
                ConversationSummary(
                    id = ConversationId(entry.getString("id")),
                    title = entry.optString("title"),
                    updatedAtEpochMillis = entry.optLong("updatedAtEpochMillis"),
                    messageCount = entry.optInt("messageCount"),
                )
            }
        }.getOrNull()
        if (fromIndex != null) return fromIndex

        val rebuilt = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") && it.name != INDEX_FILE }
            .mapNotNull { file ->
                runCatching {
                    val root = JSONObject(file.readText())
                    ConversationSummary(
                        id = ConversationId(root.getString("id")),
                        title = root.optString("title"),
                        updatedAtEpochMillis = root.optLong("updatedAtEpochMillis"),
                        messageCount = root.optJSONArray("messages")?.length() ?: 0,
                    )
                }.getOrNull()
            }
        if (rebuilt.isNotEmpty()) writeIndex(rebuilt)
        return rebuilt
    }

    private fun writeIndex(summaries: List<ConversationSummary>) {
        directory.mkdirs()
        val array = JSONArray()
        summaries.forEach { summary ->
            array.put(
                JSONObject()
                    .put("id", summary.id.value)
                    .put("title", summary.title)
                    .put("updatedAtEpochMillis", summary.updatedAtEpochMillis)
                    .put("messageCount", summary.messageCount),
            )
        }
        runCatching { writeAtomically(indexFile, array.toString()) }
    }

    /** A half-written conversation would be unreadable, so replace the file only once complete. */
    private fun writeAtomically(target: File, contents: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(contents)
        if (!temporary.renameTo(target)) {
            target.writeText(contents)
            temporary.delete()
        }
    }

    private fun ConversationMessage.toJson(): JSONObject = JSONObject()
        .put("id", id.value)
        .put("role", role.name)
        .put("content", content)
        .put("createdAtEpochMillis", createdAtEpochMillis)
        .put(
            "activity",
            JSONArray().apply {
                activity.forEach { entry ->
                    put(
                        when (entry) {
                            is AgentActivity.Thinking -> JSONObject()
                                .put("kind", "thinking")
                                .put("text", entry.text)
                                .put("durationMillis", entry.durationMillis)
                            is AgentActivity.ToolInvocation -> JSONObject()
                                .put("kind", "tool")
                                .put("id", entry.id)
                                .put("name", entry.name)
                                .put("argumentsJson", entry.argumentsJson)
                                .put("result", entry.result)
                                .put("failed", entry.failed)
                        },
                    )
                }
            },
        )

    private fun JSONObject.toMessage(): ConversationMessage = ConversationMessage(
        id = MessageId(getString("id")),
        role = runCatching { MessageRole.valueOf(getString("role")) }.getOrDefault(MessageRole.USER),
        content = optString("content"),
        createdAtEpochMillis = optLong("createdAtEpochMillis", System.currentTimeMillis()),
        activity = optJSONArray("activity")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                when (entry.optString("kind")) {
                    "thinking" -> AgentActivity.Thinking(
                        text = entry.optString("text"),
                        durationMillis = entry.optLong("durationMillis"),
                    )
                    "tool" -> AgentActivity.ToolInvocation(
                        id = entry.optString("id"),
                        name = entry.optString("name"),
                        argumentsJson = entry.optString("argumentsJson"),
                        result = if (entry.isNull("result")) null else entry.optString("result"),
                        failed = entry.optBoolean("failed"),
                    )
                    else -> null
                }
            }
        }.orEmpty(),
    )

    private companion object {
        const val INDEX_FILE = "index.json"
        const val TITLE_LIMIT = 60
    }
}
