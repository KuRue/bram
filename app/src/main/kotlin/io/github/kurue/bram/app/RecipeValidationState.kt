package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class RecipeEnvironmentChanged(message: String) : IllegalStateException(message)

data class RecipeValidationRecord(
    val key: String,
    val verdict: RecipeVerdict,
    val detail: String,
    val recordedAt: Long = System.currentTimeMillis(),
    val incumbentMillis: Long = 0,
    val proposedMillis: Long = 0,
)

/** Bounded device-local evidence, separate from downloaded suggestions. No record grants a pass. */
class RecipeValidationHistory(private val file: File) {
    @Synchronized fun read(): List<RecipeValidationRecord> {
        if (!file.exists()) return emptyList()
        require(file.length() <= 256 * 1024) { "Recipe validation history is too large" }
        val values = JSONArray(file.readText())
        require(values.length() <= 128) { "Too many recipe validation records" }
        return (0 until values.length()).map { index ->
            val obj = values.getJSONObject(index)
            RecipeValidationRecord(
                obj.getString("key"), RecipeVerdict.valueOf(obj.getString("verdict")), obj.getString("detail"),
                obj.getLong("at"), obj.optLong("incumbentMillis"), obj.optLong("proposedMillis"),
            )
        }
    }

    /** A process that died mid-check never leaves evidence that could be mistaken for approval. */
    @Synchronized fun recover(): List<RecipeValidationRecord> {
        val records = read()
        val recovered = records.map { record ->
            if (record.verdict == RecipeVerdict.CHECKING) record.copy(
                verdict = RecipeVerdict.INTERRUPTED,
                detail = "Validation was interrupted. Only a complete check can save settings; review the profile and validate again.",
            ) else record
        }
        if (records != recovered) write(recovered)
        return recovered
    }

    @Synchronized fun save(record: RecipeValidationRecord): List<RecipeValidationRecord> {
        val records = (listOf(record.copy(detail = record.detail.take(800))) + read().filterNot { it.key == record.key }).take(128)
        write(records)
        return records
    }

    private fun write(records: List<RecipeValidationRecord>) {
        val array = JSONArray()
        records.forEach { record -> array.put(JSONObject()
            .put("key", record.key).put("verdict", record.verdict.name).put("detail", record.detail)
            .put("at", record.recordedAt).put("incumbentMillis", record.incumbentMillis).put("proposedMillis", record.proposedMillis)) }
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256 * 1024) { "Recipe validation history exceeds its size limit" }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            java.io.FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            java.nio.channels.FileChannel.open(file.parentFile!!.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
        } finally { temporary.delete() }
    }
}

data class RecipePreview(
    val key: String,
    val sourceName: String,
    val sourceId: String,
    val origin: String,
    val revision: String,
    val snapshotPin: String,
    val recipeHash: String,
    val official: Boolean,
    val candidate: RecipeCandidate,
    val proposed: ModelProfile,
    val blocked: String?,
    val record: RecipeValidationRecord?,
) {
    val provenance: String get() = "${if (official) "Bundled" else "External"}: $sourceName ($sourceId), $origin, revision $revision, index SHA-256 $snapshotPin; " +
        "recipe ${candidate.recipe.id} v${candidate.recipe.version}, SHA-256 $recipeHash"
}

/** Only accepted/current snapshots participate. An unaccepted review never suggests live settings. */
fun recipePreviews(state: AppUiState, profileId: String): List<RecipePreview> {
    val profile = state.profiles.firstOrNull { it.id == profileId } ?: return emptyList()
    val model = state.localModels.firstOrNull { it.id == profile.modelId } ?: return emptyList()
    val device = state.recipeDeviceIdentity ?: return emptyList()
    val modelIdentity = RecipeModelIdentity(model.architecture, model.quantization, model.fileSizeBytes,
        model.sha256.takeIf { it.isNotBlank() && model.parts.isEmpty() }, profile.contextTokens)
    val snapshots = listOfNotNull(state.recipeSources.bundled?.let { it to "bundled" }) + state.recipeSources.sources.map { it.current to it.url }
    return snapshots.flatMap { (snapshot, origin) ->
        RecipeMatcher.candidates(snapshot.recipes, device, modelIdentity, profile).map { candidate ->
            val proposed = candidate.recipe.proposal.applyTo(profile)
            val digest = snapshot.index.recipes.first { it.id == candidate.recipe.id }.sha256
            val key = recipeSha256(listOf(origin, snapshot.index.source.kind.wire, snapshot.pin, digest, model.sha256, state.measurementFingerprint,
                profile.recipeConfigurationIdentity(), proposed.recipeConfigurationIdentity(), "recipe-check-v1")
                .joinToString("\n").toByteArray(Charsets.UTF_8))
            val blocked = when {
                model.sha256.isBlank() || model.parts.isNotEmpty() -> "Validation requires an exact content identity for a single GGUF file"
                !state.recipeRuntimeAvailable -> "The local GGUF runtime is unavailable on this device"
                device.engineCommit.isBlank() || state.measurementFingerprint.isBlank() -> "The local runtime build could not be identified"
                candidate.changes.isEmpty() -> "These settings already match the profile"
                else -> recipeEligibility(proposed, model, state.deviceProfile?.cpuCoreCount ?: 1, device.availableBackends)
                    ?: recipeEligibility(profile, model, state.deviceProfile?.cpuCoreCount ?: 1, device.availableBackends)
            }
            RecipePreview(key, snapshot.index.source.name, snapshot.index.source.id, origin, snapshot.index.revision,
                snapshot.pin, digest, snapshot.index.source.kind == RecipeSourceKind.OFFICIAL,
                candidate, proposed, blocked, state.recipeValidationRecords.firstOrNull { it.key == key })
        }
    }.sortedWith(compareBy<RecipePreview>({ it.candidate.fidelity != RecipeFidelity.EXACT }, { !it.official }, { it.candidate.recipe.id }))
}
