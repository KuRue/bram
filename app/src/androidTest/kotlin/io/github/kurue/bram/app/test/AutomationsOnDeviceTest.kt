package io.github.kurue.bram.app.test

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import io.github.kurue.bram.app.BramApplication
import io.github.kurue.bram.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Automations end to end on the device, without a model: create one from a preset, see it listed
 * with its plain schedule, pause it, run it now anyway (a paused schedule still runs on demand and
 * stays paused), find the run in its history, and delete the definition. The run itself waits
 * for a model, which is the honest state on a model-free emulator.
 */
class AutomationsOnDeviceTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val container get() = ApplicationProvider.getApplicationContext<BramApplication>().container

    @After
    fun cleanUp() = runBlocking {
        // Leave nothing behind for other tests: this test's automation and the run it queued.
        container.automationStore.automations().filter { it.name == NAME }.forEach {
            container.automationStore.remove(it.id)
        }
        container.taskRunner.tasks.value.filter { it.displayName == NAME }.forEach {
            container.taskRunner.cancel(it.id)
            container.taskRunner.delete(it.id)
        }
        container.automationRunner.rescheduleAllNow()
    }

    @Test
    fun createPauseRunNowAndDelete() {
        composeRule.onNodeWithTag("menu-button").performClick()
        composeRule.onNodeWithText("Automations").performClick()
        composeRule.onNodeWithTag("automation-new").performScrollTo().performClick()
        composeRule.onNodeWithTag("automation-name").performTextInput(NAME)
        composeRule.onNodeWithTag("automation-prompt").performTextInput("Summarise my day.")
        composeRule.onNodeWithText("Weekdays").performClick()
        // The default time is 09:00; the preview names the schedule in words.
        composeRule.onNodeWithText("Weekdays at 09:00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("automation-save").performScrollTo().performClick()

        composeRule.waitUntil(TIMEOUT) { exists("automation-row") }
        composeRule.onNodeWithText(NAME).assertIsDisplayed()
        composeRule.onNodeWithText("Weekdays at 09:00").assertIsDisplayed()

        composeRule.onNodeWithText(NAME).performClick()
        composeRule.onNodeWithTag("automation-enabled").performClick()
        composeRule.waitUntil(TIMEOUT) { textExists("Paused: Run now still works") }

        composeRule.onNodeWithTag("automation-run-now").performClick()
        composeRule.waitUntil(TIMEOUT) { exists("automation-run") }
        composeRule.onNodeWithTag("automation-enabled").assertIsOff()
        assertTrue(
            "the run is linked to its automation",
            container.taskRunner.tasks.value.any { it.displayName == NAME && it.automationId != null },
        )

        composeRule.onNodeWithText("Delete automation").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitUntil(TIMEOUT) { !textExists(NAME) || exists("automation-new") }
        assertTrue(
            "deleting the definition keeps its past runs",
            container.taskRunner.tasks.value.any { it.displayName == NAME },
        )
    }

    private fun exists(tag: String) =
        composeRule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun textExists(text: String) =
        runCatching { composeRule.onNodeWithText(text, substring = true).assertExists() }.isSuccess

    private companion object {
        const val NAME = "Device test automation"
        const val TIMEOUT = 10_000L
    }
}
