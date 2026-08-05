package io.github.kurue.bram.runtime.llamacpp

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GgufMetadata(
    val version: Int,
    val tensorCount: Long,
    val architecture: String,
    val name: String?,
    val quantization: String,
    val trainedContextTokens: Int,
    val layerCount: Int,
    val hasChatTemplate: Boolean,
)

/** Reads only the bounded GGUF header and metadata table; tensor bytes are never loaded. */
class GgufMetadataReader {
    fun read(input: InputStream): GgufMetadata {
        val reader = LittleEndianReader(input)
        val magic = reader.bytes(4).toString(Charsets.US_ASCII)
        require(magic == "GGUF") { "Not a GGUF file (missing GGUF header)" }

        val version = reader.u32().toInt()
        require(version in 2..3) { "Unsupported GGUF version $version" }
        val tensorCount = reader.u64Bounded(MAX_TENSORS, "tensor count")
        val metadataCount = reader.u64Bounded(MAX_METADATA_ENTRIES, "metadata count").toInt()
        val values = HashMap<String, Any?>(metadataCount.coerceAtMost(512))

        repeat(metadataCount) {
            val key = reader.string(MAX_KEY_BYTES)
            val type = reader.u32().toInt()
            val value = reader.value(type, 0)
            if (key in INTERESTING_KEYS || key.endsWith(".context_length") || key.endsWith(".block_count")) {
                values[key] = value
            }
        }

        val architecture = values["general.architecture"] as? String
            ?: throw IllegalArgumentException("GGUF does not declare general.architecture")
        val context = values["$architecture.context_length"].asPositiveInt()
            ?: values.entries.firstNotNullOfOrNull { (key, value) ->
                value.asPositiveInt().takeIf { key.endsWith(".context_length") }
            }
            ?: 0
        val layers = values["$architecture.block_count"].asPositiveInt()
            ?: values.entries.firstNotNullOfOrNull { (key, value) ->
                value.asPositiveInt().takeIf { key.endsWith(".block_count") }
            }
            ?: 0
        val fileType = (values["general.file_type"] as? Number)?.toInt()

        return GgufMetadata(
            version = version,
            tensorCount = tensorCount,
            architecture = architecture,
            name = values["general.name"] as? String,
            quantization = fileTypeName(fileType),
            trainedContextTokens = context,
            layerCount = layers,
            hasChatTemplate = !((values["tokenizer.chat_template"] as? String).isNullOrBlank()),
        )
    }

    private fun fileTypeName(value: Int?): String = when (value) {
        0 -> "F32"
        1 -> "F16"
        2 -> "Q4_0"
        3 -> "Q4_1"
        7 -> "Q8_0"
        8 -> "Q5_0"
        9 -> "Q5_1"
        10 -> "Q2_K"
        11 -> "Q3_K_S"
        12 -> "Q3_K_M"
        13 -> "Q3_K_L"
        14 -> "Q4_K_S"
        15 -> "Q4_K_M"
        16 -> "Q5_K_S"
        17 -> "Q5_K_M"
        18 -> "Q6_K"
        19 -> "IQ2_XXS"
        20 -> "IQ2_XS"
        21 -> "Q2_K_S"
        22 -> "IQ3_XS"
        23 -> "IQ3_XXS"
        24 -> "IQ1_S"
        25 -> "IQ4_NL"
        26 -> "IQ3_S"
        27 -> "IQ3_M"
        28 -> "IQ2_S"
        29 -> "IQ2_M"
        30 -> "IQ4_XS"
        31 -> "IQ1_M"
        32 -> "BF16"
        36 -> "TQ1_0"
        37 -> "TQ2_0"
        38 -> "MXFP4_MOE"
        39 -> "NVFP4"
        40 -> "Q1_0"
        41 -> "Q2_0"
        null -> "Unknown"
        else -> "GGUF type $value"
    }

    private fun Any?.asPositiveInt(): Int? = (this as? Number)?.toLong()
        ?.takeIf { it in 1..Int.MAX_VALUE }
        ?.toInt()

    private companion object {
        const val MAX_TENSORS = 1_000_000L
        const val MAX_METADATA_ENTRIES = 1_000_000L
        const val MAX_KEY_BYTES = 16_384
        val INTERESTING_KEYS = setOf(
            "general.architecture",
            "general.name",
            "general.file_type",
            "tokenizer.chat_template",
        )
    }
}

private class LittleEndianReader(private val input: InputStream) {
    fun bytes(count: Int): ByteArray {
        require(count >= 0)
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw EOFException("Unexpected end of GGUF metadata")
            offset += read
        }
        return result
    }

    fun u32(): Long = ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffff_ffffL

    private fun u64Raw(): Long = ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).long

    fun u64Bounded(max: Long, label: String): Long {
        val value = u64Raw()
        require(value >= 0 && value <= max) { "Invalid GGUF $label: unsigned value exceeds supported limit" }
        return value
    }

    fun string(maxBytes: Int = MAX_STRING_BYTES): String {
        val length = u64Bounded(maxBytes.toLong(), "string length").toInt()
        return bytes(length).toString(Charsets.UTF_8)
    }

    fun value(type: Int, depth: Int): Any? {
        require(depth <= 2) { "GGUF metadata arrays are nested too deeply" }
        return when (type) {
            0 -> bytes(1)[0].toUByte().toInt()
            1 -> bytes(1)[0].toInt()
            2 -> ByteBuffer.wrap(bytes(2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
            3 -> ByteBuffer.wrap(bytes(2)).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            4 -> u32()
            5 -> ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).int
            6 -> ByteBuffer.wrap(bytes(4)).order(ByteOrder.LITTLE_ENDIAN).float
            7 -> bytes(1)[0].toInt() != 0
            8 -> string()
            9 -> {
                val elementType = u32().toInt()
                val count = u64Bounded(MAX_ARRAY_ELEMENTS, "array length").toInt()
                // Tokenizer arrays can be enormous. We do not retain them, but must advance the stream.
                repeat(count) { value(elementType, depth + 1) }
                null
            }
            10 -> u64Raw()
            11 -> u64Raw()
            12 -> ByteBuffer.wrap(bytes(8)).order(ByteOrder.LITTLE_ENDIAN).double
            else -> throw IllegalArgumentException("Unsupported GGUF metadata type $type")
        }
    }

    private companion object {
        const val MAX_STRING_BYTES = 64 * 1024 * 1024
        const val MAX_ARRAY_ELEMENTS = 2_000_000L
    }
}
