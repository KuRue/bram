package io.github.kurue.bram.core.domain

/**
 * Which processor a LiteRT-LM package loads onto.
 *
 * The NPU backend is deliberately absent: it needs the matching Qualcomm QNN runtime, whose version
 * must line up with the library's and the device's DSP firmware, and it gains nothing here over GPU
 * on the reference hardware. It is a follow-up, not a silent option.
 */
enum class LiteRtBackend(val wire: String) {
    CPU("cpu"),
    GPU("gpu");

    val label: String
        get() = when (this) {
            CPU -> "LiteRT CPU"
            GPU -> "LiteRT GPU"
        }

    companion object {
        fun fromWire(value: String?): LiteRtBackend = entries.firstOrNull { it.wire == value } ?: GPU
    }
}

/**
 * A `.litertlm` package selected through the Storage Access Framework.
 *
 * Unlike a GGUF, a LiteRT-LM package carries its model, tokenizer, and chat template compiled into
 * one file, so there is no metadata header to read: the record is what the file picker said plus
 * the SHA-256 Bram computed while copying it into app-private storage. The context window is the
 * package's own, unknown without opening it, so the conservative floor below is reported until a
 * way to read it exists.
 */
data class LiteRtModelRecord(
    val id: ModelId,
    val displayName: String,
    val fileName: String,
    val contentUri: String,
    val localPath: String,
    val fileSizeBytes: Long,
    val sha256: String,
    val backend: LiteRtBackend = LiteRtBackend.GPU,
    val importedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    fun asModelDescriptor(): ModelDescriptor = ModelDescriptor(
        id = id,
        displayName = displayName,
        providerName = "On this device",
        modelName = fileName,
        location = ModelLocation.LOCAL,
        contextWindowTokens = LITE_RT_CONTEXT_TOKENS,
        capabilities = setOf(ModelCapability.TEXT, ModelCapability.TOOL_CALLING),
    )
}

/**
 * The context window Bram assumes for a LiteRT-LM package it has not opened.
 *
 * A `.litertlm` file keeps its KV-cache size inside the package; the engine does not expose it, so
 * the safe conservative floor is used for routing and context budgeting. Underestimating only
 * leaves headroom; overestimating would push a turn past the package's real window.
 */
const val LITE_RT_CONTEXT_TOKENS = 4_096
