package io.github.kurue.bram.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceBenchStoreTest {

    private val nativeJson = JSONObject(
        """{"bufferBytes":268435456,"passes":5,"results":[
            {"threads":1,"gbPerSec":17.5},{"threads":4,"gbPerSec":58.25},{"threads":8,"gbPerSec":55.0}],
            "peakGbPerSec":58.25,"peakThreads":4}""",
    )

    @Test
    fun `the native sweep parses with its peak`() {
        val parsed = DeviceBenchStore.fromNativeJson(nativeJson, measuredAtEpochMillis = 42, fingerprint = "fp")!!
        assertEquals(58.25, parsed.peakGbPerSecond, 1e-9)
        assertEquals(4, parsed.peakThreads)
        assertEquals(3, parsed.byThreads.size)
    }

    @Test
    fun `a stored measurement round-trips`() {
        val original = DeviceBenchStore.fromNativeJson(nativeJson, measuredAtEpochMillis = 42, fingerprint = "fp")!!
        assertEquals(original, DeviceBenchStore.decode(DeviceBenchStore.encode(original)))
    }

    @Test
    fun `corrupt or empty data reads as not measured`() {
        assertNull(DeviceBenchStore.decode("{not json"))
        assertNull(DeviceBenchStore.fromNativeJson(JSONObject("{}"), 0, "fp"))
    }
}
