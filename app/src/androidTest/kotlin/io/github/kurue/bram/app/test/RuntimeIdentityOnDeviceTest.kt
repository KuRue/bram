package io.github.kurue.bram.app.test

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.github.kurue.bram.app.BramTheme
import io.github.kurue.bram.app.RuntimeIdentityLine
import io.github.kurue.bram.app.RuntimeIdentityCard
import org.junit.Rule
import org.junit.Test

/**
 * A recipe records the engine commit and the model file's SHA-256, values only Bram knew until this
 * card existed. It renders on its own here, because on a phone it sits in a bottom sheet whose
 * viewport can hide a newly added row: `RuntimeIdentityTest` covers which values are chosen, this
 * covers that they are shown whole and labelled.
 */
class RuntimeIdentityOnDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val engine = "a94d563ed801d1da1b8c2432946de07d0231bb3d"
    private val sha = "e1a61bf937bc60726e18626e97f7ee9bfd2574d95744c2ed909de98b78006fbe"

    @Test fun theCardShowsWhatARecipeMustNameAndKeepsTheHashWhole() {
        compose.setContent {
            BramTheme {
                RuntimeIdentityCard(
                    listOf(
                        RuntimeIdentityLine("Engine", engine),
                        RuntimeIdentityLine("Device", "d=device|app=1"),
                        RuntimeIdentityLine("Model file", "LFM2.5-2.6B-Q4_0.gguf"),
                        RuntimeIdentityLine("Model SHA-256", sha),
                    ),
                )
            }
        }
        compose.onNodeWithTag("runtime-identity").assertIsDisplayed()
        compose.onNodeWithText("Runtime identity").assertIsDisplayed()
        compose.onNodeWithText("Select a value to copy it.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Engine").assertIsDisplayed()
        compose.onNodeWithText(engine).assertIsDisplayed()
        compose.onNodeWithText("Model SHA-256").assertIsDisplayed()
        // The profile card has room for twelve characters; this one has to be the whole hash,
        // because a recipe is checked against it.
        compose.onNodeWithText(sha).assertIsDisplayed()
    }
}
