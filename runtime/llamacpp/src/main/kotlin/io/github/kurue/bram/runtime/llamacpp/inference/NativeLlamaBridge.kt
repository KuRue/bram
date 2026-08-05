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
        threads: Int,
        gpuLayers: Int,
    ): String
    external fun referenceDecode(tokenCount: Int): String
    external fun formatChat(roles: Array<String>, contents: Array<String>, addAssistant: Boolean): String
    external fun countTokens(prompt: String): Int
    external fun generate(
        prompt: String,
        maxOutputTokens: Int,
        temperature: Float,
        sink: NativeTokenSink,
    ): String
    external fun cancel()
    external fun selfTest(): String
    external fun unload(): String
    external fun state(): String

    private companion object {
        init {
            System.loadLibrary("bram_llama")
        }
    }
}
