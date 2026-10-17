package io.github.kurue.bram.core.domain

/**
 * The CPU instructions that decide which on-device kernels run fast, read from the `Features`
 * lines of `/proc/cpuinfo`. Every core lists its own line, and a big.LITTLE part only has a
 * feature if all of them do, because a thread can land on any core.
 */
data class CpuFeatures(
    /** ARMv8.2 half-precision SIMD arithmetic (`asimdhp`). Required by Bram's arm64 build. */
    val fp16: Boolean,
    /** ARMv8.2 int8 dot product (`asimddp`). Required by Bram's arm64 build. */
    val dotProduct: Boolean,
    /** ARMv8.6 int8 matrix multiply (`i8mm`), which KleidiAI's fastest Q4_0/Q8_0 kernels use. */
    val int8Matmul: Boolean,
    /** bfloat16 arithmetic (`bf16`). */
    val bf16: Boolean,
    /** Scalable vectors (`sve`). */
    val sve: Boolean,
) {
    /** What Bram's arm64 native library will refuse to load without; see `CpuRequirements`. */
    val meetsArmBaseline: Boolean get() = fp16 && dotProduct

    companion object {
        /** Null when the text has no `Features` lines, as on x86 (`flags`) or an unreadable file. */
        fun parse(cpuinfo: String): CpuFeatures? {
            val cores = cpuinfo.lineSequence()
                .filter { it.trimStart().startsWith("Features", ignoreCase = true) }
                .map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
                .toList()
            if (cores.isEmpty()) return null
            fun all(feature: String) = cores.all { feature in it }
            return CpuFeatures(
                fp16 = all("asimdhp"),
                dotProduct = all("asimddp"),
                int8Matmul = all("i8mm"),
                bf16 = all("bf16"),
                sve = all("sve"),
            )
        }
    }
}

/**
 * What a first-run scan learned about the phone, gathered by the app from Android and from the
 * native runtime. Kept Android-free so the advice built from it is a pure, testable function.
 */
data class HardwareScan(
    val deviceName: String,
    /** `Build.SOC_MANUFACTURER` (e.g. "QTI" for Qualcomm), empty when unknown. */
    val socManufacturer: String,
    val socModel: String,
    val primaryAbi: String,
    val totalRamBytes: Long,
    val freeStorageBytes: Long,
    /** Null when `/proc/cpuinfo` could not be read or has no ARM feature lines. */
    val cpu: CpuFeatures?,
    /** Accelerators the native runtime in this build actually enumerated. */
    val runtimeAccelerators: Set<AcceleratorKind>,
    /**
     * Whether the llama.cpp library loaded in the inference process: false when the build carries
     * none for this processor (the x86_64 emulator) or it is corrupt. Null when the runtime could
     * not be asked, which is treated as available — the load itself will say otherwise.
     */
    val ggufRuntimeLinked: Boolean? = null,
)

/** How a scan row reads at a glance. */
enum class FindingLevel { GOOD, NEUTRAL, LIMITED, BLOCKER }

/** One row of the scan: what was checked, what was found, and what it means for models. */
data class ScanFinding(
    val title: String,
    val level: FindingLevel,
    val summary: String,
    val detail: String,
)

/** A size class of model, named the way model cards name them. */
data class ModelSizeTier(
    val label: String,
    /** The largest model in the tier, in billions of parameters, which sizes the file. */
    val maxBillionParams: Double,
    /** Well-known model families of this size, as examples, not an endorsement list. */
    val examples: List<String>,
) {
    /** Approximate Q4_0 GGUF size: about 4.5 bits a weight, plus a little for embeddings. */
    val approxQ4FileBytes: Long get() = (maxBillionParams * BYTES_PER_BILLION_PARAMS_Q4).toLong()
}

/** What the first-run screen recommends, and why. */
data class ModelAdvice(
    val findings: List<ScanFinding>,
    /** False when no GGUF can run here, so a server is the way to chat. */
    val canRunLocally: Boolean,
    /** Whether offering the file picker makes sense: true when GGUF or LiteRT-LM may run. */
    val canImportFiles: Boolean,
    /** The tier that stays quick and leaves the phone room for everything else. */
    val comfortable: ModelSizeTier?,
    /** The biggest tier that should still load; slower, and the phone will feel it. */
    val largest: ModelSizeTier?,
    /** e.g. "GGUF, Q4_0". */
    val recommendedFormat: String,
    val headline: String,
    val reasons: List<String>,
    /** Other formats Bram can run here, in a sentence each. */
    val alternatives: List<String>,
)

private const val BYTES_PER_BILLION_PARAMS_Q4 = 0.6e9

/**
 * Turns a [HardwareScan] into first-run advice: which file format and quant to look for, and how
 * big a model the phone can hold.
 *
 * The quant advice follows what this build can actually accelerate. Q4_0 is the one 4-bit type
 * that every fast path shares: the Hexagon NPU has kernels for Q4_0/Q8_0 and not the K-quants
 * ([QuantCompatibility]), and the KleidiAI CPU kernels Bram links are the Q4_0/Q8_0 dot-product
 * and i8mm paths. Q4_K_M is the common download and runs fine, but on CPU and GPU only.
 *
 * Sizing is a fraction of total RAM, because Android gives one app far less than the phone has:
 * the model is about a quarter for the comfortable tier and under half for the largest, which
 * leaves room for the KV cache, the compute buffers, and the rest of the phone.
 */
object ModelAdvisor {

    /** Smallest to largest. The examples are the families most people will find under each size. */
    val tiers: List<ModelSizeTier> = listOf(
        ModelSizeTier("about 1B", 1.7, listOf("Gemma 3 1B", "Llama 3.2 1B", "LFM2.5 1.2B", "Qwen3 1.7B")),
        ModelSizeTier("about 3B", 3.2, listOf("Llama 3.2 3B", "SmolLM3 3B")),
        ModelSizeTier("about 4B", 4.3, listOf("Qwen3 4B", "Gemma 3 4B", "Phi-4 mini")),
        ModelSizeTier("about 8B", 8.2, listOf("Qwen3 8B", "Llama 3.1 8B")),
        ModelSizeTier("about 14B", 14.8, listOf("Qwen3 14B", "Phi-4")),
    )

    private const val COMFORTABLE_RAM_SHARE = 0.25
    private const val LARGEST_RAM_SHARE = 0.45

    /** Headroom over the file size: the import copies the model into Bram's own storage. */
    private const val STORAGE_MARGIN = 1.1

    fun advise(scan: HardwareScan): ModelAdvice {
        val isArm = scan.primaryAbi == "arm64-v8a"
        val cpuBlocks = isArm && scan.cpu != null && !scan.cpu.meetsArmBaseline
        val noRuntime = scan.ggufRuntimeLinked == false
        val npu = AcceleratorKind.HEXAGON_NPU in scan.runtimeAccelerators
        val comfortable = tierWithin(scan, COMFORTABLE_RAM_SHARE)
        val largest = tierWithin(scan, LARGEST_RAM_SHARE)
        val canRunLocally = !cpuBlocks && !noRuntime && largest != null

        val findings = listOf(
            memoryFinding(scan, comfortable, largest),
            storageFinding(scan),
            if (noRuntime) runtimeMissingFinding(scan) else cpuFinding(scan, isArm),
            npuFinding(scan, npu),
            gpuFinding(scan),
        )

        if (!canRunLocally) {
            // Only a missing library leaves LiteRT-LM worth trying: it is a separate runtime, but
            // it cannot help an old CPU, a full disk, or too little memory.
            val liteRtMayRun = noRuntime && !cpuBlocks && largest != null
            return ModelAdvice(
                findings = findings,
                canRunLocally = false,
                canImportFiles = liteRtMayRun,
                comfortable = null,
                largest = null,
                recommendedFormat = "",
                headline = "Connect a model on a server",
                reasons = listOf(
                    when {
                        cpuBlocks -> "This phone's processor lacks the ARMv8.2 instructions Bram's on-device runtime needs."
                        noRuntime -> "This build of Bram has no llama.cpp library for this processor " +
                            "(${scan.primaryAbi.ifBlank { "unknown" }}), so GGUF models cannot run here."
                        else -> "There is not enough memory or free storage for even a small on-device model."
                    },
                    "Bram can still chat through any OpenAI-compatible server, such as Ollama, " +
                        "LM Studio, or llama.cpp's server on a computer you own.",
                ),
                alternatives = if (liteRtMayRun) {
                    listOf("LiteRT-LM packages (.litertlm) use a separate runtime and may still run here.")
                } else {
                    emptyList()
                },
            )
        }

        val reasons = buildList {
            if (npu) {
                add("Q4_0 runs on this phone's NPU: it has kernels for Q4_0 and Q8_0, not the K-quants.")
            }
            when {
                scan.cpu?.int8Matmul == true ->
                    add("Q4_0 also takes the fastest CPU path: Bram's KleidiAI kernels use this CPU's int8 matrix instructions.")
                scan.cpu?.dotProduct == true ->
                    add("Q4_0 also takes the fastest CPU path: Bram's KleidiAI kernels use this CPU's dot-product instructions.")
                !isArm ->
                    add("This is an x86_64 device or emulator, so speed here says little about a phone.")
            }
            add("Q4_K_M, the most common download, also works, but on the CPU or GPU only.")
            if (comfortable != null && largest != null && comfortable != largest) {
                add("A model of ${largest.label} should still load, but expect slower replies and a warm phone.")
            }
        }

        val tierText = comfortable?.label ?: largest!!.label
        return ModelAdvice(
            findings = findings,
            canRunLocally = true,
            canImportFiles = true,
            comfortable = comfortable,
            largest = largest,
            recommendedFormat = "GGUF, Q4_0",
            headline = "A model of $tierText, as a Q4_0 GGUF",
            reasons = reasons,
            alternatives = listOf(
                "LiteRT-LM packages (.litertlm), such as Google's Gemma releases, run on the CPU or the GPU.",
                "A model on a server: any OpenAI-compatible endpoint, such as Ollama or LM Studio.",
            ),
        )
    }

    /** The largest tier whose Q4_0 file fits [ramShare] of RAM and the free storage. */
    private fun tierWithin(scan: HardwareScan, ramShare: Double): ModelSizeTier? =
        tiers.lastOrNull { tier ->
            tier.approxQ4FileBytes <= scan.totalRamBytes * ramShare &&
                tier.approxQ4FileBytes * STORAGE_MARGIN <= scan.freeStorageBytes
        }

    private fun memoryFinding(
        scan: HardwareScan,
        comfortable: ModelSizeTier?,
        largest: ModelSizeTier?,
    ): ScanFinding {
        val ram = gigabytes(scan.totalRamBytes)
        return when {
            largest == null && scan.totalRamBytes * LARGEST_RAM_SHARE < tiers.first().approxQ4FileBytes ->
                ScanFinding("Memory", FindingLevel.BLOCKER, "$ram of RAM", "Too little memory for an on-device model.")
            comfortable != null && largest != null && comfortable != largest ->
                ScanFinding(
                    "Memory", FindingLevel.GOOD, "$ram of RAM",
                    "Comfortable up to ${comfortable.label}; ${largest.label} at a push.",
                )
            else -> {
                val tier = largest ?: comfortable
                ScanFinding(
                    "Memory",
                    when (tier) {
                        null -> FindingLevel.NEUTRAL
                        tiers.first() -> FindingLevel.LIMITED
                        else -> FindingLevel.GOOD
                    },
                    "$ram of RAM",
                    tier?.let { "Room for models up to ${it.label}." } ?: "Limited by free storage, below.",
                )
            }
        }
    }

    private fun storageFinding(scan: HardwareScan): ScanFinding {
        val free = gigabytes(scan.freeStorageBytes)
        val smallest = tiers.first().approxQ4FileBytes * STORAGE_MARGIN
        return when {
            scan.freeStorageBytes < smallest ->
                ScanFinding("Storage", FindingLevel.BLOCKER, "$free free", "Free some space: even a small model needs about ${gigabytes(smallest.toLong())}.")
            scan.freeStorageBytes < tiers[2].approxQ4FileBytes * STORAGE_MARGIN ->
                ScanFinding("Storage", FindingLevel.LIMITED, "$free free", "Enough for a small model. Bram keeps its own copy of each model.")
            else ->
                ScanFinding("Storage", FindingLevel.GOOD, "$free free", "Bram keeps its own copy of each model you add.")
        }
    }

    private fun runtimeMissingFinding(scan: HardwareScan): ScanFinding = ScanFinding(
        "Processor", FindingLevel.BLOCKER, scan.primaryAbi.ifBlank { "Unknown" },
        "This build of Bram has no on-device runtime for this processor, so GGUF models cannot run here.",
    )

    private fun cpuFinding(scan: HardwareScan, isArm: Boolean): ScanFinding {
        val cpu = scan.cpu
        if (!isArm) {
            return ScanFinding(
                "Processor", FindingLevel.NEUTRAL, scan.primaryAbi.ifBlank { "Unknown" },
                "Not an ARM phone processor; models run on Bram's generic CPU build.",
            )
        }
        if (cpu == null) {
            return ScanFinding(
                "Processor", FindingLevel.NEUTRAL, "ARM64",
                "Could not read the processor's features; Bram checks again when a model loads.",
            )
        }
        val named = buildList {
            if (cpu.dotProduct) add("dot product")
            if (cpu.int8Matmul) add("int8 matrix multiply")
            if (cpu.fp16) add("fp16")
            if (cpu.bf16) add("bf16")
            if (cpu.sve) add("SVE")
        }
        return when {
            !cpu.meetsArmBaseline -> ScanFinding(
                "Processor", FindingLevel.BLOCKER, "Older than ARMv8.2",
                "Missing the fp16 and dot-product instructions Bram's on-device runtime needs.",
            )
            cpu.int8Matmul -> ScanFinding(
                "Processor", FindingLevel.GOOD, "ARMv8.6 class",
                "Has ${named.joinToString()}: the fastest CPU kernels apply.",
            )
            else -> ScanFinding(
                "Processor", FindingLevel.GOOD, "ARMv8.2 class",
                "Has ${named.joinToString()}: fast Q4_0 and Q8_0 CPU kernels apply.",
            )
        }
    }

    private fun npuFinding(scan: HardwareScan, npu: Boolean): ScanFinding {
        val qualcomm = scan.socManufacturer.equals("QTI", ignoreCase = true) ||
            scan.socManufacturer.contains("qualcomm", ignoreCase = true) ||
            scan.socModel.startsWith("SM", ignoreCase = true)
        return when {
            npu -> ScanFinding(
                "NPU", FindingLevel.GOOD, "Hexagon NPU",
                "Q4_0 and Q8_0 models can run on it. Bram checks its output against the CPU first.",
            )
            qualcomm -> ScanFinding(
                "NPU", FindingLevel.NEUTRAL, "Snapdragon, not used",
                "This build of Bram was made without Qualcomm's Hexagon SDK, so models run on the CPU or GPU instead.",
            )
            else -> ScanFinding(
                "NPU", FindingLevel.NEUTRAL, "None Bram can use",
                "Bram's NPU support is for Qualcomm Hexagon. The CPU and GPU still work.",
            )
        }
    }

    private fun gpuFinding(scan: HardwareScan): ScanFinding {
        val openCl = AcceleratorKind.OPENCL_GPU in scan.runtimeAccelerators
        val vulkan = AcceleratorKind.VULKAN_GPU in scan.runtimeAccelerators
        return when {
            openCl -> ScanFinding(
                "GPU", FindingLevel.GOOD, "OpenCL",
                "GGUF models can offload to the GPU once it matches the CPU's output.",
            )
            vulkan -> ScanFinding(
                "GPU", FindingLevel.NEUTRAL, "Vulkan, unproven",
                "Found, but Vulkan has failed Bram's correctness check before, so the CPU stays the default.",
            )
            else -> ScanFinding(
                "GPU", FindingLevel.NEUTRAL, "LiteRT-LM only",
                "No GPU backend for GGUF in this build; LiteRT-LM packages can still use the GPU.",
            )
        }
    }

    private fun gigabytes(bytes: Long): String {
        val gb = bytes / 1e9
        return if (gb >= 10) "%.0f GB".format(gb) else "%.1f GB".format(gb)
    }
}
