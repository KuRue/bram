package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.AcceleratorKind
import io.github.kurue.bram.core.domain.CapabilityState
import io.github.kurue.bram.core.domain.DeviceProfile
import io.github.kurue.bram.core.domain.ExecutionMode
import io.github.kurue.bram.core.domain.ExecutionPlan
import io.github.kurue.bram.core.domain.LocalModelArtifact
import kotlin.math.max
import kotlin.math.min

data class PlanningPreferences(
    val requestedContextTokens: Int,
    val allowStorageAssisted: Boolean,
    val safetyReserveBytes: Long? = null,
)

class ExecutionPlanner {
    fun candidates(
        device: DeviceProfile,
        model: LocalModelArtifact,
        preferences: PlanningPreferences,
    ): List<ExecutionPlan> {
        val context = min(preferences.requestedContextTokens, model.trainedContextTokens).coerceAtLeast(256)
        val reserve = preferences.safetyReserveBytes ?: max(GIB, (device.totalRamBytes * 0.20).toLong())
        val availableAfterReserve = (device.availableRamBytes - reserve).coerceAtLeast(0)
        val steadyStateLimit = min(availableAfterReserve, (device.totalRamBytes * 0.72).toLong())
        val kvBytes = saturatedMultiply(model.estimatedKvBytesPerToken, context.toLong())
        val fixedRuntimeBytes = saturatedAdd(kvBytes, model.estimatedComputeBufferBytes)

        val capabilityByKind = device.accelerators.associateBy { it.kind }
        val backends = model.backendCompatibility
            .filter { kind ->
                capabilityByKind[kind]?.state in setOf(
                    CapabilityState.AVAILABLE,
                    CapabilityState.DETECTED_NOT_VALIDATED,
                )
            }
            .sortedBy { backendPreference(it) }

        val plans = mutableListOf<ExecutionPlan>()
        for (backend in backends) {
            val repackOverhead = when (backend) {
                AcceleratorKind.HEXAGON_NPU, AcceleratorKind.LITERT_NPU -> (model.fileSizeBytes * 0.35).toLong()
                AcceleratorKind.OPENCL_GPU, AcceleratorKind.VULKAN_GPU, AcceleratorKind.LITERT_GPU ->
                    (model.fileSizeBytes * 0.10).toLong()
                else -> 0L
            }
            val residentPeak = saturatedAdd(model.fileSizeBytes, repackOverhead, fixedRuntimeBytes)
            val validated = capabilityByKind[backend]?.state == CapabilityState.AVAILABLE

            if (residentPeak <= steadyStateLimit) {
                plans += ExecutionPlan(
                    id = "${backend.name.lowercase()}-resident-$context",
                    mode = ExecutionMode.RESIDENT,
                    primaryBackend = backend,
                    contextTokens = context,
                    estimatedPeakRamBytes = residentPeak,
                    estimatedResidentWeightsBytes = model.fileSizeBytes + repackOverhead,
                    estimatedStorageBackedWeightsBytes = 0,
                    safetyReserveBytes = reserve,
                    risk = if (validated) "low" else "experimental",
                    rationale = listOf(
                        "Weights, KV cache, and working buffers fit the current safe RAM envelope.",
                        if (validated) "Backend passed validation." else "Backend needs a probationary correctness and stability run.",
                    ),
                )
            }
        }

        if (plans.isEmpty() && preferences.allowStorageAssisted && fixedRuntimeBytes < steadyStateLimit) {
            val residentWeightBudget = (steadyStateLimit - fixedRuntimeBytes).coerceAtLeast(0)
            val residentWeights = min(model.fileSizeBytes, residentWeightBudget)
            val storageBacked = (model.fileSizeBytes - residentWeights).coerceAtLeast(0)
            if (storageBacked > 0 && device.freeStorageBytes > model.fileSizeBytes + 512L * MIB) {
                plans += ExecutionPlan(
                    id = "cpu-storage-assisted-$context",
                    mode = ExecutionMode.STORAGE_ASSISTED,
                    primaryBackend = AcceleratorKind.CPU,
                    contextTokens = context,
                    estimatedPeakRamBytes = steadyStateLimit,
                    estimatedResidentWeightsBytes = residentWeights,
                    estimatedStorageBackedWeightsBytes = storageBacked,
                    safetyReserveBytes = reserve,
                    risk = "extremely slow",
                    rationale = listOf(
                        "The complete model does not fit the safe resident-memory envelope.",
                        "KV cache and compute buffers remain resident; model pages are reread from storage.",
                        "The UI must require explicit opt-in and show measured storage reads per token.",
                    ),
                )
            }
        }

        return plans
    }

    private fun backendPreference(kind: AcceleratorKind): Int = when (kind) {
        AcceleratorKind.HEXAGON_NPU, AcceleratorKind.LITERT_NPU -> 0
        AcceleratorKind.OPENCL_GPU, AcceleratorKind.VULKAN_GPU, AcceleratorKind.LITERT_GPU -> 1
        AcceleratorKind.CPU, AcceleratorKind.LITERT_CPU -> 2
    }

    private fun saturatedMultiply(left: Long, right: Long): Long =
        if (left == 0L || right <= Long.MAX_VALUE / left) left * right else Long.MAX_VALUE

    private fun saturatedAdd(vararg values: Long): Long {
        var sum = 0L
        for (value in values) {
            if (value > Long.MAX_VALUE - sum) return Long.MAX_VALUE
            sum += value
        }
        return sum
    }

    private companion object {
        const val MIB = 1_048_576L
        const val GIB = 1_073_741_824L
    }
}
