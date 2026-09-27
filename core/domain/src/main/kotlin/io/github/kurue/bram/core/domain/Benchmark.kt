package io.github.kurue.bram.core.domain

import kotlin.math.sqrt

/**
 * One benchmark test, llama-bench style: `pp512` is prompt processing of 512 tokens; `tg128@4096`
 * is decoding 128 tokens after the context already holds 4 096. Depth matters because decode slows
 * as the KV cache grows, and a long chat lives at depth, not at zero.
 */
data class BenchTest(
    val kind: Kind,
    val tokens: Int,
    val depth: Int = 0,
    val repetitions: Int = 3,
) {
    enum class Kind(val wire: String) { PROMPT("pp"), GENERATION("tg") }

    /** "pp512", "tg128", "tg128@4096". */
    val label: String
        get() = kind.wire + tokens + if (depth > 0) "@$depth" else ""
}

/**
 * Battery power sampled while a test ran. Whole-device power (screen and radios included), so it
 * compares runs on the same phone in the same state, not phones with each other. [baselineWatts]
 * is the same device idle just before, so the difference is what the work cost.
 */
data class EnergySample(
    val averageWatts: Double,
    val baselineWatts: Double,
    val samples: Int,
) {
    /** Watts attributable to the test: the average above the idle baseline, never negative. */
    val netWatts: Double get() = (averageWatts - baselineWatts).coerceAtLeast(0.0)
}

data class BenchResult(
    val test: BenchTest,
    /** Tokens per second, one per repetition. Empty when [skipped]. */
    val tokPerSec: List<Double>,
    /** Why the test did not run (e.g. the depth does not fit the context), or null. */
    val skipped: String? = null,
    /** Present only when the phone was on battery, which is the only time the reading means work. */
    val energy: EnergySample? = null,
    val thermalBefore: String = "",
    val thermalAfter: String = "",
    /** Battery temperature (°C) before and after: the skin-heat proxy throttling follows. */
    val batteryTempBefore: Double? = null,
    val batteryTempAfter: Double? = null,
    /** How long the run waited for the phone to cool before this test, in milliseconds. */
    val cooldownMillis: Long = 0,
) {
    val mean: Double get() = if (tokPerSec.isEmpty()) 0.0 else tokPerSec.average()

    val stdDev: Double
        get() {
            if (tokPerSec.size < 2) return 0.0
            val mean = mean
            return sqrt(tokPerSec.sumOf { (it - mean) * (it - mean) } / (tokPerSec.size - 1))
        }

    /** Energy per token of the work itself (net watts over throughput); null without a reading. */
    val joulesPerToken: Double?
        get() = energy?.takeIf { mean > 0.0 }?.let { it.netWatts / mean }
}

/** A benchmark run of one profile: what ran, where, and what it measured. */
data class BenchRun(
    val id: String,
    val profileId: String,
    val profileName: String,
    /** The backend label the profile ran on ("CPU", "NPU", ...). */
    val backend: String,
    val startedAtEpochMillis: Long,
    /** [MeasurementFingerprint] key: a run from another device or build does not compare. */
    val fingerprint: String,
    val results: List<BenchResult>,
    /**
     * True for a sustained run: [results] are consecutive decode tests with no cooldown between
     * them, in order, so the list is the throttling curve rather than independent measurements.
     */
    val sustained: Boolean = false,
    /**
     * What was measured, in short: backend, prompt/decode threads, batch. Two runs compare as a
     * before/after only when this matches; otherwise the difference is the configuration.
     */
    val config: String = "",
) {
    /** For a sustained run: how far decode fell from the first test to the last (0.25 = -25%). */
    val sustainedDrop: Double?
        get() {
            if (!sustained) return null
            val rates = results.filter { it.skipped == null && it.mean > 0.0 }
            val first = rates.firstOrNull()?.mean ?: return null
            val last = rates.lastOrNull()?.mean ?: return null
            return ((first - last) / first).coerceAtLeast(0.0)
        }
}

object BenchConfig {
    /** "CPU · 6/4 threads · batch 256/128", or "NPU · 4 threads · batch 256/128". */
    fun label(backend: String, threads: Int, decodeThreads: Int, batch: Int, ubatch: Int): String {
        val threadPart = if (decodeThreads > 0 && decodeThreads != threads) "$threads/$decodeThreads threads" else "$threads threads"
        return "$backend · $threadPart · batch $batch/$ubatch"
    }
}

object Cooldown {
    /**
     * Whether a test may start: the battery is back within [slackCelsius] of where the run began,
     * so every test starts from the same thermal state instead of inheriting the last one's heat.
     * A missing reading never blocks — the thermal status gate still applies.
     */
    fun ready(currentCelsius: Double?, runStartCelsius: Double?, slackCelsius: Double = 1.0): Boolean =
        currentCelsius == null || runStartCelsius == null || currentCelsius <= runStartCelsius + slackCelsius
}

object BenchPlan {
    /**
     * The standard pass for a context of [contextTokens]: prompt processing, decode on an empty
     * cache, and decode at depth — 4 096 when it fits, else as deep as the context allows while
     * leaving room for the generated tokens. Deep enough to show the KV cost, short enough that a
     * pass takes a couple of minutes rather than a prefill of the whole window.
     */
    fun standard(contextTokens: Int): List<BenchTest> {
        val tests = mutableListOf(
            BenchTest(BenchTest.Kind.PROMPT, tokens = 512),
            BenchTest(BenchTest.Kind.GENERATION, tokens = 128),
        )
        val depth = minOf(4_096, contextTokens - 128)
        if (depth >= 1_024) {
            tests += BenchTest(BenchTest.Kind.GENERATION, tokens = 128, depth = depth, repetitions = 2)
        }
        return tests
    }
}

object BatteryPower {
    /**
     * Watts from a `BATTERY_PROPERTY_CURRENT_NOW` reading and the battery voltage.
     *
     * The platform documents microamperes, but vendors differ — some report milliamperes, and the
     * sign convention for discharge varies. A phone draws between roughly 0.05 and 10 A, so a
     * magnitude below 20 000 can only be milliamperes (as microamperes it would be under 20 mA,
     * less than an idle screen). Null for a zero or unusable reading.
     */
    fun watts(currentNow: Long, voltageMillivolts: Int): Double? {
        if (currentNow == 0L || currentNow == Long.MIN_VALUE || voltageMillivolts <= 0) return null
        val magnitude = kotlin.math.abs(currentNow.toDouble())
        val amps = if (magnitude < 20_000) magnitude / 1_000.0 else magnitude / 1_000_000.0
        return amps * voltageMillivolts / 1_000.0
    }
}
