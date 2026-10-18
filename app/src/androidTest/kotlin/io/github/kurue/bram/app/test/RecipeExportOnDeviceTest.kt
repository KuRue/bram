package io.github.kurue.bram.app.test

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.kurue.bram.app.AppUiState
import io.github.kurue.bram.app.BramTheme
import io.github.kurue.bram.app.RecipeExportSection
import io.github.kurue.bram.core.domain.BenchResult
import io.github.kurue.bram.core.domain.BenchRun
import io.github.kurue.bram.core.domain.BenchTest
import io.github.kurue.bram.core.domain.CpuFeatures
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.core.domain.ModelProfile
import io.github.kurue.bram.core.domain.RecipeBackend
import io.github.kurue.bram.core.domain.RecipeDeviceIdentity
import io.github.kurue.bram.core.domain.RecipeExport
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The share surface: what the person sees before a measurement leaves the phone. */
class RecipeExportOnDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val engine = "a94d563ed801d1da1b8c2432946de07d0231bb3d"
    private val fingerprint = "d=device|app=1|engine=$engine|cpu=NEON=1"
    private val profile = ModelProfile(
        id = "profile:1", name = "LFM2.5-2.6B-Q4_0", modelId = ModelId("model:1"), contextTokens = 8192,
        threads = 4, batchTokens = 512, systemPrompt = "Never mention PROJECT-FALCON.",
    )
    private val model = LocalModelRecord(
        ModelId("model:1"), "LFM2.5-2.6B-Q4_0", "LFM2.5-2.6B-Q4_0.gguf", "", "/data/models/x.gguf",
        1593894912, "e1a61bf937bc60726e18626e97f7ee9bfd2574d95744c2ed909de98b78006fbe",
        ggufVersion = 3, architecture = "lfm2", quantization = "Q4_0",
        trainedContextTokens = 131072, layerCount = 30, hasChatTemplate = true,
    )

    private fun state(benchRuns: List<BenchRun>) = AppUiState(
        profiles = listOf(profile),
        localModels = listOf(model),
        benchRuns = benchRuns,
        measurementFingerprint = fingerprint,
        recipeDeviceIdentity = RecipeDeviceIdentity(
            "samsung", "SM-S938U1", "SM8750", 37,
            CpuFeatures(fp16 = true, dotProduct = true, int8Matmul = true, bf16 = true, sve = false),
            engine, setOf(RecipeBackend.CPU),
        ),
    )

    private val run = BenchRun(
        id = "bench:1", profileId = profile.id, profileName = "profile", backend = "CPU",
        startedAtEpochMillis = 1_000, fingerprint = fingerprint, config = "CPU · 4 threads · batch 512/0",
        results = listOf(
            BenchResult(BenchTest(BenchTest.Kind.PROMPT, 512, 0), listOf(120.0, 100.0), batteryTempBefore = 30.0, batteryTempAfter = 31.0),
            BenchResult(BenchTest(BenchTest.Kind.GENERATION, 128, 0), listOf(28.0, 27.0), batteryTempBefore = 31.0, batteryTempAfter = 32.0),
        ),
    )

    @Test
    fun theDocumentCanBePreviewedAndSavedAndCarriesNothingThePersonWrote() {
        val state = state(listOf(run))
        var saved: RecipeExport? = null
        compose.setContent {
            // The real screen scrolls; the preview lands below the fold on a phone-sized view.
            BramTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RecipeExportSection(state, state.profiles.first(), onSave = { saved = it })
                }
            }
        }
        compose.onNodeWithTag("recipe-maintainer").performTextInput("KuRue")
        compose.onNodeWithText("Save recipe file").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recipe-export-preview").performScrollTo().performClick()
        compose.onNodeWithText("bram.hardware-recipe", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("recipe-export-save").performScrollTo().performClick()
        compose.runOnIdle {
            assertNotNull("the save action must hand over the document", saved)
            val document = saved!!.document
            assertTrue(document.contains("\"schema\": \"bram.hardware-recipe\""))
            assertTrue(document.contains("e1a61bf937bc60726e18626e97f7ee9bfd2574d95744c2ed909de98b78006fbe"))
            // The profile's system prompt is in the state this section was given; it must not
            // reach the document.
            assertTrue("a system prompt leaked into the export", !document.contains("PROJECT-FALCON"))
        }
    }

    @Test
    fun withoutAMeasurementItSaysWhatIsMissingInsteadOfExportingARecipe() {
        val state = state(emptyList())
        compose.setContent {
            BramTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RecipeExportSection(state, state.profiles.first(), onSave = {})
                }
            }
        }
        compose.onNodeWithTag("recipe-export-not-ready").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Run a benchmark", substring = true).assertIsDisplayed()
    }
}
