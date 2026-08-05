package io.github.kurue.bram.core.domain

/**
 * Correctness scoring for an accelerator measured against the CPU reference.
 *
 * The comparison is teacher-forced: both backends receive the identical reference token sequence
 * and are asked only for the next-token prediction at each position. Free-running generation cannot
 * be scored this way, because one differing token sends the remainder of the sequence somewhere
 * unrelated and a small numerical difference becomes indistinguishable from a broken kernel.
 */
object AcceleratorAgreement {
    /**
     * Fraction of positions where the accelerator predicted the same token as the CPU.
     *
     * Only the overlapping prefix is scored, so a backend that returns a short sequence is not
     * credited for the positions it never produced.
     */
    fun score(reference: List<Int>, predicted: List<Int>): Double {
        val comparable = minOf(reference.size, predicted.size)
        if (comparable == 0) return 0.0
        val agreed = (0 until comparable).count { reference[it] == predicted[it] }
        return agreed.toDouble() / comparable
    }

    /**
     * Whether [score] is close enough to treat the accelerator as computing correctly.
     *
     * Exact equality is the wrong bar: a quantized accelerator legitimately disagrees with an fp32
     * CPU on near-ties. A backend that is genuinely broken misses by far more than this margin —
     * measured examples sat at 75% or collapsed entirely, against 95.8% for a working one.
     */
    fun isUsable(score: Double): Boolean = score >= USABLE_THRESHOLD

    const val USABLE_THRESHOLD: Double = 0.9
}

/**
 * Largest offload that still behaved, and the smallest that did not.
 *
 * [firstBadLayers] is null when nothing failed. [lastGoodLayers] of zero means even a single
 * offloaded layer misbehaved, which points at an operation every layer shares rather than one
 * specific layer.
 */
data class OffloadBoundary(
    val lastGoodLayers: Int,
    val firstBadLayers: Int?,
)

/**
 * Binary-searches the offload count for the boundary between working and broken.
 *
 * [totalLayers] is probed first: if it passes there is no boundary to find. [isUsableAt] is
 * expected to be monotonic — usable below the boundary, not usable above it — which held for every
 * backend measured so far. It is invoked at most log2([totalLayers]) + 1 times, since each probe
 * requires a full model reload.
 */
suspend fun findOffloadBoundary(
    totalLayers: Int,
    isUsableAt: suspend (Int) -> Boolean,
): OffloadBoundary {
    if (totalLayers <= 0) return OffloadBoundary(0, null)
    if (isUsableAt(totalLayers)) return OffloadBoundary(totalLayers, null)

    var good = 0
    var bad = totalLayers
    while (bad - good > 1) {
        val middle = good + (bad - good) / 2
        if (isUsableAt(middle)) good = middle else bad = middle
    }
    return OffloadBoundary(good, bad)
}
