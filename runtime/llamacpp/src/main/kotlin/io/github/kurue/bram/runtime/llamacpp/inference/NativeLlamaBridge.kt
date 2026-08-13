package io.github.kurue.bram.runtime.llamacpp.inference

internal fun interface NativeTokenSink {
    fun onToken(text: String)
}

/** Loaded only by [InferenceProcessService] inside the `:inference` process. */
internal class NativeLlamaBridge {
    external fun probe(): String
    external fun devices(): String
    external fun load(
        modelPath: String,
        contextTokens: Int,
        batchTokens: Int,
        ubatchTokens: Int,
        threads: Int,
        gpuLayers: Int,
        deviceFilter: String,
        enableThinking: Boolean,
        flashAttention: String,
        kvCacheType: String,
        cpuMask: String,
        cpuStrict: Boolean,
        poll: Int,
        threadPriority: String,
        loadMode: String,
        hexUseHmx: Boolean,
        hexDisableNhvx: Boolean,
        hexHostBuf: Boolean,
        hexOpBatch: Int,
        hexNDev: Int,
    ): String
    external fun referenceDecode(tokenCount: Int): String
    external fun teacherForced(forcedTokens: IntArray): String
    external fun parseReply(reply: String): String
    external fun formatChat(
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
        /** Tool definitions as a JSON array, empty when the run offers none. */
        toolsJson: String,
        /** Forces the reply to be a tool call. Only for a retry; see [InferenceProcessService]. */
        requireTool: Boolean,
    ): String

    /**
     * The reasoning tags of the format the last prompt was built with, so the streaming split can
     * use the loaded model's own markers instead of assuming `<think>`. Valid only after
     * [formatChat], since applying the template is what computes them.
     */
    external fun chatFormat(): String
    external fun countTokens(prompt: String): Int
    external fun generate(
        prompt: String,
        maxOutputTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        repeatLastTokens: Int,
        sink: NativeTokenSink,
    ): String
    external fun cancel()
    external fun selfTest(): String
    external fun unload(): String
    external fun state(): String
    external fun loadEmbedder(modelPath: String, threads: Int): String
    external fun embed(text: String): FloatArray
    external fun unloadEmbedder(): String

    private companion object {
        init {
            System.loadLibrary("bram_llama")
        }
    }
}
