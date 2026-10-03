package io.github.kurue.bram.runtime.llamacpp.inference

import java.io.File

/**
 * Whether this CPU can run Bram's native library.
 *
 * The arm64 build of ggml-cpu targets armv8.2-a with half-precision arithmetic and dot-product
 * instructions (see CMakeLists: GGML_CPU_ARM_ARCH). That took Qwen3-4B's decode at a 4K-token
 * context from 2.6 to 7.0 tok/s on the S25 Ultra, but a core without those instructions would die
 * on SIGILL the moment a kernel ran. Pre-2018 designs built only from Cortex-A53/A57/A72 cores
 * lack them. So the check runs before the library is loaded, and such a phone gets a sentence
 * instead of a crash. Local models on those phones were never practical anyway.
 */
object CpuRequirements {
    /** The Linux hwcap names for the two features: fp16 SIMD arithmetic and dot product. */
    val REQUIRED_FEATURES = listOf("asimdhp", "asimddp")

    /** The required features missing from a `/proc/cpuinfo` text; empty when all are present. */
    fun missingFeatures(cpuinfo: String): List<String> {
        // Every core lists its own Features line; a big.LITTLE part must support them on all.
        val lines = cpuinfo.lineSequence()
            .filter { it.trimStart().startsWith("Features", ignoreCase = true) }
            .map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
            .toList()
        if (lines.isEmpty()) return emptyList() // Unreadable: do not block on a guess.
        return REQUIRED_FEATURES.filter { feature -> lines.any { feature !in it } }
    }

    /**
     * Throws with a user-facing reason when this is an arm64 device missing a required feature.
     * Other ABIs (the x86_64 emulator build) are built for their own baseline and pass.
     */
    fun check(primaryAbi: String, cpuinfo: () -> String = { File("/proc/cpuinfo").readText() }) {
        if (primaryAbi != "arm64-v8a") return
        val missing = runCatching { missingFeatures(cpuinfo()) }.getOrDefault(emptyList())
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "This phone's processor lacks instructions Bram's on-device models need " +
                    "(${missing.joinToString()}; ARMv8.2 or newer). Use a model on a server instead.",
            )
        }
    }
}
