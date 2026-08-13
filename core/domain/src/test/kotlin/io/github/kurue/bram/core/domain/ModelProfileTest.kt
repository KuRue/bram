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
    fun `a default profile runs the original attention and kv defaults`() {
        // AUTO/F16 is exactly what Bram ran before the settings existed, so an untouched profile
        // cannot change behavior on upgrade.
        val profile = ModelProfile.defaultFor(model())
        assertEquals(FlashAttentionMode.AUTO, profile.flashAttention)
        assertEquals(KvCacheType.F16, profile.kvCacheType)
    }

    @Test
    fun `a default profile runs the original batch configuration`() {
        // Zero means llama.cpp's defaults (512/128), which is what Bram always loaded with, so an
        // untouched profile cannot change prompt speed or memory on upgrade.
        val profile = ModelProfile.defaultFor(model())
        assertEquals(0, profile.batchTokens)
        assertEquals(0, profile.ubatchTokens)
        assertTrue(profile.batchTuneNote.isBlank())
    }

    @Test
    fun `a tuned profile remembers what was chosen and when`() {
        val profile = ModelProfile.defaultFor(model()).copy(
            batchTokens = 1_024,
            ubatchTokens = 128,
            batchTuneNote = "Tuned batch 1024/128 on Vulkan: 42 prompt tok/s, matching the CPU reference",
            batchTunedAtEpochMillis = 123L,
        )
        assertEquals(1_024, profile.batchTokens)
        assertEquals(128, profile.ubatchTokens)
        assertEquals(123L, profile.batchTunedAtEpochMillis)
    }

    @Test
    fun `unknown wire values fall back to the safe defaults`() {
        assertEquals(FlashAttentionMode.AUTO, FlashAttentionMode.fromWire("banana"))
        assertEquals(FlashAttentionMode.ON, FlashAttentionMode.fromWire("on"))
        assertEquals(KvCacheType.F16, KvCacheType.fromWire("banana"))
        assertEquals(KvCacheType.Q8_0, KvCacheType.fromWire("q8_0"))
    }

    @Test
    fun `a default profile runs the original threadpool and load configuration`() {
        // Every tuning field defaults to "today": device threads, default affinity, backend poll,
        // normal priority, mmap load, no hexagon flags. An untouched profile cannot change
        // behavior on upgrade.
        val profile = ModelProfile.defaultFor(model())
        assertEquals(0, profile.threads)
        assertEquals("", profile.cpuMask)
        assertFalse(profile.cpuStrict)
        assertEquals(-1, profile.poll)
        assertEquals(ThreadPriority.NORMAL, profile.threadPriority)
        assertEquals(LoadMode.AUTO, profile.loadMode)
        assertEquals(HexFlags(), profile.hexFlags)
        assertTrue(profile.hexFlags.isDefault)
    }

    @Test
    fun `sanitizedRuntime clamps and canonicalizes the tuning fields`() {
        val wild = ModelProfile.defaultFor(model()).copy(
            threads = -4,
            cpuMask = "0xFC",
            cpuStrict = true,
            poll = 500,
            hexFlags = HexFlags(useHmx = true, opBatch = 99, nDev = -2),
        ).sanitizedRuntime()
        assertEquals(0, wild.threads)
        assertEquals("fc", wild.cpuMask)
        assertTrue(wild.cpuStrict)
        assertEquals(100, wild.poll)
        assertEquals(0xF, wild.hexFlags.opBatch)
        assertEquals(0, wild.hexFlags.nDev)
    }

    @Test
    fun `strict placement without a mask names nothing and is dropped`() {
        val profile = ModelProfile.defaultFor(model()).copy(cpuMask = "", cpuStrict = true)
            .sanitizedRuntime()
        assertFalse(profile.cpuStrict)
    }

    @Test
    fun `unknown tuning wire values fall back to the safe defaults`() {
        assertEquals(ThreadPriority.NORMAL, ThreadPriority.fromWire("banana"))
        assertEquals(ThreadPriority.HIGH, ThreadPriority.fromWire("high"))
        assertEquals(LoadMode.AUTO, LoadMode.fromWire("banana"))
        assertEquals(LoadMode.NO_MMAP, LoadMode.fromWire("no_mmap"))
    }

    @Test
    fun `a cpu mask is canonical hex or empty, never filtered garbage`() {
        assertEquals("fc", canonicalCpuMask("0xFC"))
        assertEquals("3f", canonicalCpuMask(" 0x3f "))
        assertEquals("", canonicalCpuMask("not-a-mask"))
        assertEquals("", canonicalCpuMask(""))
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
