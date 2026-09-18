package io.github.kurue.bram.runtime.llamacpp

import android.content.Context
import io.github.kurue.bram.core.domain.BackendMeasurement
import io.github.kurue.bram.core.domain.DimensionTuneNote
import io.github.kurue.bram.core.domain.FlashAttentionMode
import io.github.kurue.bram.core.domain.HexFlags
import io.github.kurue.bram.core.domain.KvCacheType
import io.github.kurue.bram.core.domain.LoadMode
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.core.domain.ModelProfile
import io.github.kurue.bram.core.domain.SamplerSettings
import io.github.kurue.bram.core.domain.ThreadPriority
import io.github.kurue.bram.core.domain.TuneCandidateResult
import io.github.kurue.bram.core.domain.TuningDimension
import io.github.kurue.bram.core.domain.canonicalCpuMask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The saved ways of running the imported models.
 *
 * Stored beside the model catalog rather than inside it: a profile outlives any single import, and
 * several can point at the same file. Persistence is the same shape as [LocalModelStore] — one JSON
 * array in preferences — because the volume is a handful of small records and a database would earn
 * nothing.
 */
class ModelProfileStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    suspend fun list(): List<ModelProfile> = withContext(Dispatchers.IO) {
        decode(preferences.getString(KEY_PROFILES, null))
            .sortedBy(ModelProfile::createdAtEpochMillis)
    }

    suspend fun forModel(modelId: ModelId): List<ModelProfile> =
        list().filter { it.modelId == modelId }

    suspend fun find(profileId: String): ModelProfile? = list().firstOrNull { it.id == profileId }

    /** The profile Bram last ran, so a relaunch can restore the whole configuration, not just the file. */
    suspend fun lastUsedProfileId(): String? = withContext(Dispatchers.IO) {
        preferences.getString(KEY_LAST_USED, null)
    }

    suspend fun setLastUsedProfileId(profileId: String?) = withContext(Dispatchers.IO) {
        preferences.edit().apply {
            if (profileId == null) remove(KEY_LAST_USED) else putString(KEY_LAST_USED, profileId)
        }.apply()
    }

    suspend fun save(profile: ModelProfile): ModelProfile = withContext(Dispatchers.IO) {
        val context = profile.contextTokens.coerceAtLeast(256)
        val batch = profile.batchTokens.coerceIn(0, context)
        val sanitized = profile
            .sanitizedRuntime()
            .copy(
                sampler = profile.sampler.sanitized(),
                batchTokens = batch,
                ubatchTokens = profile.ubatchTokens.coerceIn(0, batch),
            )
        val existing = decode(preferences.getString(KEY_PROFILES, null))
        val merged = existing.filterNot { it.id == sanitized.id } + sanitized
        write(merged)
        sanitized
    }

    suspend fun delete(profileId: String) = withContext(Dispatchers.IO) {
        val remaining = decode(preferences.getString(KEY_PROFILES, null))
            .filterNot { it.id == profileId }
        write(remaining)
        if (preferences.getString(KEY_LAST_USED, null) == profileId) {
            preferences.edit().remove(KEY_LAST_USED).apply()
        }
    }

    /**
     * Makes sure every imported model can be run without the user having to create a profile first.
     *
     * Also the migration path: a model imported before profiles existed carries its context size,
     * backend, and reasoning choice as fields, and those become its default profile rather than
     * being silently dropped. Models that already have a profile are left alone, so this is safe to
     * call on every catalog load.
     */
    suspend fun ensureDefaults(models: List<LocalModelRecord>): List<ModelProfile> =
        withContext(Dispatchers.IO) {
            val existing = decode(preferences.getString(KEY_PROFILES, null))
            val covered = existing.map { it.modelId }.toSet()
            val added = models.filterNot { it.id in covered }.map(ModelProfile::defaultFor)
            if (added.isNotEmpty()) write(existing + added)
            (existing + added).sortedBy(ModelProfile::createdAtEpochMillis)
        }

    /** Drops profiles whose model is gone, so deleting an import does not leave them behind. */
    suspend fun removeOrphans(models: List<LocalModelRecord>): Int = withContext(Dispatchers.IO) {
        val known = models.map { it.id }.toSet()
        val existing = decode(preferences.getString(KEY_PROFILES, null))
        val kept = existing.filter { it.modelId in known }
        if (kept.size != existing.size) write(kept)
        existing.size - kept.size
    }

    private fun write(profiles: List<ModelProfile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        // commit(), not apply(): a profile created by a conversion (or a tuning run) must be on
        // disk before the process can be killed, or a force-stop right after silently loses it.
        check(preferences.edit().putString(KEY_PROFILES, array.toString()).commit()) {
            "Could not persist the model profiles"
        }
    }

    private fun decode(raw: String?): List<ModelProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { array.getJSONObject(index).toProfile() }.getOrNull()
        }
    }

    private fun ModelProfile.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("modelId", modelId.value)
        .put("contextTokens", contextTokens)
        .put("backendId", backendId)
        .put("thinkingEnabled", thinkingEnabled)
        .put("flashAttention", flashAttention.wire)
        .put("kvCacheType", kvCacheType.wire)
        .put("batchTokens", batchTokens)
        .put("ubatchTokens", ubatchTokens)
        .put("threads", threads)
        .put("cpuMask", cpuMask)
        .put("cpuStrict", cpuStrict)
        .put("poll", poll)
        .put("threadPriority", threadPriority.wire)
        .put("loadMode", loadMode.wire)
        .put("hexUseHmx", hexFlags.useHmx)
        .put("hexDisableNhvx", hexFlags.disableNhvx)
        .put("hexHostBuf", hexFlags.hostBuf)
        .put("hexOpBatch", hexFlags.opBatch)
        .put("hexNDev", hexFlags.nDev)
        .put("streamExperts", streamExperts)
        .put("streamCacheMb", streamCacheMb)
        .put("streamDenseAnon", streamDenseAnon)
        .put("streamOverlap", streamOverlap)
        .put("temperature", sampler.temperature.toDouble())
        .put("topP", sampler.topP.toDouble())
        .put("topK", sampler.topK)
        .put("repeatPenalty", sampler.repeatPenalty.toDouble())
        .put("repeatLastTokens", sampler.repeatLastTokens)
        .put("systemPrompt", systemPrompt)
        .put("createdAtEpochMillis", createdAtEpochMillis)
        .put(
            "measurements",
            JSONArray().also { array ->
                measurements.forEach { measurement ->
                    array.put(
                        JSONObject()
                            .put("backendId", measurement.backendId)
                            .put("label", measurement.label)
                            .put("agrees", measurement.agrees)
                            .put("agreement", measurement.agreement)
                            .put("speedup", measurement.speedup)
                            .put("promptTokPerSec", measurement.promptTokPerSec)
                            .put("decodeTokPerSec", measurement.decodeTokPerSec),
                    )
                }
            },
        )
        .put("autoConfiguredNote", autoConfiguredNote)
        .put("autoConfiguredAtEpochMillis", autoConfiguredAtEpochMillis)
        .put("batchTuneNote", batchTuneNote)
        .put("batchTunedAtEpochMillis", batchTunedAtEpochMillis)
        .put(
            "tuning",
            JSONArray().also { array ->
                tuning.forEach { note ->
                    array.put(
                        JSONObject()
                            .put("dimension", note.dimension.wire)
                            .put("chosen", note.chosen)
                            .put("note", note.note)
                            .put("measuredAtEpochMillis", note.measuredAtEpochMillis)
                            .put(
                                "results",
                                JSONArray().also { results ->
                                    note.results.forEach { result ->
                                        results.put(
                                            JSONObject()
                                                .put("label", result.label)
                                                .put("promptTokPerSec", result.promptTokPerSec)
                                                .put("decodeTokPerSec", result.decodeTokPerSec)
                                                .put("agreed", result.agreed)
                                                .put("timedOut", result.timedOut)
                                                .put("winner", result.winner),
                                        )
                                    }
                                },
                            ),
                    )
                }
            },
        )
        .put("measuredFingerprint", measuredFingerprint)
        .put("isDefault", isDefault)

    private fun JSONObject.toProfile(): ModelProfile {
        val fallback = SamplerSettings()
        val storedContext = optInt("contextTokens", 4_096)
        val storedBatch = optInt("batchTokens", 0).coerceIn(0, storedContext)
        return ModelProfile(
            id = getString("id"),
            name = getString("name"),
            modelId = ModelId(getString("modelId")),
            contextTokens = storedContext,
            backendId = optString("backendId"),
            thinkingEnabled = optBoolean("thinkingEnabled"),
            flashAttention = FlashAttentionMode.fromWire(optString("flashAttention")),
            kvCacheType = KvCacheType.fromWire(optString("kvCacheType")),
            batchTokens = storedBatch,
            ubatchTokens = optInt("ubatchTokens", 0).coerceIn(0, storedBatch),
            threads = optInt("threads", 0).coerceIn(0, 64),
            cpuMask = canonicalCpuMask(optString("cpuMask")),
            cpuStrict = optBoolean("cpuStrict") && canonicalCpuMask(optString("cpuMask")).isNotEmpty(),
            poll = optInt("poll", -1).coerceIn(-1, 100),
            threadPriority = ThreadPriority.fromWire(optString("threadPriority")),
            loadMode = LoadMode.fromWire(optString("loadMode")),
            hexFlags = HexFlags(
                useHmx = optBoolean("hexUseHmx"),
                disableNhvx = optBoolean("hexDisableNhvx"),
                hostBuf = optBoolean("hexHostBuf"),
                opBatch = optInt("hexOpBatch", 0).coerceIn(0, 0xF),
                nDev = optInt("hexNDev", 0).coerceIn(0, 8),
            ).sanitized(),
            streamExperts = optBoolean("streamExperts", false),
            streamCacheMb = optInt("streamCacheMb", 0).coerceAtLeast(0),
            streamDenseAnon = optBoolean("streamDenseAnon", false),
            streamOverlap = optBoolean("streamOverlap", false),
            sampler = SamplerSettings(
                temperature = optDouble("temperature", fallback.temperature.toDouble()).toFloat(),
                topP = optDouble("topP", fallback.topP.toDouble()).toFloat(),
                topK = optInt("topK", fallback.topK),
                repeatPenalty = optDouble("repeatPenalty", fallback.repeatPenalty.toDouble()).toFloat(),
                repeatLastTokens = optInt("repeatLastTokens", fallback.repeatLastTokens),
            ).sanitized(),
            systemPrompt = optString("systemPrompt"),
            createdAtEpochMillis = optLong("createdAtEpochMillis", System.currentTimeMillis()),
            measurements = optJSONArray("measurements")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { entry ->
                        BackendMeasurement(
                            backendId = entry.optString("backendId"),
                            label = entry.optString("label"),
                            agrees = entry.optBoolean("agrees"),
                            agreement = entry.optDouble("agreement", 0.0),
                            speedup = entry.optDouble("speedup", 0.0),
                            promptTokPerSec = entry.optDouble("promptTokPerSec", 0.0),
                            decodeTokPerSec = entry.optDouble("decodeTokPerSec", 0.0),
                        )
                    }
                }
            }.orEmpty(),
            autoConfiguredNote = optString("autoConfiguredNote"),
            autoConfiguredAtEpochMillis = optLong("autoConfiguredAtEpochMillis", 0L),
            batchTuneNote = optString("batchTuneNote"),
            batchTunedAtEpochMillis = optLong("batchTunedAtEpochMillis", 0L),
            tuning = optJSONArray("tuning")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { entry ->
                        DimensionTuneNote(
                            dimension = TuningDimension.fromWire(entry.optString("dimension"))
                                ?: return@let null,
                            chosen = entry.optString("chosen"),
                            note = entry.optString("note"),
                            measuredAtEpochMillis = entry.optLong("measuredAtEpochMillis", 0L),
                            results = entry.optJSONArray("results")?.let { results ->
                                (0 until results.length()).mapNotNull { resultIndex ->
                                    results.optJSONObject(resultIndex)?.let { result ->
                                        TuneCandidateResult(
                                            label = result.optString("label"),
                                            promptTokPerSec = result.optDouble("promptTokPerSec", 0.0),
                                            decodeTokPerSec = result.optDouble("decodeTokPerSec", 0.0),
                                            agreed = result.optBoolean("agreed"),
                                            timedOut = result.optBoolean("timedOut"),
                                            winner = result.optBoolean("winner"),
                                        )
                                    }
                                }
                            }.orEmpty(),
                        )
                    }
                }
            }.orEmpty(),
            measuredFingerprint = optString("measuredFingerprint"),
            isDefault = optBoolean("isDefault"),
        )
    }

    private companion object {
        const val PREFERENCES = "bram-model-profiles-v1"
        const val KEY_PROFILES = "profiles"
        const val KEY_LAST_USED = "lastUsedProfileId"
    }
}
