package io.github.kurue.bram.app

import android.content.Context
import io.github.kurue.bram.core.domain.BenchResult
import io.github.kurue.bram.core.domain.BenchRun
import io.github.kurue.bram.core.domain.BenchTest
import io.github.kurue.bram.core.domain.EnergySample
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every benchmark run, newest first, capped. A history rather than a latest value because the
 * point is comparison: the same profile before and after a pin bump, a tuning change, or an OS
 * update. Each run carries its fingerprint so the UI can say when two runs do not compare.
 */
class BenchHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("bram-bench-history-v1", Context.MODE_PRIVATE)

    @Synchronized
    fun runs(): List<BenchRun> = decode(prefs.getString(KEY_RUNS, null).orEmpty())

    @Synchronized
    fun add(run: BenchRun) {
        val updated = (listOf(run) + runs()).take(MAX_RUNS)
        prefs.edit().putString(KEY_RUNS, encode(updated)).commit()
    }

    internal companion object {
        private const val KEY_RUNS = "runs"
        const val MAX_RUNS = 100

        fun encode(runs: List<BenchRun>): String = JSONArray().also { array ->
            runs.forEach { run ->
                array.put(
                    JSONObject()
                        .put("id", run.id)
                        .put("profileId", run.profileId)
                        .put("profileName", run.profileName)
                        .put("backend", run.backend)
                        .put("startedAtEpochMillis", run.startedAtEpochMillis)
                        .put("fingerprint", run.fingerprint)
                        .put("sustained", run.sustained)
                        .put("config", run.config)
                        .put("results", JSONArray().also { results -> run.results.forEach { results.put(encodeResult(it)) } }),
                )
            }
        }.toString()

        private fun encodeResult(result: BenchResult): JSONObject = JSONObject()
            .put("kind", result.test.kind.wire)
            .put("tokens", result.test.tokens)
            .put("depth", result.test.depth)
            .put("repetitions", result.test.repetitions)
            .put("tokPerSec", JSONArray(result.tokPerSec))
            .put("skipped", result.skipped ?: JSONObject.NULL)
            .put("thermalBefore", result.thermalBefore)
            .put("thermalAfter", result.thermalAfter)
            .put("batteryTempBefore", result.batteryTempBefore ?: JSONObject.NULL)
            .put("batteryTempAfter", result.batteryTempAfter ?: JSONObject.NULL)
            .put("cooldownMillis", result.cooldownMillis)
            .put(
                "energy",
                result.energy?.let {
                    JSONObject()
                        .put("averageWatts", it.averageWatts)
                        .put("baselineWatts", it.baselineWatts)
                        .put("samples", it.samples)
                } ?: JSONObject.NULL,
            )

        fun decode(raw: String): List<BenchRun> {
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                runCatching {
                    val json = array.getJSONObject(index)
                    val results = json.getJSONArray("results")
                    BenchRun(
                        id = json.getString("id"),
                        profileId = json.getString("profileId"),
                        profileName = json.optString("profileName"),
                        backend = json.optString("backend"),
                        startedAtEpochMillis = json.getLong("startedAtEpochMillis"),
                        fingerprint = json.optString("fingerprint"),
                        results = (0 until results.length()).map { decodeResult(results.getJSONObject(it)) },
                        sustained = json.optBoolean("sustained"),
                        config = json.optString("config"),
                    )
                }.getOrNull()
            }
        }

        private fun decodeResult(json: JSONObject): BenchResult {
            val kind = BenchTest.Kind.entries.firstOrNull { it.wire == json.optString("kind") } ?: BenchTest.Kind.PROMPT
            val rates = json.optJSONArray("tokPerSec") ?: JSONArray()
            val energy = json.optJSONObject("energy")
            return BenchResult(
                test = BenchTest(kind, json.getInt("tokens"), json.optInt("depth"), json.optInt("repetitions", 1)),
                tokPerSec = (0 until rates.length()).map { rates.getDouble(it) },
                skipped = json.optString("skipped").takeIf { !json.isNull("skipped") && it.isNotEmpty() },
                energy = energy?.let {
                    EnergySample(it.getDouble("averageWatts"), it.getDouble("baselineWatts"), it.getInt("samples"))
                },
                thermalBefore = json.optString("thermalBefore"),
                thermalAfter = json.optString("thermalAfter"),
                batteryTempBefore = json.optDouble("batteryTempBefore").takeIf { !it.isNaN() },
                batteryTempAfter = json.optDouble("batteryTempAfter").takeIf { !it.isNaN() },
                cooldownMillis = json.optLong("cooldownMillis"),
            )
        }
    }
}
