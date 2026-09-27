package io.github.kurue.bram.core.domain

/**
 * The device's measured DRAM read bandwidth, and what it implies for decode.
 *
 * Generating one token of a dense model reads every weight once, so decode speed on a phone is
 * capped by memory bandwidth, not arithmetic: `ceiling ≈ bandwidth / weight bytes`. Knowing the
 * ceiling turns "is 20 tok/s good?" into "20 tok/s is 45% of what this phone can do", which says
 * whether a faster kernel, a different engine, or only a smaller model can help.
 *
 * [peakBytesPerSecond] is the best thread count's sustained read rate; [byThreads] keeps the whole
 * sweep, since where it flattens says how many cores it takes to saturate the bus.
 */
data class MemoryBandwidth(
    val peakBytesPerSecond: Double,
    val peakThreads: Int,
    val byThreads: Map<Int, Double>,
    val measuredAtEpochMillis: Long,
    /** [MeasurementFingerprint] of the device and build it was measured on. */
    val fingerprint: String,
) {
    val peakGbPerSecond: Double get() = peakBytesPerSecond / 1e9

    companion object {
        /** Parses the native sweep (see mem_bench.h); null when it carries no usable result. */
        fun fromNative(
            results: List<Pair<Int, Double>>,
            measuredAtEpochMillis: Long,
            fingerprint: String,
        ): MemoryBandwidth? {
            val valid = results.filter { (threads, gb) -> threads > 0 && gb > 0.0 && gb.isFinite() }
            val best = valid.maxByOrNull { it.second } ?: return null
            return MemoryBandwidth(
                peakBytesPerSecond = best.second * 1e9,
                peakThreads = best.first,
                byThreads = valid.associate { (threads, gb) -> threads to gb * 1e9 },
                measuredAtEpochMillis = measuredAtEpochMillis,
                fingerprint = fingerprint,
            )
        }
    }
}

/** Where a measured decode speed sits against the bandwidth ceiling. */
data class DecodeCeiling(
    /** Tokens per second if every weight byte streamed at the measured peak. */
    val ceilingTokPerSec: Double,
    /** The measured decode speed as a share of [ceilingTokPerSec], 0..1+ (above 1 means cache help). */
    val share: Double,
)

object MemoryCeiling {
    /**
     * Whether the file-size ceiling applies: false for mixture-of-experts architectures, which
     * read only their active experts per token. Name-based, so a MoE published under a dense
     * architecture name (Mixtral ships as `llama`) slips through and reads as "far below ceiling".
     */
    fun appliesTo(architecture: String): Boolean {
        val arch = architecture.trim().lowercase()
        if (arch.isEmpty()) return false
        if (isStreamableMoeArchitecture(arch) || "moe" in arch) return false
        return arch !in KNOWN_MOE_ARCHITECTURES
    }

    private val KNOWN_MOE_ARCHITECTURES = setOf(
        "deepseek", "deepseek2", "deepseek4", "gpt-oss", "gptoss", "llama4", "dbrx", "arctic",
        "grok", "jamba", "bailingmoe", "hunyuan-moe", "ernie4_5-moe", "glm4moe",
    )

    /**
     * The decode ceiling for a dense model whose per-token weight read is [weightBytes], and how
     * close [decodeTokPerSec] comes to it. Null when either input is unusable.
     *
     * The file size stands in for bytes read per token. It slightly overstates them — the token
     * embedding table is only gathered one row per token — which makes the ceiling conservative
     * (a little low), never flattering. MoE models read only their active experts per token, so
     * this does not apply to them; callers must not pass one.
     */
    fun forDenseModel(
        weightBytes: Long,
        decodeTokPerSec: Double,
        bandwidth: MemoryBandwidth,
    ): DecodeCeiling? {
        if (weightBytes <= 0 || decodeTokPerSec <= 0.0 || bandwidth.peakBytesPerSecond <= 0.0) return null
        val ceiling = bandwidth.peakBytesPerSecond / weightBytes.toDouble()
        return DecodeCeiling(ceilingTokPerSec = ceiling, share = decodeTokPerSec / ceiling)
    }
}
