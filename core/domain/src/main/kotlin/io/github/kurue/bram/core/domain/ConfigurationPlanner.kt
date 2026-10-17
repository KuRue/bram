package io.github.kurue.bram.core.domain

/** What the configuration is for; it decides which settings are worth measuring first. */
enum class ConfigWorkload(val label: String) {
    /** Short prompts and replies: thread count and prompt batch matter most. */
    CHAT("Chat"),

    /** Thousands of tokens of context: the KV cache and attention settings start to matter. */
    LONG_CONTEXT("Long context"),
}

/** How long a configuration pass may take. Starting points from the plan, not promises. */
enum class ConfigDepth(val label: String, val budgetMillis: Long) {
    QUICK("Quick", 2 * 60_000L),
    THOROUGH("Thorough", 10 * 60_000L),
}

/** One candidate's measured cost of the shared workload: lower is faster. */
data class TrialScore(val label: String, val totalMillis: Long)

/**
 * A wall-clock budget for one pass, on a monotonic clock. Every trial is charged to it, and a
 * trial whose estimated cost no longer fits is not started.
 */
class ConfigurationBudget(val totalMillis: Long, private val now: () -> Long) {
    private val startedAt = now()
    val elapsedMillis: Long get() = now() - startedAt
    val remainingMillis: Long get() = (totalMillis - elapsedMillis).coerceAtLeast(0)
    fun affords(costMillis: Long): Boolean = costMillis <= remainingMillis
}

/**
 * The running estimate of what one trial (a load plus the replay) costs on this device for this
 * model. Conservative on purpose: the slowest of the recent trials, so a pass does not start a
 * trial it cannot finish just because the last one happened to be quick.
 */
class TrialCostEstimate(private val window: Int = 3) {
    private val recent = ArrayDeque<Long>()
    fun observe(millis: Long) {
        if (millis <= 0) return
        recent.addLast(millis)
        while (recent.size > window) recent.removeFirst()
    }
    fun estimate(fallbackMillis: Long): Long = recent.maxOrNull() ?: fallbackMillis
}

/**
 * The adaptive pass's decisions that do not need a device: which settings to try, in what order,
 * and when a measured difference is real. Replaces "measure every candidate of every dimension
 * and keep the fastest", which spent most of its time confirming defaults and let measurement
 * noise pick winners among equals.
 */
object ConfigurationPlanner {

    /**
     * The smallest relative improvement treated as real. On the S25 Ultra the six batch sizes
     * measured 64/18 to 64/20 tok/s, all one result within noise; a fixed 5% margin keeps the
     * current setting unless a candidate is clearly faster. A later version can derive the
     * margin from repeated measurements.
     */
    const val NOISE_MARGIN = 0.05

    /**
     * Settings to tune after the backend, most likely to matter first, so a budget that runs out
     * has spent its time where it counts. The CPU mask comes last: it rarely wins and its
     * restricted masks are the candidates most prone to timing out.
     */
    fun dimensions(workload: ConfigWorkload, npu: Boolean): List<TuningDimension> = buildList {
        add(TuningDimension.THREADS)
        if (workload == ConfigWorkload.LONG_CONTEXT) {
            add(TuningDimension.KV_CACHE)
            add(TuningDimension.FLASH_ATTENTION)
        }
        add(TuningDimension.BATCH)
        add(TuningDimension.LOAD_MODE)
        if (npu) add(TuningDimension.HEX_FLAGS)
        add(TuningDimension.POLL)
        add(TuningDimension.CPU_MASK)
    }

    /**
     * The candidate to keep. The fastest wins only when it beats the current setting
     * ([incumbentLabel]) by more than [margin]; otherwise the current setting stays, because a
     * smaller difference is indistinguishable from noise. Null when nothing was scored.
     */
    fun pickWinner(
        scores: List<TrialScore>,
        incumbentLabel: String?,
        margin: Double = NOISE_MARGIN,
    ): TrialScore? {
        val best = scores.minByOrNull { it.totalMillis } ?: return null
        val incumbent = scores.firstOrNull { it.label == incumbentLabel } ?: return best
        return if (best.totalMillis < incumbent.totalMillis * (1 - margin)) best else incumbent
    }
}
