package io.github.kurue.bram.core.domain

enum class AcceleratorKind {
    CPU,
    VULKAN_GPU,
    OPENCL_GPU,
    HEXAGON_NPU,
    LITERT_CPU,
    LITERT_GPU,
    LITERT_NPU,
}

/** What a capability row calls itself, short enough to sit beside the state marker. */
val AcceleratorKind.displayName: String
    get() = when (this) {
        AcceleratorKind.CPU -> "CPU"
        AcceleratorKind.VULKAN_GPU -> "Vulkan"
        AcceleratorKind.OPENCL_GPU -> "OpenCL"
        AcceleratorKind.HEXAGON_NPU -> "NPU"
        AcceleratorKind.LITERT_CPU -> "LiteRT CPU"
        AcceleratorKind.LITERT_GPU -> "LiteRT GPU"
        AcceleratorKind.LITERT_NPU -> "LiteRT NPU"
    }

enum class CapabilityState {
    AVAILABLE,
    DETECTED_NOT_VALIDATED,
    UNPROBED,
    UNAVAILABLE,
    FAILED_VALIDATION,
}

data class AcceleratorCapability(
    val kind: AcceleratorKind,
    val state: CapabilityState,
    val detail: String,
)

data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val osVersion: String,
    val appAbi: String,
    val cpuCoreCount: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val lowMemoryThresholdBytes: Long,
    val totalStorageBytes: Long,
    val freeStorageBytes: Long,
    val thermalStatus: String,
    val accelerators: List<AcceleratorCapability>,
    val profileFingerprint: String,
)

data class LocalModelArtifact(
    val modelId: ModelId,
    val displayName: String,
    val architecture: String,
    val quantization: String,
    val fileSizeBytes: Long,
    val layerCount: Int,
    val trainedContextTokens: Int,
    val estimatedKvBytesPerToken: Long,
    val estimatedComputeBufferBytes: Long,
    val backendCompatibility: Set<AcceleratorKind>,
)

/**
 * A GGUF selected through Android's Storage Access Framework.
 *
 * [contentUri] records the import source for display; the runtime copies the bytes into
 * app-private storage at [localPath] because scoped storage forbids native code from re-opening
 * a provider-granted descriptor by path. Both are strings so the domain model remains
 * Android-free.
 */
data class LocalModelRecord(
    val id: ModelId,
    val displayName: String,
    val fileName: String,
    val contentUri: String,
    val localPath: String,
    val fileSizeBytes: Long,
    val sha256: String,
    val ggufVersion: Int,
    val architecture: String,
    val quantization: String,
    val trainedContextTokens: Int,
    val layerCount: Int,
    val hasChatTemplate: Boolean,
    val importedAtEpochMillis: Long = System.currentTimeMillis(),
    val preferredContextTokens: Int = trainedContextTokens.takeIf { it > 0 }?.coerceAtMost(8_192) ?: 4_096,
    /**
     * Which processor this model should load onto, remembered per model because the best choice
     * depends on the model as much as the device. Empty means CPU.
     */
    val preferredBackendId: String = "",
    /**
     * Whether to ask the model to reason before answering. Worth having per model: a reasoning
     * model can spend several paragraphs deciding how to say hello, which is a poor trade on a
     * phone unless the question actually warrants it.
     */
    val thinkingEnabled: Boolean = false,
) {
    fun asModelDescriptor(): ModelDescriptor = ModelDescriptor(
        id = id,
        displayName = displayName,
        providerName = "On this device",
        modelName = fileName,
        location = ModelLocation.LOCAL,
        contextWindowTokens = preferredContextTokens,
        capabilities = setOf(ModelCapability.TEXT),
    )
}

enum class ExecutionMode {
    RESIDENT,
    STORAGE_ASSISTED,
}

data class ExecutionPlan(
    val id: String,
    val mode: ExecutionMode,
    val primaryBackend: AcceleratorKind,
    val fallbackBackend: AcceleratorKind = AcceleratorKind.CPU,
    val contextTokens: Int,
    val estimatedPeakRamBytes: Long,
    val estimatedResidentWeightsBytes: Long,
    val estimatedStorageBackedWeightsBytes: Long,
    val safetyReserveBytes: Long,
    val risk: String,
    val rationale: List<String>,
)

/**
 * How a run may choose between local and remote processing, decided once per run by the router.
 * AUTO weighs quality, latency, and battery; the only-only modes are hard gates.
 */
enum class RoutingMode(val wire: String) {
    AUTO("auto"),
    LOCAL_ONLY("local_only"),
    REMOTE_ONLY("remote_only");

    val label: String
        get() = when (this) {
            AUTO -> "Auto"
            LOCAL_ONLY -> "Local only"
            REMOTE_ONLY -> "Remote only"
        }

    companion object {
        fun fromWire(value: String?): RoutingMode = entries.firstOrNull { it.wire == value } ?: AUTO
    }
}

/**
 * How much this conversation trusts remote processing of its content.
 *
 * LOCAL_ONLY means the content never leaves the device: remote candidates are hard-rejected
 * regardless of routing mode. PRIVATE_REMOTE_ALLOWED means remote processing is acceptable but
 * local is preferred — the router keeps its normal quality/latency/battery scoring with a local
 * bias on top. STANDARD is the plain default: no bias, remote processed under the routing mode.
 */
enum class PrivacyClass(val wire: String) {
    STANDARD("standard"),
    PRIVATE_REMOTE_ALLOWED("private_remote_allowed"),
    LOCAL_ONLY("local_only");

    val label: String
        get() = when (this) {
            STANDARD -> "Standard"
            PRIVATE_REMOTE_ALLOWED -> "Prefer local"
            LOCAL_ONLY -> "Local only"
        }

    companion object {
        fun fromWire(value: String?): PrivacyClass = entries.firstOrNull { it.wire == value } ?: STANDARD
    }
}

data class RoutingCandidate(
    val model: ModelDescriptor,
    val available: Boolean,
    val estimatedLatencyMillis: Long? = null,
    val estimatedQuality: Double? = null,
    val estimatedBatteryCost: Double? = null,
)

data class RoutingRequest(
    val mode: RoutingMode,
    val privacyClass: PrivacyClass,
    val requiredCapabilities: Set<ModelCapability>,
    val minimumContextTokens: Int,
    val preferQuality: Boolean = false,
    /** Extra weight given to local candidates; privacy classes that prefer local raise it. */
    val localBias: Double = 1.0,
)

data class RoutingDecision(
    val selected: RoutingCandidate?,
    /** The remaining eligible candidates in score order, for a policy-aware fallback. */
    val fallbacks: List<RoutingCandidate> = emptyList(),
    val rejectedReasons: Map<ModelId, String>,
    val rationale: List<String>,
)
