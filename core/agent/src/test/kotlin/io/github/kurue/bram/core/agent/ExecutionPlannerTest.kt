package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.AcceleratorCapability
import io.github.kurue.bram.core.domain.AcceleratorKind
import io.github.kurue.bram.core.domain.CapabilityState
import io.github.kurue.bram.core.domain.DeviceProfile
import io.github.kurue.bram.core.domain.ExecutionMode
import io.github.kurue.bram.core.domain.LocalModelArtifact
import io.github.kurue.bram.core.domain.ModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionPlannerTest {
    @Test
    fun `oversized model becomes explicit storage-assisted plan`() {
        val gib = 1_073_741_824L
        val device = DeviceProfile(
            manufacturer = "Test",
            model = "Phone",
            soc = "SoC",
            osVersion = "1",
            appAbi = "arm64-v8a",
            cpuCoreCount = 8,
            totalRamBytes = 12 * gib,
            availableRamBytes = 8 * gib,
            lowMemoryThresholdBytes = gib,
            totalStorageBytes = 256 * gib,
            freeStorageBytes = 100 * gib,
            thermalStatus = "none",
            accelerators = listOf(
                AcceleratorCapability(AcceleratorKind.CPU, CapabilityState.AVAILABLE, "ok"),
            ),
            profileFingerprint = "test",
        )
        val model = LocalModelArtifact(
            modelId = ModelId("big"),
            displayName = "Big",
            architecture = "test",
            quantization = "Q4",
            fileSizeBytes = 15 * gib,
            layerCount = 40,
            trainedContextTokens = 32_768,
            estimatedKvBytesPerToken = 64 * 1_024,
            estimatedComputeBufferBytes = 512 * 1_048_576L,
            backendCompatibility = setOf(AcceleratorKind.CPU),
        )

        val plans = ExecutionPlanner().candidates(
            device,
            model,
            PlanningPreferences(requestedContextTokens = 8_192, allowStorageAssisted = true),
        )

        assertEquals(1, plans.size)
        assertEquals(ExecutionMode.STORAGE_ASSISTED, plans.single().mode)
        assertTrue(plans.single().estimatedStorageBackedWeightsBytes > 0)
    }
}
