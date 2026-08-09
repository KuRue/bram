package io.github.kurue.bram.platform.android

import android.content.Context
import io.github.kurue.bram.core.domain.Automation
import io.github.kurue.bram.core.domain.AutomationStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Automations live in one small JSON file, like tasks and skills: atomic writes, loaded lazily on
 * first use. The runner recomputes `nextRunAtEpochMillis` whenever schedules change, so the file
 * holds whatever the runner last computed rather than deriving it on read.
 */
class PersistentAutomationStore(context: Context) : AutomationStore {
    private val file = File(context.applicationContext.filesDir, AUTOMATIONS_FILE)

    override suspend fun automations(): List<Automation> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.toAutomation()
            }
        }.getOrDefault(emptyList())
    }

    override suspend fun save(automation: Automation) = withContext(Dispatchers.IO) {
        val existing = automations().associateByTo(linkedMapOf()) { it.id }
        existing[automation.id] = automation
        persist(existing.values.toList())
    }

    override suspend fun remove(automationId: String) = withContext(Dispatchers.IO) {
        persist(automations().filterNot { it.id == automationId })
    }

    private fun persist(automations: List<Automation>) {
        val array = JSONArray()
        automations.forEach { array.put(it.toJson()) }
        runCatching {
            file.parentFile?.mkdirs()
            writeAtomically(file, array.toString())
        }
    }

    private fun Automation.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("cron", cron)
        .put("prompt", prompt)
        .put("enabled", enabled)
        .put("lastRunAtEpochMillis", lastRunAtEpochMillis)
        .put("nextRunAtEpochMillis", nextRunAtEpochMillis)
        .put("updatedAtEpochMillis", updatedAtEpochMillis)

    private fun JSONObject.toAutomation(): Automation = Automation(
        id = optString("id"),
        name = optString("name"),
        cron = optString("cron"),
        prompt = optString("prompt"),
        enabled = optBoolean("enabled", true),
        lastRunAtEpochMillis = if (isNull("lastRunAtEpochMillis")) null else optLong("lastRunAtEpochMillis"),
        nextRunAtEpochMillis = if (isNull("nextRunAtEpochMillis")) null else optLong("nextRunAtEpochMillis"),
        updatedAtEpochMillis = optLong("updatedAtEpochMillis", System.currentTimeMillis()),
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
        const val AUTOMATIONS_FILE = "automations.json"
    }
}
