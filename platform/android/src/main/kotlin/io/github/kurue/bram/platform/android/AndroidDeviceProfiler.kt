package io.github.kurue.bram.platform.android

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import io.github.kurue.bram.core.domain.AcceleratorCapability
import io.github.kurue.bram.core.domain.AcceleratorKind
import io.github.kurue.bram.core.domain.CapabilityState
import io.github.kurue.bram.core.domain.DeviceProfile
import java.security.MessageDigest

class AndroidDeviceProfiler(
    private val context: Context,
) {
    /**
     * [runtimeBackends] are the accelerators the native runtime actually enumerated. Android's
     * feature flags only say a GPU exists, not that this build can compute on it, so anything the
     * runtime reports takes precedence over guesswork.
     */
    fun snapshot(
        cpuValidated: Boolean = false,
        runtimeBackends: Set<AcceleratorKind> = emptySet(),
    ): DeviceProfile {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val storage = StatFs(context.filesDir.absolutePath)
        val power = context.getSystemService(PowerManager::class.java)
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val hasVulkan = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val thermal = thermalName(power.currentThermalStatus)

        val fingerprintMaterial = listOf(
            Build.MANUFACTURER,
            Build.MODEL,
            soc,
            Build.FINGERPRINT,
            abi,
        ).joinToString("|")

        return DeviceProfile(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            soc = soc.orEmpty(),
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appAbi = abi,
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            totalRamBytes = memory.totalMem,
            availableRamBytes = memory.availMem,
            lowMemoryThresholdBytes = memory.threshold,
            totalStorageBytes = storage.totalBytes,
            freeStorageBytes = storage.availableBytes,
            thermalStatus = thermal,
            accelerators = listOf(
                AcceleratorCapability(
                    AcceleratorKind.CPU,
                    if (cpuValidated) CapabilityState.AVAILABLE else CapabilityState.DETECTED_NOT_VALIDATED,
                    if (cpuValidated) {
                        "$abi with ${Runtime.getRuntime().availableProcessors()} visible cores; llama.cpp tokenizer + decode self-test passed"
                    } else {
                        "$abi with ${Runtime.getRuntime().availableProcessors()} visible cores; native correctness test has not passed yet"
                    },
                ),
                AcceleratorCapability(
                    AcceleratorKind.VULKAN_GPU,
                    when {
                        AcceleratorKind.VULKAN_GPU in runtimeBackends -> CapabilityState.DETECTED_NOT_VALIDATED
                        hasVulkan -> CapabilityState.UNPROBED
                        else -> CapabilityState.UNAVAILABLE
                    },
                    when {
                        AcceleratorKind.VULKAN_GPU in runtimeBackends ->
                            "The runtime found a Vulkan device. Compare it against CPU under Models " +
                                "before relying on it."
                        hasVulkan -> "Android reports Vulkan hardware, but this build exposes no Vulkan device"
                        else -> "No Vulkan hardware feature"
                    },
                ),
                AcceleratorCapability(
                    AcceleratorKind.OPENCL_GPU,
                    CapabilityState.UNPROBED,
                    "Android has no public OpenCL capability API; requires a native library and kernel self-test",
                ),
                AcceleratorCapability(
                    AcceleratorKind.HEXAGON_NPU,
                    if (AcceleratorKind.HEXAGON_NPU in runtimeBackends) {
                        CapabilityState.DETECTED_NOT_VALIDATED
                    } else {
                        CapabilityState.UNAVAILABLE
                    },
                    if (AcceleratorKind.HEXAGON_NPU in runtimeBackends) {
                        "The runtime found a Hexagon NPU. Compare it against CPU under Models " +
                            "before relying on it."
                    } else {
                        "This build has no Hexagon backend, which needs the Qualcomm Hexagon SDK at build time"
                    },
                ),
                AcceleratorCapability(
                    AcceleratorKind.LITERT_NPU,
                    CapabilityState.UNPROBED,
                    "Requires a compatible LiteRT-LM package and initialization test",
                ),
            ),
            profileFingerprint = sha256(fingerprintMaterial).take(20),
        )
    }

    private fun thermalName(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown ($status)"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
