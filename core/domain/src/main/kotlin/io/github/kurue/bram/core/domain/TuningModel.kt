package io.github.kurue.bram.core.domain

/**
 * A runtime setting that can be measured on the device. Each dimension is a load-identity field
 * with a candidate list, a teacher-forced gate, and a note written on the profile — the batch
 * tuner was the first of these, and the rest follow the same shape.
 */
enum class TuningDimension(val wire: String, val label: String) {
    THREADS("threads", "Threads"),
    CPU_MASK("cpuMask", "CPU mask"),
    POLL("poll", "Poll"),
    KV_CACHE("kvCache", "KV cache"),
    FLASH_ATTENTION("flashAttention", "Attention"),
    LOAD_MODE("loadMode", "Load mode"),
    HEX_FLAGS("hexFlags", "Hexagon"),
    BATCH("batch", "Batch"),
    ;

    companion object {
        fun fromWire(value: String): TuningDimension? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * What one tuning measurement chose, in words, beside the choice itself.
 *
 * Kept as notes so the card can show "why" the way accelerator measurements do: a measured
 * choice that cannot be inspected is a silent automatic decision, which the backend choice is
 * explicitly not allowed to be.
 */
data class DimensionTuneNote(
    val dimension: TuningDimension,
    /** The chosen value as a person would read it, e.g. "6 threads" or "mask 0xfc". */
    val chosen: String,
    /** Why, in words, including what was tried. */
    val note: String,
    val measuredAtEpochMillis: Long,
    /** Per-candidate throughput, so the UI can draw the comparison instead of quoting it. */
    val results: List<TuneCandidateResult> = emptyList(),
)

/**
 * One candidate from a tuning sweep, with the numbers the UI draws. Absolute throughput — the
 * display metric — not a comparison against the CPU reference; agreement is the small print
 * that says the number can be trusted at all.
 */
data class TuneCandidateResult(
    /** What was tried, in words ("8 threads", "Mask 0x3", "Aggressive polling"). */
    val label: String,
    /** Prompt processing throughput of the measured run, in tokens per second. */
    val promptTokPerSec: Double,
    /** Decode throughput of the measured run, in tokens per second. */
    val decodeTokPerSec: Double,
    /** Whether the candidate reproduced the CPU reference (the gate that lets speed count). */
    val agreed: Boolean,
    /** Whether the candidate was abandoned because its measurement hung. */
    val timedOut: Boolean = false,
    /** Whether this candidate won the sweep. */
    val winner: Boolean = false,
)

/**
 * Where the candidate lists for the tuning dimensions come from. Pure so the device-dependent
 * parts (core counts, cluster topology) can be fed in from the outside and the math tested.
 */
object TuningCandidates {

    /** Thread counts worth trying: the current choice (when set), then all, all-but-two, half. */
    fun threadCandidates(cores: Int, current: Int): List<Int> {
        val all = (cores - 2).coerceAtLeast(1)
        val half = (cores / 2).coerceAtLeast(1)
        return buildList {
            if (current in 1..cores) add(current)
            add(cores)
            if (all != cores) add(all)
            if (half != all && half != cores) add(half)
        }.distinct()
    }

    /**
     * Hex string naming [coreIndices], e.g. cores 0..5 -> "3f". Empty for "no mask" (default
     * affinity), which is how every candidate list starts.
     */
    fun maskForCores(coreIndices: Collection<Int>): String {
        if (coreIndices.isEmpty()) return ""
        var bits = 0L
        coreIndices.forEach { index ->
            if (index in 0 until 64) bits = bits or (1L shl index)
        }
        return if (bits == 0L) "" else java.lang.Long.toHexString(bits)
    }

    /**
     * Mask candidates from cluster core counts ordered by max frequency, descending. The empty
     * mask (default affinity) is always first; when the device has more than one cluster, "top
     * cluster only" and "top two clusters" follow, because generation usually wants the fast
     * cores and nothing else contending with them.
     */
    fun maskCandidates(clusterSizes: List<Int>): List<String> {
        val candidates = mutableListOf("")
        if (clusterSizes.size < 2) return candidates
        val top = (0 until clusterSizes[0]).toList()
        candidates += maskForCores(top)
        val second = (clusterSizes[0] until clusterSizes[0] + clusterSizes[1]).toList()
        val topTwo = top + second
        if (maskForCores(topTwo) != maskForCores(top)) candidates += maskForCores(topTwo)
        return candidates.distinct()
    }

    /** Polling levels worth trying: none and aggressive. */
    fun pollCandidates(): List<Int> = listOf(0, 100)

    /** How the weights enter memory; mmap is today's behavior, no-mmap the Hexagon reference. */
    fun loadModeCandidates(): List<LoadMode> = listOf(LoadMode.MMAP, LoadMode.NO_MMAP)

    /**
     * Hexagon host-side flag sets worth trying: the backend defaults, and the upstream
     * Snapdragon reference combination (HMX on, HVX off, host buffers, op batching).
     */
    fun hexFlagCandidates(): List<HexFlags> = listOf(
        HexFlags(),
        HexFlags(useHmx = true, disableNhvx = true, hostBuf = true, opBatch = 1),
    )
}
