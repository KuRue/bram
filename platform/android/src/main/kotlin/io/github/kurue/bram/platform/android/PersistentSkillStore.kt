package io.github.kurue.bram.platform.android

import android.content.Context
import io.github.kurue.bram.core.domain.ActiveSkill
import io.github.kurue.bram.core.domain.SkillActionOutcome
import io.github.kurue.bram.core.domain.SkillImportOutcome
import io.github.kurue.bram.core.domain.SkillLibrary
import io.github.kurue.bram.core.domain.SkillOrigin
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
 *
 * The file carries a schema version (`{"schemaVersion":2,"skills":[…]}`). Version 1 was a bare
 * array and is still read; anything unreadable is quarantined — renamed aside rather than silently
 * discarded — and the store starts empty, so one bad byte cannot brick every skill interaction.
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
                if (file.isFile) readPackages(file.readText()) else emptyList()
            }.getOrElse { failure ->
                quarantine(failure)
                emptyList()
            }
            library.restore(restored)
            loaded = true
        }
    }

    private fun readPackages(text: String): List<SkillPackage> {
        if (text.isBlank()) return emptyList()
        val trimmed = text.trimStart()
        // Version 1 is a bare array; version 2 wraps it with a schema version so future formats
        // have somewhere to declare themselves.
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val root = JSONObject(trimmed)
            val declared = root.optInt("schemaVersion", 0)
            if (declared > CURRENT_SCHEMA_VERSION) {
                throw IllegalStateException("skills.json schema version $declared is newer than this app understands")
            }
            root.optJSONArray("skills") ?: JSONArray()
        }
        return buildList {
            for (index in 0 until array.length()) {
                add(array.optJSONObject(index)?.toPackage() ?: continue)
            }
        }
    }

    private fun quarantine(failure: Throwable) {
        val aside = File(file.parentFile, "$SKILLS_FILE.corrupt-${System.currentTimeMillis()}")
        runCatching { file.renameTo(aside) }
        android.util.Log.w(
            "BramSkills",
            "skills.json could not be read (${failure.message}); moved aside to ${aside.name}",
        )
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

    override suspend fun proposeDraft(document: String): SkillImportOutcome = withContext(Dispatchers.IO) {
        ensureLoaded()
        val outcome = library.proposeDraft(document, System.currentTimeMillis())
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

    override suspend fun disable(skillId: String): SkillActionOutcome = withContext(Dispatchers.IO) {
        ensureLoaded()
        val outcome = library.disable(skillId, System.currentTimeMillis())
        if (outcome is SkillActionOutcome.Ok) persist()
        outcome
    }

    override suspend fun enable(skillId: String): SkillActionOutcome = withContext(Dispatchers.IO) {
        ensureLoaded()
        val outcome = library.enable(skillId, System.currentTimeMillis())
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
        val skills = JSONArray()
        library.packages().forEach { skills.put(it.toJson()) }
        val root = JSONObject()
            .put("schemaVersion", CURRENT_SCHEMA_VERSION)
            .put("skills", skills)
        runCatching {
            file.parentFile?.mkdirs()
            writeAtomically(file, root.toString())
        }
    }

    private fun SkillPackage.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("activeVersion", activeVersion)
        .put("draftVersion", draftVersion)
        .put("updatedAtEpochMillis", updatedAtEpochMillis)
        .put("disabled", disabled)
        .put(
            "versions",
            JSONArray().apply { versions.forEach { put(it.toJson()) } },
        )

    private fun SkillVersion.toJson(): JSONObject = JSONObject()
        .put("version", version)
        .put("description", description)
        .put("instructions", instructions)
        .put("importedAtEpochMillis", importedAtEpochMillis)
        .put("author", author)
        .put("origin", origin.name)
        .put("tools", JSONArray(tools.toList()))
        .put("permissions", JSONArray(permissions.toList()))
        .apply { approvedAtEpochMillis?.let { put("approvedAtEpochMillis", it) } }

    private fun JSONObject.toPackage(): SkillPackage = SkillPackage(
        id = optString("id"),
        name = optString("name"),
        activeVersion = if (isNull("activeVersion")) null else optString("activeVersion"),
        draftVersion = if (isNull("draftVersion")) null else optString("draftVersion"),
        updatedAtEpochMillis = optLong("updatedAtEpochMillis", System.currentTimeMillis()),
        disabled = optBoolean("disabled", false),
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
        author = optString("author"),
        origin = runCatching { SkillOrigin.valueOf(optString("origin", SkillOrigin.USER.name)) }
            .getOrDefault(SkillOrigin.USER),
        tools = optJSONArray("tools")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
        }.orEmpty(),
        permissions = optJSONArray("permissions")?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
        }.orEmpty(),
        // A version stored before approval tracking existed was imported by the user directly, so
        // its import time is the closest honest record of that approval.
        approvedAtEpochMillis = when {
            has("approvedAtEpochMillis") && !isNull("approvedAtEpochMillis") -> optLong("approvedAtEpochMillis")
            !has("origin") -> optLong("importedAtEpochMillis", System.currentTimeMillis())
            else -> null
        },
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
        const val CURRENT_SCHEMA_VERSION = 2
        val lock = Any()
    }
}
