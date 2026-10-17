package io.github.kurue.bram.app.test

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.kurue.bram.app.AutoConfigureOverlay
import io.github.kurue.bram.app.AutoConfigureProgress
import io.github.kurue.bram.app.BramTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Model-free checks of the real configuration overlay's stopping and policy states. */
class ConfigurationControlsOnDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cancelIsAvailableWhileWaitingAndDoesNotClaimSuccess() {
        val progress = mutableStateOf(AutoConfigureProgress(
            profileId = "test", modelName = "Test model", step = 0, total = 0,
            current = "Waiting for the phone to cool…", waitingForCooldown = true,
        ))
        var cancels = 0
        compose.setContent {
            BramTheme {
                AutoConfigureOverlay(progress.value, cooldownOverride = false,
                    onContinueAnyway = {}, onDismiss = {}, onCancel = {
                        cancels++
                        progress.value = progress.value.copy(stopping = true)
                    })
            }
        }
        compose.onNodeWithTag("cancel-auto-configure").performScrollTo().performClick()
        compose.onNodeWithText("Cancelling…").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, cancels)
            progress.value = progress.value.copy(
                finished = true, stopping = false, waitingForCooldown = false,
                current = "Configuration cancelled. Saved profile settings were kept.",
            )
        }
        compose.onNodeWithText("Configuration results").assertIsDisplayed()
        compose.onNodeWithText("Configuration cancelled. Saved profile settings were kept.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
    }

    @Test fun excludedBackendHasAVisibleReasonRatherThanAFailedMeasurement() {
        val reason = "Vulkan: excluded from automatic configuration by the current validation policy."
        compose.setContent {
            BramTheme {
                AutoConfigureOverlay(AutoConfigureProgress(
                    profileId = "test", modelName = "Test model", step = 0, total = 0,
                    current = "Checking CPU…", exclusions = listOf(reason),
                ), cooldownOverride = false, onContinueAnyway = {}, onDismiss = {}, onCancel = {})
            }
        }
        compose.onNodeWithText(reason).assertIsDisplayed()
        compose.onNodeWithTag("cancel-auto-configure").performScrollTo().assertIsDisplayed()
    }
}
