package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RecipePreviewTest {
    private val assets = File("src/main/assets/hardware-recipes/official-v1")
    private val snapshot = RecipeSources.readSnapshot(File(assets, "index.json").readBytes(), false) { File(assets, it).readBytes() }
    private val recipe = snapshot.recipes.single()
    private val model = LocalModelRecord(ModelId("model"), "LFM", "model.gguf", "", "/model.gguf",
        recipe.model.sizeBytes!!, recipe.model.sha256!!, ggufVersion = 3, architecture = "lfm2", quantization = "q4_0",
        trainedContextTokens = 32768, layerCount = 30, hasChatTemplate = true)
    private val profile = ModelProfile("profile", "My profile", model.id, 8192, threads = 2)
    private val device = RecipeDeviceIdentity("samsung", "SM-S938U1", "SM8750", 37,
        CpuFeatures(true, true, true, true, false), recipe.runtime.engineCommits.single(), setOf(RecipeBackend.CPU))
    private val state = AppUiState(
        profiles = listOf(profile), localModels = listOf(model), recipeDeviceIdentity = device,
        recipeRuntimeAvailable = true,
        measurementFingerprint = "driver/build/model-test", recipeSources = RecipeSourcesUi(bundled = snapshot),
        deviceProfile = DeviceProfile("samsung", "SM-S938U1", "SM8750", "17", "arm64-v8a", 8,
            12_000_000_000, 8_000_000_000, 500_000_000, 100_000_000_000, 50_000_000_000,
            "none", emptyList(), "device-test"),
    )

    @Test fun `preview names exact match and computes changes without touching the saved profile`() {
        val preview = recipePreviews(state, profile.id).single()
        assertEquals(RecipeFidelity.EXACT, preview.candidate.fidelity)
        assertEquals(4, preview.proposed.threads)
        assertEquals(2, state.profiles.single().threads)
        assertTrue(preview.candidate.changes.any { it.field == "threads" && it.from == "2" && it.to == "4" })
        assertNull(preview.blocked)
    }

    @Test fun `an unaccepted source review never supplies configuration candidates`() {
        val unaccepted = state.copy(recipeSources = RecipeSourcesUi(review = RecipeSourceReview("https://example.org/index.json", snapshot, null)))
        assertTrue(recipePreviews(unaccepted, profile.id).isEmpty())
    }

    @Test fun `failure records are bound to source model runtime and both setting combinations`() {
        val preview = recipePreviews(state, profile.id).single()
        val record = RecipeValidationRecord(preview.key, RecipeVerdict.REJECTED, "CPU agreement failed")
        val excluded = state.copy(recipeValidationRecords = listOf(record))
        assertEquals(record, recipePreviews(excluded, profile.id).single().record)
        val revisions = listOf(
            excluded.copy(measurementFingerprint = "different driver or build"),
            excluded.copy(profiles = listOf(profile.copy(threads = 1))),
            excluded.copy(localModels = listOf(model.copy(sha256 = "b".repeat(64)))),
            excluded.copy(recipeSources = RecipeSourcesUi(bundled = snapshot.copy(indexJson = snapshot.indexJson + "\n"))),
        )
        revisions.forEach { changed ->
            val next = recipePreviews(changed, profile.id).single()
            assertNotEquals(preview.key, next.key)
            assertNull(next.record)
        }
    }

    @Test fun `unknown model bytes and split files cannot be validated as exact content`() {
        for (changed in listOf(model.copy(sha256 = ""), model.copy(parts = listOf("part-2.gguf")))) {
            val preview = recipePreviews(state.copy(localModels = listOf(changed)), profile.id).single()
            assertEquals(RecipeFidelity.SEED, preview.candidate.fidelity)
            assertNotNull(preview.blocked)
        }
    }

    @Test fun `a runtime without an engine identity never permits applying a recipe`() {
        val preview = recipePreviews(state.copy(recipeDeviceIdentity = device.copy(engineCommit = "")), profile.id).single()
        assertTrue(preview.blocked!!.contains("runtime build"))
    }

    @Test fun `external sources claiming the official id cannot acquire its selection priority`() {
        val external = snapshot.copy(index = snapshot.index.copy(source = snapshot.index.source.copy(kind = RecipeSourceKind.EXTERNAL)))
        val choices = recipePreviews(state.copy(recipeSources = RecipeSourcesUi(bundled = snapshot,
            sources = listOf(StoredRecipeSource("https://example.org/index.json", external)))), profile.id)
        assertTrue(choices.first().official)
        assertFalse(choices.last().official)
    }
}
