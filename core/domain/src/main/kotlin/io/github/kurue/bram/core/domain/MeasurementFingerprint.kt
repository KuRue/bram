package io.github.kurue.bram.core.domain

/**
 * What a measurement was taken on. Stored beside every tuning result so a profile restored onto
 * another phone, an OS update, a new Bram build, or a new llama.cpp pin can be recognized as
 * stale and re-measured instead of silently trusted. Pure so the key math is tested.
 */
data class MeasurementFingerprint(
    /** The device + OS identity (the profiler's short hash). */
    val device: String,
    /** The app build the measurement ran under. */
    val appBuild: String,
    /** The native engine build (llama.cpp commit). */
    val engineBuild: String,
    /** The CPU ISA features the engine reported, so a kernel change is visible too. */
    val cpuFeatures: String,
) {
    val key: String
        get() = "d=$device|app=$appBuild|engine=$engineBuild|cpu=$cpuFeatures"

    companion object {
        /**
         * Extracts the features the CPU kernels care about from llama.cpp's system info
         * ("... DOTPROD = 1 | MATMUL_INT8 = 1 | SVE = 1 ...") into a compact, stable string.
         * Absent or unparseable input yields all-zero flags rather than a mismatch storm.
         */
        fun cpuFeatureFlags(systemInfo: String): String =
            listOf("NEON", "DOTPROD", "MATMUL_INT8", "SVE", "SME").joinToString("|") { feature ->
                "$feature=" + if (systemInfo.contains("$feature = 1")) "1" else "0"
            }
    }
}
