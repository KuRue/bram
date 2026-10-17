package io.github.kurue.bram.app

import android.os.Build
import io.github.kurue.bram.core.domain.AcceleratorKind
import io.github.kurue.bram.core.domain.CpuFeatures
import io.github.kurue.bram.core.domain.DeviceProfile
import io.github.kurue.bram.core.domain.HardwareScan
import java.io.File

/**
 * Gathers the first-run [HardwareScan]: the device profile Bram already keeps, the CPU's
 * instruction-set features, and the accelerators the native runtime enumerated in this build.
 */
internal object HardwareScanner {

    fun scan(
        profile: DeviceProfile,
        runtimeBackends: List<RuntimeBackend>,
        ggufRuntimeLinked: Boolean?,
    ): HardwareScan = HardwareScan(
        deviceName = listOf(profile.manufacturer, profile.model).filter { it.isNotBlank() }.joinToString(" "),
        socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.orEmpty() else "",
        socModel = profile.soc,
        primaryAbi = profile.appAbi,
        totalRamBytes = profile.totalRamBytes,
        freeStorageBytes = profile.freeStorageBytes,
        cpu = runCatching { CpuFeatures.parse(File("/proc/cpuinfo").readText()) }.getOrNull(),
        runtimeAccelerators = runtimeBackends.mapNotNull { it.acceleratorKind }.toSet(),
        ggufRuntimeLinked = ggufRuntimeLinked,
    )

    private val RuntimeBackend.acceleratorKind: AcceleratorKind?
        get() = when (this) {
            RuntimeBackend.CPU -> null
            RuntimeBackend.VULKAN -> AcceleratorKind.VULKAN_GPU
            RuntimeBackend.OPENCL -> AcceleratorKind.OPENCL_GPU
            RuntimeBackend.HEXAGON -> AcceleratorKind.HEXAGON_NPU
        }
}
