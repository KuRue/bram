package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.CpuFeatures
import io.github.kurue.bram.core.domain.HardwareRecipe
import io.github.kurue.bram.core.domain.RecipeBackend
import io.github.kurue.bram.core.domain.RecipeDeviceIdentity
import io.github.kurue.bram.core.domain.RecipeFidelity
import io.github.kurue.bram.core.domain.RecipeIndex
import io.github.kurue.bram.core.domain.RecipeMatcher
import io.github.kurue.bram.core.domain.RecipeModelIdentity
import io.github.kurue.bram.core.domain.RecipeParse
import io.github.kurue.bram.core.domain.RecipeSourceKind
import io.github.kurue.bram.core.domain.ModelId
import io.github.kurue.bram.core.domain.ModelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The snapshot the app ships. It is one of the formats Bram reads from the outside world, so it
 * gets the same treatment as a download: parse strictly, and check the hash the index declares
 * really is the bytes of the file beside it.
 */
class HardwareRecipeSeedTest {

    private val directory = File("src/main/assets/hardware-recipes/official-v1")

    private fun parseRecipe(): HardwareRecipe {
        val file = File(directory, "samsung-sm-s938-sm8750-lfm2.5-2.6b-q4_0-cpu.json")
        assertTrue("seed recipe missing at ${file.path}", file.isFile)
        val parsed = HardwareRecipe.parse(file.readText())
        assertTrue("seed recipe refused: $parsed", parsed is RecipeParse.Parsed)
        return (parsed as RecipeParse.Parsed).value
    }

    @Test
    fun `the bundled index parses and pins the recipe's bytes`() {
        val indexFile = File(directory, "index.json")
        assertTrue("seed index missing at ${indexFile.path}", indexFile.isFile)
        val parsed = RecipeIndex.parse(indexFile.readText())
        assertTrue("seed index refused: $parsed", parsed is RecipeParse.Parsed)
        val index = (parsed as RecipeParse.Parsed).value

        assertEquals(RecipeSourceKind.OFFICIAL, index.source.kind)
        assertEquals("bram.official", index.source.id)
        assertEquals(1, index.recipes.size)

        val digest = index.recipes.single()
        val recipeBytes = File(directory, digest.file).readBytes()
        assertEquals(
            "the index hash must be the recipe file's own bytes",
            digest.sha256,
            MessageDigest.getInstance("SHA-256").digest(recipeBytes).joinToString("") { "%02x".format(it) },
        )
        assertEquals(parseRecipe().id, digest.id)
        assertEquals(parseRecipe().version, digest.version)
    }

    @Test
    fun `the seed carries the measurement it says it does`() {
        val recipe = parseRecipe()
        assertEquals("2026-10-17", recipe.evidenceDate)
        assertEquals(RecipeBackend.CPU, recipe.proposal.backend)
        assertEquals(4, recipe.proposal.threads)
        assertEquals(512, recipe.proposal.batchTokens)
        // The evidence is a quote of a real run, not a rounded claim.
        assertEquals(100.8, recipe.evidence.promptTokPerSec!!, 0.01)
        assertEquals(20.9, recipe.evidence.depthDecodeTokPerSec!!, 0.01)
        assertTrue("spread must be recorded too", recipe.evidence.promptSpread!! > 0.0)
        assertEquals(180, recipe.limits.expiresAfterDays)
        assertTrue(recipe.limits.revalidateOn.isNotEmpty())
    }

    @Test
    fun `the seed matches the phone it was measured on, and is only a seed elsewhere`() {
        val recipe = parseRecipe()
        val phone = RecipeDeviceIdentity(
            manufacturer = "samsung",
            model = "SM-S938U1",
            soc = "SM8750",
            androidApi = 37,
            cpuFeatures = CpuFeatures(fp16 = true, dotProduct = true, int8Matmul = true, bf16 = true, sve = false),
            engineCommit = recipe.runtime.engineCommits.single(),
            availableBackends = setOf(RecipeBackend.CPU, RecipeBackend.VULKAN),
        )
        val model = RecipeModelIdentity(
            architecture = "lfm2",
            quantization = "Q4_0",
            sizeBytes = recipe.model.sizeBytes!!,
            sha256 = recipe.model.sha256,
            contextTokens = 8192,
        )
        val profile = ModelProfile(
            id = "profile:1",
            name = recipe.model.architectures.single(),
            modelId = ModelId("model:1"),
            contextTokens = 8192,
        )

        val exact = RecipeMatcher.candidates(listOf(recipe), phone, model, profile).single()
        assertEquals(RecipeFidelity.EXACT, exact.fidelity)
        assertTrue(exact.reasons.isEmpty())

        // A different phone with the same chip gets the settings as a suggestion, and says why.
        val other = phone.copy(model = "SM-S931U")
        val seed = RecipeMatcher.candidates(listOf(recipe), other, model, profile).single()
        assertEquals(RecipeFidelity.SEED, seed.fidelity)
        assertTrue(seed.reasons.isNotEmpty())

        // A phone with a different chip does not see it at all.
        assertTrue(
            RecipeMatcher.candidates(listOf(recipe), phone.copy(soc = "MT6991"), model, profile).isEmpty(),
        )
        assertNotEquals(0, recipe.match.socs.size)
    }
}
