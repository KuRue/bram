package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.BenchResult
import io.github.kurue.bram.core.domain.BenchRun
import io.github.kurue.bram.core.domain.BenchTest
import io.github.kurue.bram.core.domain.CpuFeatures
import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.core.domain.ModelProfile
import io.github.kurue.bram.core.domain.RecipeBackend
import io.github.kurue.bram.core.domain.RecipeDeviceIdentity
import io.github.kurue.bram.core.domain.RecipeDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeExportStateTest {

    private val engine = "a94d563ed801d1da1b8c2432946de07d0231bb3d"
    private val fingerprint = "d=device|app=1|engine=$engine|cpu=NEON=1"
    private val modelId = ModelId("model:1")

    private fun model(sha: String = "a".repeat(64), parts: List<String> = emptyList()) = LocalModelRecord(
        modelId, "LFM2.5-2.6B-Q4_0", "LFM2.5-2.6B-Q4_0.gguf", "", "/data/models/x.gguf", 1593894912, sha,
        parts = parts, ggufVersion = 3, architecture = "lfm2", quantization = "Q4_0",
        trainedContextTokens = 131072, layerCount = 30, hasChatTemplate = true,
    )

    private val profile = ModelProfile(
        id = "profile:1", name = "LFM2.5-2.6B-Q4_0", modelId = modelId, contextTokens = 8192,
        threads = 4, batchTokens = 512,
    )

    private fun run(id: String, startedAt: Long, fingerprint: String = this.fingerprint, profileId: String = profile.id) = BenchRun(
        id = id, profileId = profileId, profileName = "profile", backend = "CPU", startedAtEpochMillis = startedAt,
        fingerprint = fingerprint, config = "CPU · 4 threads · batch 512/0",
        results = listOf(
            BenchResult(BenchTest(BenchTest.Kind.PROMPT, 512, 0), listOf(120.0, 100.0)),
            BenchResult(BenchTest(BenchTest.Kind.GENERATION, 128, 0), listOf(28.0, 27.0)),
        ),
    )

    private fun state(
        runs: List<BenchRun> = listOf(run("bench:1", 1_000)),
        profiles: List<ModelProfile> = listOf(profile),
        models: List<LocalModelRecord> = listOf(model()),
        device: RecipeDeviceIdentity? = RecipeDeviceIdentity(
            "samsung", "SM-S938U1", "SM8750", 37,
            CpuFeatures(fp16 = true, dotProduct = true, int8Matmul = true, bf16 = true, sve = false),
            engine, setOf(RecipeBackend.CPU, RecipeBackend.OPENCL),
        ),
    ) = AppUiState(
        profiles = profiles,
        localModels = models,
        benchRuns = runs,
        measurementFingerprint = fingerprint,
        recipeDeviceIdentity = device,
    )

    @Test
    fun `a profile with a matching benchmark becomes a recipe`() {
        val outcome = recipeExportFor(state(), profile.id, "KuRue", today = "2026-10-18")
        assertTrue("expected Ready, got $outcome", outcome is RecipeDraft.Ready)
        val export = (outcome as RecipeDraft.Ready).export
        assertEquals("2026-10-18", export.recipe.evidenceDate)
        assertEquals(110.0, export.recipe.evidence.promptTokPerSec!!, 0.001)
        assertEquals(4, export.recipe.proposal.threads)
    }

    @Test
    fun `a benchmark from another build or device is not evidence for this one`() {
        val other = state(runs = listOf(run("bench:1", 1_000, fingerprint = "d=other")))
        val outcome = recipeExportFor(other, profile.id, "KuRue")
        assertTrue(outcome is RecipeDraft.NotReady)
        assertTrue((outcome as RecipeDraft.NotReady).reason.contains("Run a benchmark"))
    }

    @Test
    fun `the newest matching benchmark is the one quoted`() {
        val runs = listOf(run("bench:old", 1_000), run("bench:new", 9_000), run("bench:other-profile", 10_000, profileId = "profile:2"))
        val outcome = recipeExportFor(state(runs = runs), profile.id, "KuRue", today = "2026-10-18")
        val export = (outcome as RecipeDraft.Ready).export
        // The newest run for *this* profile, not the newest overall.
        assertEquals(110.0, export.recipe.evidence.promptTokPerSec!!, 0.001)
    }

    @Test
    fun `without a benchmark it explains what to do, rather than exporting a guess`() {
        val outcome = recipeExportFor(state(runs = emptyList()), profile.id, "KuRue")
        assertTrue(outcome is RecipeDraft.NotReady)
        assertTrue((outcome as RecipeDraft.NotReady).reason.contains("Run a benchmark"))
    }

    @Test
    fun `a split model is refused because a recipe names one file's hash`() {
        val parts = model(parts = listOf("model-00001-of-00002.gguf", "model-00002-of-00002.gguf"))
        val outcome = recipeExportFor(state(models = listOf(parts)), profile.id, "KuRue")
        assertTrue(outcome is RecipeDraft.NotReady)
        assertTrue((outcome as RecipeDraft.NotReady).reason.contains("split model"))
    }

    @Test
    fun `an unknown phone identity is refused rather than exported anonymously`() {
        val outcome = recipeExportFor(state(device = null), profile.id, "KuRue")
        assertTrue(outcome is RecipeDraft.NotReady)
        assertTrue((outcome as RecipeDraft.NotReady).reason.contains("identity"))
    }
}
