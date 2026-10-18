package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeMatchingTest {

    private val recipe = HardwareRecipe(
        id = "samsung-sm-s938-sm8750-lfm2.5-2.6b-q4_0-cpu",
        version = 1,
        maintainer = "Bram",
        evidenceDate = "2026-10-17",
        match = RecipeDeviceMatch(
            manufacturers = listOf("samsung"),
            models = listOf("sm-s938u1"),
            socs = listOf("sm8750", "snapdragon 8 elite"),
            requireCpuFeatures = setOf(RecipeCpuFeature.FP16, RecipeCpuFeature.DOTPROD, RecipeCpuFeature.I8MM),
            minAndroidApi = 35,
        ),
        runtime = RecipeRuntime(
            engineCommits = listOf("a94d563ed801d1da1b8c2432946de07d0231bb3d"),
            settingsSchemaVersion = 1,
            availableBackends = setOf(RecipeBackend.CPU),
        ),
        model = RecipeModel(
            architectures = listOf("lfm2"),
            quantizations = listOf("q4_0"),
            sha256 = "e1a61bf937bc60726e18626e97f7ee9bfd2574d95744c2ed909de98b78006fbe",
            sizeBytes = 1593894912,
            minContextTokens = 4096,
        ),
        workload = RecipeWorkload(512, 128, 4096, RecipeWorkloadPreference.CHAT),
        proposal = RecipeProposal(
            backend = RecipeBackend.CPU,
            contextTokens = 8192,
            threads = 4,
            batchTokens = 512,
        ),
        evidence = RecipeEvidence(method = "Bram benchmark", agreement = 1.0, promptTokPerSec = 100.8),
        limits = RecipeLimits(expiresAfterDays = 180, revalidateOn = setOf(RecipeRevalidation.ENGINE_COMMIT), incompatible = emptyList()),
    )

    private val phone = RecipeDeviceIdentity(
        manufacturer = "samsung",
        model = "SM-S938U1",
        soc = "SM8750",
        androidApi = 37,
        cpuFeatures = CpuFeatures(fp16 = true, dotProduct = true, int8Matmul = true, bf16 = true, sve = false),
        engineCommit = "a94d563ed801d1da1b8c2432946de07d0231bb3d",
        availableBackends = setOf(RecipeBackend.CPU, RecipeBackend.VULKAN),
    )

    private val lfm = RecipeModelIdentity(
        architecture = "lfm2",
        quantization = "Q4_0",
        sizeBytes = 1593894912,
        sha256 = "e1a61bf937bc60726e18626e97f7ee9bfd2574d95744c2ed909de98b78006fbe",
        contextTokens = 8192,
    )

    private val profile = ModelProfile(
        id = "profile:1",
        name = "LFM2.5-2.6B-Q4_0",
        modelId = ModelId("model:1"),
        contextTokens = 8192,
    )

    private fun candidates(
        device: RecipeDeviceIdentity = phone,
        model: RecipeModelIdentity = lfm,
        profile: ModelProfile = this.profile,
        recipes: List<HardwareRecipe> = listOf(recipe),
    ) = RecipeMatcher.candidates(recipes, device, model, profile)

    @Test
    fun `a recipe measured on this exact phone is exact, with nothing to revalidate`() {
        val candidate = candidates().single()
        assertEquals(RecipeFidelity.EXACT, candidate.fidelity)
        assertTrue(candidate.reasons.isEmpty())
    }

    @Test
    fun `another phone entirely is not a match`() {
        assertTrue(candidates(device = phone.copy(model = "SM-S928B", soc = "SM8650")).isEmpty())
    }

    @Test
    fun `the same chip in another model is a seed, and says why`() {
        val candidate = candidates(device = phone.copy(model = "SM-S931U")).single()
        assertEquals(RecipeFidelity.SEED, candidate.fidelity)
        assertEquals(listOf("measured on a different model in the same family"), candidate.reasons)
    }

    @Test
    fun `a measurement from another engine build is a seed that names both builds`() {
        val candidate = candidates(device = phone.copy(engineCommit = "deadbee")).single()
        assertEquals(RecipeFidelity.SEED, candidate.fidelity)
        assertEquals(
            listOf(
                "measured on engine a94d563ed801d1da1b8c2432946de07d0231bb3d, this build is deadbee",
            ),
            candidate.reasons,
        )
    }

    @Test
    fun `a phone without the required kernels never sees the recipe`() {
        val withoutI8mm = phone.copy(
            cpuFeatures = CpuFeatures(fp16 = true, dotProduct = true, int8Matmul = false, bf16 = false, sve = false),
        )
        assertTrue(candidates(device = withoutI8mm).isEmpty())
        // A device whose cpuinfo could not be read claims nothing, so a recipe requiring features is not a match.
        assertTrue(candidates(device = phone.copy(cpuFeatures = null)).isEmpty())
    }

    @Test
    fun `a backend this build does not carry is not offered`() {
        val npuRecipe = recipe.copy(
            runtime = recipe.runtime.copy(availableBackends = setOf(RecipeBackend.CPU, RecipeBackend.HEXAGON)),
            proposal = recipe.proposal.copy(backend = RecipeBackend.HEXAGON),
        )
        assertTrue(candidates(recipes = listOf(npuRecipe)).isEmpty())
        val withNpu = phone.copy(availableBackends = phone.availableBackends + RecipeBackend.HEXAGON)
        assertEquals(RecipeFidelity.EXACT, candidates(device = withNpu, recipes = listOf(npuRecipe)).single().fidelity)
    }

    @Test
    fun `another quantization is not a candidate for a seed`() {
        assertTrue(candidates(model = lfm.copy(quantization = "q8_0")).isEmpty())
        assertTrue(candidates(model = lfm.copy(architecture = "qwen3")).isEmpty())
    }

    @Test
    fun `a context below what the evidence describes is not a candidate`() {
        assertTrue(candidates(model = lfm.copy(contextTokens = 2048)).isEmpty())
    }

    @Test
    fun `an older Android than the recipe demands is not a candidate`() {
        assertTrue(candidates(device = phone.copy(androidApi = 34)).isEmpty())
    }

    @Test
    fun `a different model file of the same shape is a seed`() {
        val candidate = candidates(model = lfm.copy(sha256 = "f".repeat(64))).single()
        assertEquals(RecipeFidelity.SEED, candidate.fidelity)
        assertEquals(listOf("measured on different model bytes"), candidate.reasons)
    }

    @Test
    fun `an unhashed local file matches on shape but is only a seed`() {
        val candidate = candidates(model = lfm.copy(sha256 = null)).single()
        assertEquals(RecipeFidelity.SEED, candidate.fidelity)
    }

    @Test
    fun `only the settings that differ are proposed`() {
        val withKv = recipe.copy(proposal = recipe.proposal.copy(kvCache = KvCacheType.F16))
        val current = profile.copy(threads = 0, batchTokens = 512, kvCacheType = KvCacheType.Q8_0)
        val candidate = candidates(profile = current, recipes = listOf(withKv)).single()
        assertEquals(
            listOf(
                ProposalChange("threads", "Threads", "Auto", "4"),
                ProposalChange("kvCache", "KV cache", "Compact", "Exact"),
            ),
            candidate.changes,
        )
    }

    @Test
    fun `a proposal that changes nothing is an empty list`() {
        val same = profile.copy(threads = 4, batchTokens = 512)
        assertTrue(candidates(profile = same).single().changes.isEmpty())
    }

    @Test
    fun `a proposed backend is shown by name, not by profile id`() {
        val cpuRecipe = recipe.copy(proposal = recipe.proposal.copy(backend = RecipeBackend.VULKAN))
        // Everything else already matches, so the backend is the only change on the list.
        val settled = profile.copy(threads = 4, batchTokens = 512)
        val changes = candidates(device = phone, recipes = listOf(cpuRecipe), profile = settled).single().changes
        assertEquals(listOf(ProposalChange("backend", "Backend", "CPU", "Vulkan")), changes)
    }

    @Test
    fun `exact candidates sort before seeds`() {
        val seed = recipe.copy(id = "aaa-another-family-seed", match = recipe.match.copy(models = listOf("sm-s931u")))
        val ordered = candidates(recipes = listOf(seed, recipe))
        assertEquals(listOf(recipe.id, seed.id), ordered.map { it.recipe.id })
        assertEquals(listOf(RecipeFidelity.EXACT, RecipeFidelity.SEED), ordered.map { it.fidelity })
    }

    @Test
    fun `a mask and a poll level read the way the settings screens write them`() {
        val proposal = RecipeProposal(cpuMask = "3f", poll = 0, loadMode = LoadMode.NO_MMAP, flashAttention = FlashAttentionMode.ON)
        val current = profile.copy(cpuMask = "", poll = -1, loadMode = LoadMode.AUTO, flashAttention = FlashAttentionMode.AUTO)
        assertEquals(
            listOf(
                ProposalChange("cpuMask", "CPU mask", "All cores", "0x3f"),
                ProposalChange("poll", "Poll", "Default", "0"),
                ProposalChange("flashAttention", "Attention", "Auto", "On"),
                ProposalChange("loadMode", "Load mode", "Auto", "No mmap"),
            ),
            proposal.changesFor(current),
        )
    }
}
