package io.github.kurue.bram.platform.android

import android.content.Context
import io.github.kurue.bram.core.domain.ActiveSkill
import io.github.kurue.bram.core.domain.SkillActionOutcome
import io.github.kurue.bram.core.domain.SkillImportOutcome
import io.github.kurue.bram.core.domain.SkillLibrary
import io.github.kurue.bram.core.domain.SkillPackage
import io.github.kurue.bram.core.domain.SkillStore
import io.github.kurue.bram.core.domain.SkillVersion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Skills are small, bounded, and few, so they live in one JSON file like the task queue does:
 * one atomic write per change, version history kept in the record. The state machine itself is
 * [SkillLibrary] in core:domain, kept alive here and serialized after every change.
 */
class PersistentSkillStore(context: Context) : SkillStore {
    private val file = File(context.applicationContext.filesDir, SKILLS_FILE)
    private val library = SkillLibrary()
    @Volatile
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val restored = runCatching {
                if (file.isFile) {
                    val array = JSONArray(file.readText())
                    buildList {
                        for (index in 0 until array.length()) {
                            add(array.optJSONObject(index)?.toPackage() ?: continue)
                        }
                    }
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())
            library.restore(restored)
            loaded = true
        }
    }

    override suspend fun packages(): List<SkillPackage> = withContext(Dispatchers.IO) {
        ensureLoaded()
        library.packages()
    }

    override suspend fun activeSkills(): List<ActiveSkill> = withContext(Dispatchers.IO) {
        ensureLoaded()
        library.activeSkills()
    }

    override suspend fun importDocument(document: String): SkillImportOutcome = withContext(Dispatchers.IO) {
        ensureLoaded()
        val outcome = library.importDocument(document, System.currentTimeMillis())
        if (outcome is SkillImportOutcome.Imported) persist()
        outcome
    }

    override suspend fun activateDraft(skillId: String): SkillActionOutcome = withContext(Dispatchers.IO) {
        ensureLoaded()
        val outcome = library.activateDraft(skillId, System.currentTimeMillis())
        if (outcome is SkillActionOutcome.Ok) persist()
        outcome
    }

    override suspend fun rollback(skillId: String): SkillActionOutcome = withContext(Dispatchers.IO) {
        ensureLoaded()
        val outcome = library.rollback(skillId, System.currentTimeMillis())
        if (outcome is SkillActionOutcome.Ok) persist()
        outcome
    }

    override suspend fun remove(skillId: String): SkillActionOutcome = withContext(Dispatchers.IO) {
        ensureLoaded()
        val outcome = library.remove(skillId)
        if (outcome is SkillActionOutcome.Ok) persist()
        outcome
    }

    private fun persist() {
        val array = JSONArray()
        library.packages().forEach { array.put(it.toJson()) }
        runCatching {
            file.parentFile?.mkdirs()
            writeAtomically(file, array.toString())
        }
    }

    private fun SkillPackage.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("activeVersion", activeVersion)
        .put("draftVersion", draftVersion)
        .put("updatedAtEpochMillis", updatedAtEpochMillis)
        .put(
            "versions",
            JSONArray().apply { versions.forEach { put(it.toJson()) } },
        )

    private fun SkillVersion.toJson(): JSONObject = JSONObject()
        .put("version", version)
        .put("description", description)
        .put("instructions", instructions)
        .put("importedAtEpochMillis", importedAtEpochMillis)

    private fun JSONObject.toPackage(): SkillPackage = SkillPackage(
        id = optString("id"),
        name = optString("name"),
        activeVersion = if (isNull("activeVersion")) null else optString("activeVersion"),
        draftVersion = if (isNull("draftVersion")) null else optString("draftVersion"),
        updatedAtEpochMillis = optLong("updatedAtEpochMillis", System.currentTimeMillis()),
        versions = optJSONArray("versions")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    add(array.optJSONObject(index)?.toVersion() ?: continue)
                }
            }
        }.orEmpty(),
    )

    private fun JSONObject.toVersion(): SkillVersion = SkillVersion(
        version = optString("version"),
        description = optString("description"),
        instructions = optString("instructions"),
        importedAtEpochMillis = optLong("importedAtEpochMillis", System.currentTimeMillis()),
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
        const val SKILLS_FILE = "skills.json"
        val lock = Any()
    }
}
