package io.github.kurue.bram.app.test

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Bound by the probe pack's only JNI function; see engines/probe/src/main/cpp/probe.c. */
object EnginePackProbe {
    @JvmStatic external fun probe(): Int
}

/**
 * The engine-pack mechanism on a real Android version: Bram discovers an installed pack through
 * package visibility, confirms it is signed with Bram's own key, and loads a native library from
 * the pack's directory into its own process. Skips when the probe pack is not installed
 * (`./gradlew :engines:probe:installDebug`).
 */
class EnginePackProbeOnDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm = context.packageManager

    @Test
    fun aSignedPackIsDiscoveredAndItsLibraryLoadsInBramsProcess() {
        // Whether the pack is installed is asked through the test APK, which can see it by name,
        // so that Bram failing to discover it fails the test instead of skipping it.
        val testPm = InstrumentationRegistry.getInstrumentation().context.packageManager
        assumeTrue(
            "probe pack not installed",
            runCatching { testPm.getApplicationInfo(PACK, 0) }.isSuccess,
        )
        val installed = runCatching { pm.getApplicationInfo(PACK, 0) }.getOrNull()
        assertTrue("Bram must be able to see an installed pack", installed != null)

        val found = pm.queryIntentServices(Intent(ACTION), PackageManager.GET_META_DATA)
            .map { it.serviceInfo }
            .firstOrNull { it.packageName == PACK }
        assertTrue("the pack must be discoverable by its intent action", found != null)
        assertEquals("probe", found!!.metaData?.getString("io.github.kurue.bram.engine.id"))

        assertEquals(
            "a pack must be signed with Bram's own key",
            PackageManager.SIGNATURE_MATCH,
            pm.checkSignatures(context.packageName, PACK),
        )

        val library = File(installed!!.nativeLibraryDir, "libbram_engine_probe.so")
        assertTrue("library extracted at ${library.path}", library.isFile)
        System.load(library.absolutePath)
        assertEquals(0x6272616d, EnginePackProbe.probe())
    }

    private companion object {
        const val PACK = "io.github.kurue.bram.engine.probe"
        const val ACTION = "io.github.kurue.bram.ENGINE_PACK"
    }
}
