package io.github.kurue.bram.app

import android.content.Context
import io.github.kurue.bram.core.domain.ConversationId
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the task queue as one small JSON file. Tasks are few and bounded, so unlike
 * conversations there is no need for a per-task file; one atomic write keeps them simple and
 * crash-safe.
 */
class AgentTaskStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, TASKS_FILE)

    suspend fun load(): List<AgentTask> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.toTask()
            }
        }.getOrDefault(emptyList())
    }

    suspend fun save(tasks: List<AgentTask>) = withContext(Dispatchers.IO) {
        val array = JSONArray()
        tasks.forEach { task -> array.put(task.toJson()) }
        runCatching {
            file.parentFile?.mkdirs()
            writeAtomically(file, array.toString())
        }
    }

    private fun AgentTask.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("displayName", displayName)
        .put("prompt", prompt)
        .put("conversationId", conversationId.value)
        .put("createdAtEpochMillis", createdAtEpochMillis)
        .put("scheduledAtEpochMillis", scheduledAtEpochMillis)
        .put("state", state.wire)
        .put("startedAtEpochMillis", startedAtEpochMillis)
        .put("finishedAtEpochMillis", finishedAtEpochMillis)
        .put("resultSummary", resultSummary)
        .put("error", error)
        .put(
            "activityLog",
            JSONArray().apply { activityLog.forEach { put(it) } },
        )

    private fun JSONObject.toTask(): AgentTask = AgentTask(
        id = optString("id"),
        displayName = optString("displayName"),
        prompt = optString("prompt"),
        conversationId = ConversationId(optString("conversationId")),
        createdAtEpochMillis = optLong("createdAtEpochMillis", System.currentTimeMillis()),
        scheduledAtEpochMillis = if (isNull("scheduledAtEpochMillis")) {
            null
        } else {
            optLong("scheduledAtEpochMillis")
        },
        state = TaskState.entries.firstOrNull { it.wire == optString("state") } ?: TaskState.QUEUED,
        startedAtEpochMillis = if (isNull("startedAtEpochMillis")) null else optLong("startedAtEpochMillis"),
        finishedAtEpochMillis = if (isNull("finishedAtEpochMillis")) null else optLong("finishedAtEpochMillis"),
        resultSummary = if (isNull("resultSummary")) null else optString("resultSummary"),
        error = if (isNull("error")) null else optString("error"),
        activityLog = optJSONArray("activityLog")?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optString(index, "").takeIf(String::isNotBlank) }
        }.orEmpty(),
    )

    private fun writeAtomically(target: File, contents: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(contents)
        if (!temporary.renameTo(target)) {
            target.writeText(contents)
            temporary.delete()
        }
    }

    private companion object {
        const val TASKS_FILE = "tasks.json"
    }
}
