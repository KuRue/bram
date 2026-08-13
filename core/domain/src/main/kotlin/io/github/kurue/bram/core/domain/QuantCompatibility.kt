package io.github.kurue.bram.core.domain

/**
 * Which processors can compute a model's weights, derived from the per-tensor quant counts the
 * import reads from the GGUF. A backend that lacks kernels for a quant type runs those tensors
 * on the CPU, so the report is about how much of a model a backend can actually take.
 */
object QuantCompatibility {

    /**
     * ggml types the Hexagon HTP backend has kernels for (at the llama.cpp pin). Everything else
     * falls back to the CPU — notably the Q4_K family, which is why a Q4_K_M file cannot fully
     * offload to the NPU.
     */
    private val hexagonTypes = setOf("q4_0", "q4_1", "q8_0", "mxfp4", "iq4_nl")

    /** Types the Vulkan/OpenCL backends cannot offload; the K-quants and friends are fine there. */
    private val gpuUnsupportedTypes = setOf("mxfp4", "nvfp4", "tq1_0", "tq2_0")

    /**
     * What one file means for one backend: the share of its tensors that backend can compute,
     * and the share's story in words for the card.
     */
    data class BackendVerdict(
        val share: Double,
        val supportedTensors: Int,
        val totalTensors: Int,
    ) {
        val sharePercent: Int get() = (share * 100).toInt()

        val description: String
            get() = when {
                share >= 1.0 -> "Every weight tensor offloads"
                share >= 0.8 -> "Most weight tensors offload; the rest run on CPU"
                share >= 0.4 -> "About $sharePercent% of weight tensors offload; the rest run on CPU"
                share > 0.0 -> "Only $sharePercent% of weight tensors offload — mostly CPU"
                else -> "No weight tensors offload; this file would run on CPU"
            }
    }

    /** The verdicts for every backend Bram knows, for one file's quant counts. */
    data class CompatibilityReport(
        val cpu: BackendVerdict,
        val gpu: BackendVerdict,
        val npu: BackendVerdict,
        /** The quant type with the most weight tensors, e.g. "q4_k" — the file's true shape. */
        val dominantType: String,
    )

    /**
     * Builds the report from the import's per-tensor counts. Unknown types are counted as
     * unoffloadable everywhere except the CPU, which computes anything.
     */
    fun report(tensorTypeCounts: Map<String, Int>): CompatibilityReport {
        val total = tensorTypeCounts.values.sum().coerceAtLeast(1)
        val npuSupported = tensorTypeCounts
            .filterKeys { it in hexagonTypes }.values.sum()
        val gpuSupported = tensorTypeCounts
            .filterKeys { it !in gpuUnsupportedTypes }.values.sum()
        val dominant = tensorTypeCounts.maxByOrNull { it.value }?.key ?: ""
        return CompatibilityReport(
            cpu = BackendVerdict(1.0, total, total),
            gpu = BackendVerdict(gpuSupported.toDouble() / total, gpuSupported, total),
            npu = BackendVerdict(npuSupported.toDouble() / total, npuSupported, total),
            dominantType = dominant,
        )
    }
}
