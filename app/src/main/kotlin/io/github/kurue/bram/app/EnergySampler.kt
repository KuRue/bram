package io.github.kurue.bram.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.github.kurue.bram.core.domain.BatteryPower
import io.github.kurue.bram.core.domain.EnergySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Samples whole-device battery power around a piece of work.
 *
 * `BATTERY_PROPERTY_CURRENT_NOW` needs no permission and is real current, not an estimate — but
 * only while the phone runs on battery: plugged in, the charger feeds the load and the reading is
 * charge current. So [measure] returns no sample when the phone is plugged in, rather than a
 * number that means something else. The fuel gauge updates slowly on some devices (around once a
 * second), so short tests collect few samples; the count is kept so a reader can judge.
 */
class EnergySampler(private val context: Context) {
    private val battery = context.getSystemService(BatteryManager::class.java)

    fun onBattery(): Boolean = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) == 0

    /** Idle watts over [millis], or null when a reading is not meaningful. */
    suspend fun baseline(millis: Long = 1_500): Double? {
        if (!onBattery()) return null
        val readings = mutableListOf<Double>()
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            readWatts()?.let(readings::add)
            delay(SAMPLE_INTERVAL_MILLIS)
        }
        return readings.takeIf { it.isNotEmpty() }?.average()
    }

    /** Runs [block] while sampling, returning its value and the average watts during it. */
    suspend fun <T> measure(baselineWatts: Double?, block: suspend () -> T): Pair<T, EnergySample?> {
        if (baselineWatts == null || !onBattery()) return block() to null
        val readings = mutableListOf<Double>()
        val value = coroutineScope {
            val sampler = launch(Dispatchers.Default) {
                while (isActive) {
                    readWatts()?.let { synchronized(readings) { readings += it } }
                    delay(SAMPLE_INTERVAL_MILLIS)
                }
            }
            try {
                block()
            } finally {
                sampler.cancel()
            }
        }
        val taken = synchronized(readings) { readings.toList() }
        // A charger plugged in mid-test turns the readings into charge current.
        if (taken.isEmpty() || !onBattery()) return value to null
        return value to EnergySample(averageWatts = taken.average(), baselineWatts = baselineWatts, samples = taken.size)
    }

    private fun readWatts(): Double? {
        val current = battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: return null
        val voltage = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: return null
        return BatteryPower.watts(current, voltage)
    }

    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private companion object {
        const val SAMPLE_INTERVAL_MILLIS = 250L
    }
}
