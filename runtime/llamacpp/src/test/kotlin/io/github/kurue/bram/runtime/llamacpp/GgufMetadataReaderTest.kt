package io.github.kurue.bram.runtime.llamacpp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufMetadataReaderTest {
    @Test
    fun readsReferenceMetadataWithoutTensorPayload() {
        val bytes = gguf(
            "general.architecture" to stringValue("lfm2"),
            "general.name" to stringValue("LFM2.5 2.6B"),
            "general.file_type" to u32Value(2),
            "lfm2.context_length" to u32Value(32_768),
            "lfm2.block_count" to u32Value(30),
            "tokenizer.chat_template" to stringValue("{{ messages }}"),
            "tokenizer.ggml.tokens" to arrayOfStrings("a", "b", "c"),
        )

        val metadata = GgufMetadataReader().read(ByteArrayInputStream(bytes))

        assertEquals(3, metadata.version)
        assertEquals(42, metadata.tensorCount)
        assertEquals("lfm2", metadata.architecture)
        assertEquals("LFM2.5 2.6B", metadata.name)
        assertEquals("Q4_0", metadata.quantization)
        assertEquals(32_768, metadata.trainedContextTokens)
        assertEquals(30, metadata.layerCount)
        assertTrue(metadata.hasChatTemplate)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonGgufInput() {
        GgufMetadataReader().read(ByteArrayInputStream("nope".toByteArray()))
    }

    @Test
    fun readsTensorTypeCountsFromTheTensorTable() {
        val bytes = gguf(
            "general.architecture" to stringValue("qwen35"),
            "general.file_type" to u32Value(15),
        ) + tensorTable(
            "token_embd.weight" to 6,
            "blk.0.attn_q.weight" to 2,
            "blk.0.attn_k.weight" to 2,
            "blk.0.ffn_up.weight" to 2,
            "blk.0.ffn_down.weight" to 2,
            "output_norm.weight" to 0,
            "output.weight" to 6,
        )

        val metadata = GgufMetadataReader().read(ByteArrayInputStream(bytes))

        assertEquals(mapOf("q4_0" to 4, "q5_0" to 2, "f32" to 1), metadata.tensorTypeCounts)
    }

    /** A tensor table of (name, ggml type id) entries with plausible 2-d shapes. */
    private fun tensorTable(vararg tensors: Pair<String, Int>): ByteArray =
        ByteArrayOutputStream().apply {
            tensors.forEach { (name, type) ->
                writeString(name)
                writeU32(2)  // dimensions
                writeU64(4_096)
                writeU64(2_560)
                writeU32(type)
                writeU64(0)  // byte offset
            }
        }.toByteArray()

    private fun gguf(vararg metadata: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        write("GGUF".toByteArray())
        writeU32(3)
        writeU64(42)
        writeU64(metadata.size.toLong())
        metadata.forEach { (key, value) ->
            writeString(key)
            write(value)
        }
    }.toByteArray()

    private fun stringValue(value: String): ByteArray = ByteArrayOutputStream().apply {
        writeU32(8)
        writeString(value)
    }.toByteArray()

    private fun u32Value(value: Int): ByteArray = ByteArrayOutputStream().apply {
        writeU32(4)
        writeU32(value)
    }.toByteArray()

    private fun arrayOfStrings(vararg values: String): ByteArray = ByteArrayOutputStream().apply {
        writeU32(9)
        writeU32(8)
        writeU64(values.size.toLong())
        values.forEach { writeString(it) }
    }.toByteArray()

    private fun ByteArrayOutputStream.writeString(value: String) {
        val bytes = value.toByteArray()
        writeU64(bytes.size.toLong())
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun ByteArrayOutputStream.writeU64(value: Long) {
        write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
    }
}
