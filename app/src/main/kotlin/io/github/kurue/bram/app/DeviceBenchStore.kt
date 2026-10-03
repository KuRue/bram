package io.github.kurue.bram.app

import android.content.Context
import io.github.kurue.bram.core.domain.MemoryBandwidth
import org.json.JSONObject

/**
 * The device's last memory-bandwidth measurement. One record, not a history: the number only
 * changes with the device or build, and [MemoryBandwidth.fingerprint] says when it no longer
 * applies. Committed synchronously so a result survives the process dying right after it.
 */
class DeviceBenchStore(context: Context) {
    private val prefs = context.getSharedPreferences("bram-device-bench-v1", Context.MODE_PRIVATE)

    fun load(): MemoryBandwidth? = prefs.getString(KEY_BANDWIDTH, null)?.let(::decode)

    fun save(value: MemoryBandwidth) {
        prefs.edit().putString(KEY_BANDWIDTH, encode(value)).commit()
    }

    internal companion object {
        private const val KEY_BANDWIDTH = "bandwidth"

        fun encode(value: MemoryBandwidth): String = JSONObject()
            .put("peakBytesPerSecond", value.peakBytesPerSecond)
            .put("peakThreads", value.peakThreads)
            .put(
                "byThreads",
                JSONObject().also { rows ->
                    value.byThreads.forEach { (threads, rate) -> rows.put(threads.toString(), rate) }
                },
            )
            .put("measuredAtEpochMillis", value.measuredAtEpochMillis)
            .put("fingerprint", value.fingerprint)
            .toString()

        fun decode(raw: String): MemoryBandwidth? = runCatching {
            val json = JSONObject(raw)
            val rows = json.getJSONObject("byThreads")
            MemoryBandwidth(
                peakBytesPerSecond = json.getDouble("peakBytesPerSecond"),
                peakThreads = json.getInt("peakThreads"),
                byThreads = rows.keys().asSequence().associate { it.toInt() to rows.getDouble(it) },
                measuredAtEpochMillis = json.getLong("measuredAtEpochMillis"),
                fingerprint = json.optString("fingerprint"),
            )
        }.getOrNull()

        /** Reads the native sweep's JSON (see mem_bench.h). */
        fun fromNativeJson(raw: JSONObject, measuredAtEpochMillis: Long, fingerprint: String): MemoryBandwidth? {
            val results = raw.optJSONArray("results") ?: return null
            val rows = (0 until results.length()).mapNotNull { index ->
                results.optJSONObject(index)?.let { it.optInt("threads") to it.optDouble("gbPerSec") }
            }
            return MemoryBandwidth.fromNative(rows, measuredAtEpochMillis, fingerprint)
        }
    }
}
