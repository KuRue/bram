package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProfileTest {

    private fun model(
        contextTokens: Int = 8_192,
        backendId: String = "",
        thinking: Boolean = false,
    ) = LocalModelRecord(
        id = ModelId("local:abc"),
        displayName = "LFM2.5-2.6B-Q4_0",
        fileName = "LFM2.5-2.6B-Q4_0.gguf",
        contentUri = "content://x",
        localPath = "/data/x.gguf",
        fileSizeBytes = 1,
        sha256 = "abc",
        ggufVersion = 3,
        architecture = "lfm2",
        quantization = "Q4_0",
        trainedContextTokens = 128_000,
        layerCount = 30,
        hasChatTemplate = true,
        preferredContextTokens = contextTokens,
        preferredBackendId = backendId,
        thinkingEnabled = thinking,
    )

    @Test
    fun `a default profile carries the settings the model was imported with`() {
        // These used to live on the model record, so a migration that dropped them would silently
        // reset a user's context size and processor choice.
        val profile = ModelProfile.defaultFor(model(contextTokens = 4_096, backendId = "HEXAGON", thinking = true))
        assertEquals(4_096, profile.contextTokens)
        assertEquals("HEXAGON", profile.backendId)
        assertTrue(profile.thinkingEnabled)
        assertTrue(profile.isDefault)
    }

    @Test
    fun `a default profile is named after the model and derived from its id`() {
        val profile = ModelProfile.defaultFor(model())
        assertEquals("LFM2.5-2.6B-Q4_0", profile.name)
        // Stable, so calling this twice for one model cannot produce two defaults.
        assertEquals(ModelProfile.defaultFor(model()).id, profile.id)
    }

    @Test
    fun `zero temperature is greedy`() {
        assertTrue(SamplerSettings(temperature = 0f).isGreedy)
        assertFalse(SamplerSettings(temperature = 0.01f).isGreedy)
    }

    @Test
    fun `sanitizing clamps values a stored profile could otherwise load with`() {
        val wild = SamplerSettings(
            temperature = 12f,
            topP = 4f,
            topK = -5,
            repeatPenalty = 0.1f,
            repeatLastTokens = 999_999,
        ).sanitized()
        assertEquals(2f, wild.temperature, 0.001f)
        assertEquals(1f, wild.topP, 0.001f)
        assertEquals(0, wild.topK)
        // Below 1.0 would amplify repetition rather than damp it.
        assertEquals(1f, wild.repeatPenalty, 0.001f)
        assertEquals(2_048, wild.repeatLastTokens)
    }

    @Test
    fun `sanitizing leaves ordinary settings untouched`() {
        val ordinary = SamplerSettings()
        assertEquals(ordinary, ordinary.sanitized())
    }
}
