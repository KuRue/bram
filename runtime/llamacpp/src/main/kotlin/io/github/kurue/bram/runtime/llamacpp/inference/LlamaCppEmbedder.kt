package io.github.kurue.bram.runtime.llamacpp.inference

import io.github.kurue.bram.core.domain.Embedder

/**
 * Computes embeddings through the resident llama.cpp embedder in the `:inference` process. Returns
 * null when the process is unreachable or the call fails, so the memory store falls back to keyword
 * (FTS) recall rather than failing the turn.
 */
class LlamaCppEmbedder(private val client: LlamaCppServiceClient) : Embedder {
    override suspend fun embed(text: String): FloatArray? = client.embed(text)
}
