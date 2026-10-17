package io.github.kurue.bram.app.test

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import io.github.kurue.bram.app.MainActivity
import org.junit.Rule
import org.junit.Test

class RecipeSourcesNavigationOnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsShowsBundledRecipeAndRefusesCleartextIndex() {
        compose.onNodeWithTag("menu-button").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Recipe sources").performScrollTo().performClick()
        compose.onNodeWithText("Bundled with Bram · available offline").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Inspect recipes").performScrollTo().performClick()
        compose.onNodeWithText("samsung-sm-s938-sm8750-lfm2.5-2.6b-q4_0-cpu").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recipe-source-url").performScrollTo().performTextInput("http://example.org/index.json")
        compose.onNodeWithTag("recipe-source-review").performScrollTo().performClick()
        try {
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithText("Use an HTTPS URL", substring = true).assertExists() }.isSuccess
            }
        } catch (failure: Throwable) {
            compose.onRoot(useUnmergedTree = true).printToLog("RecipeSourcesNavigation")
            throw failure
        }
        compose.onNodeWithText("Use an HTTPS URL", substring = true).performScrollTo().assertIsDisplayed()
    }
}
