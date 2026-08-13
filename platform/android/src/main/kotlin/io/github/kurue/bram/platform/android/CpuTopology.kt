package io.github.kurue.bram.platform.android

import java.io.File

/**
 * Reads the device's CPU cluster layout so tuning can build core masks that match the hardware.
 *
 * Cores are grouped by their reported maximum frequency (read from sysfs), most capable group
 * first. The shape is a list of counts: [4, 4] means four fast cores and four slow ones, and the
 * tuning candidate builder turns that into "all cores", "fast cores only", and "fast + mid"
 * masks. Nothing here is authoritative — sysfs may be unreadable, a vendor may not expose
 * frequencies, and one cluster may run many speeds — so every failure path collapses to a single
 * group, which produces no mask candidates and therefore today's default-affinity behavior.
 */
object CpuTopology {

    /** Cluster core counts, most capable cluster first. One group when nothing is readable. */
    fun clusters(): List<Int> {
        val byFrequency = HashMap<Int, Int>()
        var index = 0
        while (File("/sys/devices/system/cpu/cpu$index").exists() && index < 64) {
            val frequency = File(
                "/sys/devices/system/cpu/cpu$index/cpufreq/cpuinfo_max_freq",
            ).takeIf { it.isFile }?.readTextOrNull()?.trim()?.toIntOrNull()
            if (frequency != null) {
                byFrequency[frequency] = (byFrequency[frequency] ?: 0) + 1
            } else {
                // A core without a frequency entry cannot be grouped with confidence; treat the
                // whole device as one group rather than guessing.
                return listOf(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
            }
            index++
        }
        if (byFrequency.isEmpty()) {
            return listOf(Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        }
        return byFrequency.entries.sortedByDescending { it.key }.map { it.value }
    }

    private fun File.readTextOrNull(): String? =
        runCatching { readText() }.getOrNull()
}
