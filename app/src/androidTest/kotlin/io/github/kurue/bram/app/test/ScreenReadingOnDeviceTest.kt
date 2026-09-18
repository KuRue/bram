package io.github.kurue.bram.app.test

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kurue.bram.app.AccessibilityBridge
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.app.MainActivity
import io.github.kurue.bram.app.ReadScreenTool
import io.github.kurue.bram.app.ScreenAccess
import io.github.kurue.bram.app.ScreenReader
import io.github.kurue.bram.app.ScreenTreeReader
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screen reading against the real accessibility stack, as far as instrumentation allows.
 *
 * The system binding itself cannot be exercised from a test: `am instrument` force-stops the
 * package, and the instrumentation's own accessibility automation suppresses other services for
 * the length of the run, so Bram's service never binds. What a test *can* do is the rest, against
 * the real thing:
 *  - the enabled check reads the secure setting the user's toggle writes;
 *  - the tree walk runs over the actual active window through the instrumentation's accessibility
 *    connection, which is the same `AccessibilityNodeInfo` API the service uses;
 *  - the tool turns that snapshot into the model-facing JSON.
 *
 * The bind itself is verified manually: with the service enabled, `dumpsys accessibility` lists
 * "Screen reading for Bram" under bound services once the app is launched normally.
 */
@RunWith(AndroidJUnit4::class)
class ScreenReadingOnDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ApplicationProvider.getApplicationContext<BramApplication>()

    private var previousServices = "null"
    private var previousEnabled = "null"

    @Before
    fun saveSettings() {
        previousServices = shell("settings get secure enabled_accessibility_services")
        previousEnabled = shell("settings get secure accessibility_enabled")
    }

    @After
    fun restoreSettings() {
        writeSetting("enabled_accessibility_services", previousServices)
        writeSetting("accessibility_enabled", previousEnabled)
    }

    @Test
    fun turnedOffIsReportedWithTheFix() {
        writeSetting("enabled_accessibility_services", "")
        writeSetting("accessibility_enabled", "0")
        composeRule.waitForIdle()

        val result = JSONObject(runBlocking { ReadScreenTool { context.container.screenAccess() }.execute("{}") })
        assertEquals("accessibility_off", result.getJSONObject("error").getString("code"))
    }

    @Test
    fun theEnabledSettingIsRecognized() {
        writeSetting("enabled_accessibility_services", ourComponent)
        writeSetting("accessibility_enabled", "1")
        assertTrue(AccessibilityBridge.enabled(context))

        writeSetting("enabled_accessibility_services", "")
        writeSetting("accessibility_enabled", "0")
        assertFalse(AccessibilityBridge.enabled(context))
    }

    @Test
    fun theRealWindowWalksIntoReadableNodes() {
        composeRule.waitForIdle()
        val uiAutomation = instrumentation.uiAutomation
        // The automation's flags choose itself as the active service; this is the same window the
        // user sees, which during the test is Bram's own UI.
        val snapshot = uiAutomation.getRootInActiveWindow()?.let(ScreenTreeReader::walk)
        assertNotNull("no active window to walk", snapshot)
        val nodes = snapshot!!.nodes
        assertTrue("walk returned no nodes", nodes.isNotEmpty())
        assertTrue(
            "no labeled or interactive nodes: ${nodes.take(10)}",
            nodes.any { it.text.isNotBlank() || it.description.isNotBlank() || it.clickable },
        )
    }

    @Test
    fun theToolTurnsThatSnapshotIntoModelFacingJson() {
        composeRule.waitForIdle()
        val root = instrumentation.uiAutomation.rootInActiveWindow
        assertNotNull(root)
        val reader = ScreenReader { ScreenTreeReader.walk(root) }
        val result = JSONObject(
            runBlocking { ReadScreenTool { ScreenAccess.Ready(reader) }.execute("{}") },
        )
        // The active window is whatever is on top, which in a test can be a system dialog rather
        // than Bram; the answer names its app either way.
        assertTrue(result.getString("app").isNotBlank())
        assertTrue(result.getInt("count") > 0)
        assertEquals(result.getInt("count"), result.getJSONArray("nodes").length())
    }

    /** The component string the system matches against what Settings writes. */
    private val ourComponent = "${context.packageName}/$BramAccessibilityServiceName"

    private fun writeSetting(name: String, value: String) {
        if (value.isBlank() || value == "null") {
            shell("settings delete secure $name")
        } else {
            shell("settings put secure $name $value")
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { stream -> stream.readBytes().toString(Charsets.UTF_8).trim() }

    private companion object {
        const val BramAccessibilityServiceName = "io.github.kurue.bram.app.BramAccessibilityService"
    }
}
