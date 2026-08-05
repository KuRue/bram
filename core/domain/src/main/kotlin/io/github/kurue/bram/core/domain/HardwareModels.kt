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

enum class RoutingMode {
    LOCAL_ONLY,
    REMOTE_ONLY,
    AUTO,
}

enum class PrivacyClass {
    LOCAL_ONLY,
    PRIVATE_REMOTE_ALLOWED,
    STANDARD,
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
)

data class RoutingDecision(
    val selected: RoutingCandidate?,
    val rejectedReasons: Map<ModelId, String>,
    val rationale: List<String>,
)
