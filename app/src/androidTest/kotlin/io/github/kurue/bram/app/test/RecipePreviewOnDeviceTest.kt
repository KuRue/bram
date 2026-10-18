package io.github.kurue.bram.app.test

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.kurue.bram.app.*
import io.github.kurue.bram.core.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecipePreviewOnDeviceTest {
    @get:Rule val compose = createComposeRule()

    private fun fixture(): AppUiState {
        val app = ApplicationProvider.getApplicationContext<BramApplication>()
        val sources = RecipeSources(java.io.File(app.cacheDir, "unused-preview-sources"), { name ->
            app.assets.open("hardware-recipes/official-v1/$name").use { it.readBytes() }
        }).load()
        val recipe = sources.bundled!!.recipes.single()
        val model = LocalModelRecord(ModelId("preview-model"), "Preview model", "model.gguf", "", "",
            recipe.model.sizeBytes!!, recipe.model.sha256!!, ggufVersion = 3, architecture = "lfm2",
            quantization = "q4_0", trainedContextTokens = 32768, layerCount = 30, hasChatTemplate = true)
        return AppUiState(recipeSources = sources, profiles = listOf(ModelProfile("preview-profile", "My profile", model.id, 8192, threads = 2)),
            localModels = listOf(model), measurementFingerprint = "preview-device-build", recipeRuntimeAvailable = true,
            recipeDeviceIdentity = RecipeDeviceIdentity("samsung", "SM-S938U1", "SM8750", 37,
                CpuFeatures(true, true, true, true, false), recipe.runtime.engineCommits.single(), setOf(RecipeBackend.CPU)),
            deviceProfile = DeviceProfile("samsung", "SM-S938U1", "SM8750", "17", "arm64-v8a", 8,
                12_000_000_000, 8_000_000_000, 500_000_000, 100_000_000_000, 50_000_000_000, "none", emptyList(), "preview"))
    }

    @Test fun previewDispatchesTheChosenDeltaAndShowsFingerprintBoundFailure() {
        val state = mutableStateOf(fixture())
        val profile = state.value.profiles.single()
        val preview = recipePreviews(state.value, profile.id).single()
        var chosen: Pair<String, String>? = null
        compose.setContent {
            BramTheme {
                RecipeSourcesScreen(state.value.recipeSources, onBack = {}, onReview = {}, onAccept = {},
                    onDismissReview = {}, onRollback = {}, onRemove = {}, applicationState = state.value,
                    onValidate = { id, key -> chosen = id to key })
            }
        }
        compose.onNodeWithText("Exact match · local validation still required").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Threads: 2 → 4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recipe-validate").performScrollTo().performClick()
        assertEquals(profile.id to preview.key, chosen)
        assertEquals(2, state.value.profiles.single().threads)

        compose.runOnIdle {
            state.value = state.value.copy(isValidatingAccelerator = true)
        }
        compose.onNodeWithTag("recipe-validate").assertIsNotEnabled()
        compose.runOnIdle {
            state.value = state.value.copy(isValidatingAccelerator = false, recipeValidationRecords = listOf(
                RecipeValidationRecord(preview.key, RecipeVerdict.REJECTED, "CPU agreement failed")))
        }
        compose.onNodeWithTag("recipe-validation-record").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry validation and apply if faster").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(measurementFingerprint = "new-driver-build") }
        compose.onNodeWithTag("recipe-validation-record").assertDoesNotExist()
    }

    @Test fun missingRuntimeCannotApplyEvenAnExactPublishedRecipe() {
        val state = fixture().copy(recipeRuntimeAvailable = false)
        compose.setContent {
            BramTheme {
                RecipeSourcesScreen(state.recipeSources, onBack = {}, onReview = {}, onAccept = {},
                    onDismissReview = {}, onRollback = {}, onRemove = {}, applicationState = state,
                    onValidate = { _, _ -> fail("Unavailable runtime must not validate") })
            }
        }
        compose.onNodeWithText("The local GGUF runtime is unavailable on this device").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recipe-validate").performScrollTo().assertIsNotEnabled()
    }
}
