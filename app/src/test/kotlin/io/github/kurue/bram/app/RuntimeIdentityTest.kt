package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ModelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeIdentityTest {

    private fun model(
        sha256: String = "e1a61bf937bc60726e18626e97f7ee9bfd2574d95744c2ed909de98b78006fbe",
        parts: List<String> = emptyList(),
        fileName: String = "LFM2.5-2.6B-Q4_0.gguf",
    ) = LocalModelRecord(
        ModelId("model:1"), "LFM2.5-2.6B-Q4_0", fileName, "", "/data/models/$fileName", 1593894912, sha256,
        parts = parts, ggufVersion = 3, architecture = "lfm2", quantization = "Q4_0",
        trainedContextTokens = 131072, layerCount = 30, hasChatTemplate = true,
    )

    private fun value(label: String, engine: String = "a94d563ed801d1da1b8c2432946de07d0231bb3d",
                      device: String = "d=device|app=1", model: LocalModelRecord? = model()): String =
        runtimeIdentityLines(engine, device, model).first { it.label == label }.value

    @Test
    fun `the engine commit is shown in full, because a recipe has to name it exactly`() {
        assertEquals("a94d563ed801d1da1b8c2432946de07d0231bb3d", value("Engine"))
    }

    @Test
    fun `the model hash is shown in full, not the twelve characters the profile card has room for`() {
        assertEquals("e1a61bf937bc60726e18626e97f7ee9bfd2574d95744c2ed909de98b78006fbe", value("Model SHA-256"))
    }

    @Test
    fun `a build that reported nothing says so rather than showing a blank line`() {
        val lines = runtimeIdentityLines("", "", null)
        assertTrue(lines.all { it.value.isNotBlank() })
        assertEquals("unknown — the native runtime did not report a commit", value("Engine", engine = ""))
        assertEquals("unavailable until a local model is imported", value("Model SHA-256", model = null))
    }

    @Test
    fun `a split model explains why it cannot supply the hash a recipe wants`() {
        val split = model(parts = listOf("model-00001-of-00002.gguf", "model-00002-of-00002.gguf"))
        assertTrue(value("Model file", model = split).contains("split into parts"))
    }

    @Test
    fun `the device identity is the same string measurements are keyed by`() {
        assertEquals("d=device|app=1", value("Device"))
    }
}
