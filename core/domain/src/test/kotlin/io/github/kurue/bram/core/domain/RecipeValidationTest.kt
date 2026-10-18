package io.github.kurue.bram.core.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecipeValidationTest {
    private val current = ModelProfile("profile", "Current", ModelId("model"), 4096, threads = 4)
    private val proposed = current.copy(threads = 2)
    private val tokens = (0 until 24).toList()

    private fun trial(millis: Long = 100, predictions: List<Int> = tokens) = RecipeTrial(predictions, millis / 2, millis / 2)

    @Test fun `both pairs must win and their order is reversed before full context validation`() = runBlocking {
        val calls = mutableListOf<Pair<Int, Int>>()
        val references = mutableListOf<Int>()
        val decision = RecipeValidationRunner.run(current, proposed, 1024,
            reference = { _, pad -> references += pad; tokens },
            trial = { profile, _, pad -> calls += profile.threads to pad; trial(if (profile == current) 100 else 80) })
        assertEquals(RecipeVerdict.APPLIED, decision.verdict)
        assertEquals(listOf(4 to 1024, 2 to 1024, 2 to 1024, 4 to 1024, 2 to 3840), calls)
        assertEquals(listOf(1024, 3840), references)
        assertEquals(200L, decision.incumbentMillis)
        assertEquals(160L, decision.proposedMillis)
    }

    @Test fun `noise and slower results keep the incumbent without checking more candidates`() = runBlocking {
        for (challenger in listOf(96L, 100L, 120L)) {
            var count = 0
            val decision = RecipeValidationRunner.run(current, proposed, 0, reference = { _, _ -> tokens },
                trial = { profile, _, _ -> count++; trial(if (profile == current) 100 else challenger) })
            assertEquals(RecipeVerdict.KEPT, decision.verdict)
            assertEquals(2, count)
        }
    }

    @Test fun `one lucky improvement cannot win if the second pair loses`() = runBlocking {
        var count = 0
        val decision = RecipeValidationRunner.run(current, proposed, 0, reference = { _, _ -> tokens },
            trial = { profile, _, _ -> count++; trial(if (profile == current) 100 else if (count == 2) 80 else 104) })
        assertEquals(RecipeVerdict.KEPT, decision.verdict)
        assertEquals(4, count)
    }

    @Test fun `a perfectly matching prefix is not complete agreement`() = runBlocking {
        val decision = RecipeValidationRunner.run(current, proposed, 0, reference = { _, _ -> tokens },
            trial = { profile, _, _ -> trial(80, if (profile == proposed) tokens.take(1) else tokens) })
        assertEquals(RecipeVerdict.REJECTED, decision.verdict)
    }

    @Test fun `fast but incorrect proposals are rejected`() = runBlocking {
        val decision = RecipeValidationRunner.run(current, proposed, 0, reference = { _, _ -> tokens },
            trial = { profile, _, _ -> trial(10, if (profile == proposed) List(24) { 999 } else tokens) })
        assertEquals(RecipeVerdict.REJECTED, decision.verdict)
    }

    @Test fun `a combination that fails at its intended context is never applied`() = runBlocking {
        val decision = RecipeValidationRunner.run(current, proposed.copy(contextTokens = 8192), 0,
            reference = { _, _ -> tokens },
            trial = { profile, _, pad -> trial(if (profile == current) 100 else 80, if (pad > 0) emptyList() else tokens) })
        assertEquals(RecipeVerdict.REJECTED, decision.verdict)
        assertTrue(decision.detail.contains("intended context"))
    }

    @Test fun `zero timings and incomplete references cannot grant approval`() = runBlocking {
        val badTime = RecipeValidationRunner.run(current, proposed, 0, reference = { _, _ -> tokens },
            trial = { _, _, _ -> RecipeTrial(tokens, 0, 10) })
        assertEquals(RecipeVerdict.INTERRUPTED, badTime.verdict)
        for (reference in listOf(emptyList(), tokens.take(2))) {
            assertTrue(runCatching {
                RecipeValidationRunner.run(current, proposed, 0, reference = { _, _ -> reference },
                    trial = { _, _, _ -> error("Must not measure without a full reference") })
            }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun `cancellation and environment changes escape before an approval can be returned`() = runBlocking {
        for (failure in listOf(CancellationException("cancelled"), IllegalStateException("thermal drift"))) {
            var checkpoints = 0
            val caught = runCatching { RecipeValidationRunner.run(current, proposed, 0,
                reference = { _, _ -> tokens }, trial = { profile, _, _ -> trial(if (profile == current) 100 else 80) },
                checkpoint = { if (++checkpoints == 4) throw failure }) }.exceptionOrNull()
            assertSame(failure, caught)
        }
    }

    @Test fun `a proposal preserves prompts sampling identity and resource limits`() {
        val original = current.copy(systemPrompt = "Keep my instructions", streamExperts = true, streamCacheMb = 256,
            streamDenseAnon = true, streamOverlap = true, sampler = SamplerSettings(temperature = 0.3f))
        val updated = RecipeProposal(threads = 2, decodeThreads = 1, batchTokens = 128).applyTo(original)
        assertEquals(original.copy(threads = 2, decodeThreads = 1, batchTokens = 128), updated)
        assertEquals(original, RecipeProposal().applyTo(original))
        assertEquals("OPENCL", RecipeProposal(backend = RecipeBackend.OPENCL).applyTo(current).backendId)
    }

    private val model = LocalModelRecord(ModelId("model"), "Test", "file.gguf", "", "", 100, "a".repeat(64),
        ggufVersion = 3, architecture = "llama", quantization = "q4_0", trainedContextTokens = 8192, layerCount = 4, hasChatTemplate = true)

    @Test fun `unavailable settings and policy exclusions are refused before a load`() {
        val available = setOf(RecipeBackend.CPU, RecipeBackend.VULKAN)
        assertNull(recipeEligibility(current, model, 8, available))
        for (invalid in listOf(current.copy(threads = 9), current.copy(decodeThreads = 65),
            current.copy(cpuMask = "100"), current.copy(cpuMask = "0"), current.copy(contextTokens = 16384),
            current.copy(batchTokens = 8192), current.copy(ubatchTokens = 1024),
            current.copy(backendId = "VULKAN"), current.copy(backendId = "OPENCL"), current.copy(backendId = "unknown"))) {
            assertNotNull("must refuse $invalid", recipeEligibility(invalid, model, 8, available))
        }
    }

    @Test fun `configuration identity changes for every runtime ingredient used by recipe validation`() {
        val identity = current.recipeConfigurationIdentity()
        for (different in listOf(current.copy(threads = 2), current.copy(decodeThreads = 2), current.copy(contextTokens = 8192),
            current.copy(backendId = "OPENCL"), current.copy(thinkingEnabled = true), current.copy(cpuMask = "f"),
            current.copy(streamCacheMb = 128), current.copy(streamOverlap = true), current.copy(batchTokens = 128))) {
            assertNotEquals(identity, different.recipeConfigurationIdentity())
        }
    }
}
