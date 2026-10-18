package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeExportTest {

    private val engine = "a94d563ed801d1da1b8c2432946de07d0231bb3d"
    private val modelHash = "e1a61bf937bc60726e18626e97f7ee9bfd2574d95744c2ed909de98b78006fbe"
    private val fingerprint = "d=device|app=1|engine=$engine|cpu=NEON=1"

    private val device = RecipeDeviceIdentity(
        manufacturer = "samsung",
        model = "SM-S938U1",
        soc = "SM8750",
        androidApi = 37,
        cpuFeatures = CpuFeatures(fp16 = true, dotProduct = true, int8Matmul = true, bf16 = true, sve = false),
        engineCommit = engine,
        availableBackends = setOf(RecipeBackend.CPU, RecipeBackend.VULKAN, RecipeBackend.OPENCL),
    )

    private val model = RecipeModelIdentity(
        architecture = "LFM2",
        quantization = "Q4_0",
        sizeBytes = 1593894912,
        sha256 = modelHash,
        contextTokens = 8192,
    )

    private val profile = ModelProfile(
        id = "profile:1",
        name = "LFM2.5-2.6B-Q4_0",
        modelId = ModelId("model:1"),
        contextTokens = 8192,
        threads = 4,
        batchTokens = 512,
        systemPrompt = "You are my private assistant. Do not tell anyone about PROJECT-FALCON.",
        autoConfiguredNote = "Chose 4 threads after a sweep of the owner's personal machine",
        recipeProvenance = "Bram official (bram.official), revision 2026-10-17.1; recipe samsung-…",
    )

    private fun result(kind: BenchTest.Kind, tokens: Int, depth: Int, rates: List<Double>, before: Double?, after: Double?) =
        BenchResult(
            test = BenchTest(kind, tokens, depth),
            tokPerSec = rates,
            batteryTempBefore = before,
            batteryTempAfter = after,
        )

    private val run = BenchRun(
        id = "bench:1",
        profileId = profile.id,
        profileName = "the owner's 42nd conversation",
        backend = "CPU",
        startedAtEpochMillis = 0,
        fingerprint = fingerprint,
        results = listOf(
            result(BenchTest.Kind.PROMPT, 512, 0, listOf(128.0, 100.8, 76.0), 30.7, 31.2),
            result(BenchTest.Kind.GENERATION, 128, 0, listOf(29.0, 27.9, 26.8), 31.2, 31.6),
            result(BenchTest.Kind.GENERATION, 128, 4096, listOf(21.1, 20.9, 20.7), 31.6, 32.0),
        ),
        config = "CPU · 4 threads · batch 512/0",
    )

    private fun ready(
        device: RecipeDeviceIdentity = this.device,
        model: RecipeModelIdentity = this.model,
        profile: ModelProfile = this.profile,
        run: BenchRun = this.run,
        maintainer: String = "KuRue",
        fingerprint: String = this.fingerprint,
        measurement: BackendMeasurement? = null,
    ): RecipeExport {
        val outcome = RecipeExportBuilder.build(
            device, model, profile, run, maintainer, "2026-10-18", fingerprint, measurement,
        )
        assertTrue("expected a ready draft, got $outcome", outcome is RecipeDraft.Ready)
        return (outcome as RecipeDraft.Ready).export
    }

    @Test
    fun `what it writes is what Bram reads, and the document parses back to the same recipe`() {
        val export = ready()
        val parsed = HardwareRecipe.parse(export.document)
        assertTrue("the exported document was refused: $parsed", parsed is RecipeParse.Parsed)
        assertEquals(export.recipe, (parsed as RecipeParse.Parsed).value)
    }

    @Test
    fun `the index entry carries the file's real hash, and the pair is what a source needs`() {
        val export = ready()
        val again = RecipeExportBuilder.sha256(export.document)
        assertEquals(export.sha256, again)
        assertEquals("${export.recipe.id}.json", export.fileName)
        val entry = org.json.JSONObject(export.indexEntry)
        assertEquals(export.recipe.id, entry.getString("id"))
        assertEquals(export.recipe.version, entry.getInt("version"))
        assertEquals(export.fileName, entry.getString("file"))
        assertEquals(export.sha256, entry.getString("sha256"))
    }

    @Test
    fun `the same inputs write the same bytes, which is what makes the hash mean anything`() {
        assertEquals(ready().document, ready().document)
        assertEquals(ready().sha256, ready().sha256)
    }

    @Test
    fun `a system prompt, a tuning note and a chat name cannot reach the document`() {
        // The export only ever reads named fields; this is the test that says so out loud.
        val document = ready().document
        assertFalse(document.contains("PROJECT-FALCON"))
        assertFalse(document.contains("private assistant"))
        assertFalse(document.contains("personal machine"))
        assertFalse(document.contains("42nd conversation"))
        assertFalse(document.contains("You are"))
    }

    @Test
    fun `the evidence is the run's own numbers, with its spreads and temperatures`() {
        val evidence = ready().recipe.evidence
        assertEquals(101.6, evidence.promptTokPerSec!!, 0.05)
        assertTrue("spread must be recorded", evidence.promptSpread!! > 0.0)
        assertEquals(27.9, evidence.decodeTokPerSec!!, 0.05)
        assertEquals(20.9, evidence.depthDecodeTokPerSec!!, 0.05)
        assertEquals(30.7, evidence.batteryCelsiusStart!!, 0.01)
        assertEquals(32.0, evidence.batteryCelsiusEnd!!, 0.01)
        assertEquals(512, ready().recipe.workload.promptTokens)
        assertEquals(4096, ready().recipe.workload.contextDepth)
        // A CPU run is its own reference, so the format's agreement field stays unmeasured.
        assertEquals(null, evidence.agreement)
    }

    @Test
    fun `an accelerator recipe carries the agreement that let its speed count`() {
        val gpu = profile.copy(backendId = RecipeBackend.OPENCL.profileId)
        val export = ready(
            profile = gpu,
            measurement = BackendMeasurement("OPENCL", "OpenCL", agrees = true, agreement = 0.96, speedup = 1.1),
        )
        assertEquals(0.96, export.recipe.evidence.agreement!!, 0.0001)
        assertEquals(RecipeBackend.OPENCL, export.recipe.proposal.backend)
    }

    @Test
    fun `only settings that were chosen are proposed, so nothing is reset by omission`() {
        val auto = profile.copy(threads = 0, batchTokens = 0, poll = -1, cpuMask = "")
        val proposal = ready(profile = auto).recipe.proposal
        assertEquals(null, proposal.threads)
        assertEquals(null, proposal.batchTokens)
        assertEquals(null, proposal.poll)
        assertEquals(null, proposal.cpuMask)
        // Which are decisions either way, so they are always stated.
        assertEquals(8192, proposal.contextTokens)
        assertEquals(FlashAttentionMode.AUTO, proposal.flashAttention)
        assertEquals(KvCacheType.F16, proposal.kvCache)
    }

    @Test
    fun `it refuses to make a claim it cannot back`() {
        fun reason(device: RecipeDeviceIdentity = this.device, model: RecipeModelIdentity = this.model,
                   profile: ModelProfile = this.profile, run: BenchRun = this.run,
                   fingerprint: String = this.fingerprint, maintainer: String = "KuRue"): String {
            val outcome = RecipeExportBuilder.build(device, model, profile, run, maintainer, "2026-10-18", fingerprint)
            assertTrue("expected NotReady, got $outcome", outcome is RecipeDraft.NotReady)
            return (outcome as RecipeDraft.NotReady).reason
        }
        assertTrue(reason(device = device.copy(engineCommit = "")).contains("engine build"))
        assertTrue(reason(model = model.copy(sha256 = null)).contains("content hash"))
        assertTrue(reason(model = model.copy(architecture = "")).contains("architecture"))
        assertTrue(reason(fingerprint = "d=other").contains("another device"))
        assertTrue(reason(maintainer = "").contains("maintainer"))
        assertTrue(
            reason(run = run.copy(results = run.results.filterNot { it.test.kind == BenchTest.Kind.GENERATION && it.test.depth == 0 }))
                .contains("decode numbers"),
        )
        assertTrue(
            reason(run = run.copy(results = run.results.filterNot { it.test.kind == BenchTest.Kind.PROMPT }))
                .contains("prompt numbers"),
        )
        // A profile whose backend is not in this build cannot be exported as if it ran here.
        assertTrue(
            reason(
                device = device.copy(availableBackends = setOf(RecipeBackend.CPU)),
                profile = profile.copy(backendId = RecipeBackend.OPENCL.profileId),
            ).contains("not compiled"),
        )
        // Nor can one whose backend the format does not describe.
        assertTrue(reason(profile = profile.copy(backendId = "SOMETHING_ELSE")).contains("cannot describe"))
    }

    @Test
    fun `the id is readable and legal for the format`() {
        val id = ready().recipe.id
        assertEquals("samsung-sm-s938u1-sm8750-lfm2-q4_0-cpu", id)
        assertTrue(id.matches(Regex("^[a-z0-9][a-z0-9._:-]{2,120}$")))
    }

    @Test
    fun `a device that cannot be named is refused rather than guessed at`() {
        val anonymous = device.copy(manufacturer = "", model = "", soc = "")
        val outcome = RecipeExportBuilder.build(anonymous, model, profile, run, "KuRue", "2026-10-18", fingerprint)
        assertTrue(outcome is RecipeDraft.NotReady)
        assertTrue((outcome as RecipeDraft.NotReady).reason.contains("who it is for"))
    }
}
