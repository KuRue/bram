package io.github.kurue.bram.app.test

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.app.MainActivity
import io.github.kurue.bram.app.OnboardingStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The first-run guide on a fresh install: it appears over an empty Bram, scans the phone, gives a
 * recommendation, and stays gone once skipped. Needs no models, so it runs on the emulator.
 *
 * [BramTestRunner] marks the guide finished for every other test; this one clears the mark and any
 * seeded endpoints for itself and restores both afterwards.
 */
class OnboardingOnDeviceTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var previousEndpoints: String? = null
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun freshInstallState() {
        val container = ApplicationProvider.getApplicationContext<BramApplication>().container
        val hasLocalModels = runBlocking {
            container.localModelStore.list().isNotEmpty() || container.liteRtLmStore.list().isNotEmpty()
        }
        assumeTrue("this device has imported models, so it is not a fresh install", !hasLocalModels)
        val endpoints = prefs(ENDPOINTS_PREFS)
        previousEndpoints = endpoints.getString(ENDPOINTS_KEY, null)
        endpoints.edit().remove(ENDPOINTS_KEY).commit()
        prefs(OnboardingStore.PREFERENCES).edit().putBoolean(OnboardingStore.KEY_COMPLETED, false).commit()
    }

    @After
    fun restore() {
        scenario?.close()
        prefs(OnboardingStore.PREFERENCES).edit().putBoolean(OnboardingStore.KEY_COMPLETED, true).commit()
        prefs(ENDPOINTS_PREFS).edit().apply {
            if (previousEndpoints == null) remove(ENDPOINTS_KEY) else putString(ENDPOINTS_KEY, previousEndpoints)
        }.commit()
    }

    @Test
    fun theGuideScansThePhoneRecommendsAModelAndStaysGoneWhenSkipped() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(LAUNCH_TIMEOUT_MS) { exists("onboarding") }
        composeRule.onNodeWithText("Set up Bram").assertIsDisplayed()

        // The scan runs as the guide opens; the findings sit behind Details.
        composeRule.waitUntil(SCAN_TIMEOUT_MS) { exists("onboarding-recommendation") }
        assertTrue("findings start collapsed", !exists("onboarding-findings"))
        composeRule.onNodeWithTag("onboarding-details").performClick()
        composeRule.onNodeWithTag("onboarding-findings").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Memory").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("NPU").performScrollTo().assertIsDisplayed()
        // The advice itself depends on the device ("Start with" on a phone, "Best option" where
        // GGUF cannot run, like this emulator), so only its presence is asserted here; its content
        // is covered by ModelAdvisorTest.
        composeRule.onNodeWithTag("onboarding-recommendation").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("onboarding-connect-server").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("onboarding-skip").performScrollTo().performClick()
        composeRule.waitUntil(LAUNCH_TIMEOUT_MS) { !exists("onboarding") }
        composeRule.onNodeWithTag("home").assertIsDisplayed()
        assertTrue(
            "skipping must be remembered",
            prefs(OnboardingStore.PREFERENCES).getBoolean(OnboardingStore.KEY_COMPLETED, false),
        )

        // A relaunch with still no models goes to the chat, not back to the guide.
        scenario?.recreate()
        composeRule.waitUntil(LAUNCH_TIMEOUT_MS) { exists("home") }
        composeRule.waitForIdle()
        assertTrue("the guide came back after it was skipped", !exists("onboarding"))
    }

    @Test
    fun connectAServerOpensTheServerFormAndBackReturnsToTheGuide() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(LAUNCH_TIMEOUT_MS) { exists("onboarding") }
        composeRule.waitUntil(SCAN_TIMEOUT_MS) { exists("onboarding-recommendation") }

        composeRule.onNodeWithTag("onboarding-connect-server").performScrollTo().performClick()
        composeRule.waitUntil(LAUNCH_TIMEOUT_MS) { textExists("Base URL") }
        assertTrue("the guide must step aside for the form", !exists("onboarding"))

        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitUntil(LAUNCH_TIMEOUT_MS) { exists("onboarding") }
    }

    private fun exists(tag: String): Boolean =
        composeRule.onAllNodesWithTagCount(tag) > 0

    private fun textExists(text: String): Boolean =
        runCatching { composeRule.onNodeWithText(text, substring = true).assertExists() }.isSuccess

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size

    private fun prefs(name: String) =
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private companion object {
        const val ENDPOINTS_PREFS = "bram.remote_endpoints"
        const val ENDPOINTS_KEY = "endpoints.v1"
        const val LAUNCH_TIMEOUT_MS = 15_000L
        const val SCAN_TIMEOUT_MS = 30_000L
    }
}
