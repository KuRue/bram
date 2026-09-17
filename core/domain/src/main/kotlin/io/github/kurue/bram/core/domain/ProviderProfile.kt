package io.github.kurue.bram.core.domain

/**
 * Provider-specific behavior, in one place.
 *
 * The OpenAI-compatible surface is a family of dialects: most servers need nothing beyond the base
 * schema, and a few have quirks — a session header, models served over a different wire kind than
 * their catalog suggests, models in a protocol Bram does not speak. Those quirks were previously
 * spread across the runtime, the catalog, the view model, and two screens, each matching on the
 * same URL substring; a provider that moves its host or adds a model kind had to be found in five
 * files. This profile is that knowledge's single home: callers ask the profile, never the URL.
 */
enum class ProviderProfile(
    /** Extra per-conversation header this provider wants, if any. */
    val sessionHeader: String? = null,
    /** Model ids served in a protocol Bram cannot speak; they are hidden from the catalog. */
    val unsupportedModelPrefixes: List<String> = emptyList(),
    /** Model ids served through the Responses API although the catalog shows plain rows. */
    val responsesModelPrefixes: List<String> = emptyList(),
    val responsesModelIds: Set<String> = emptySet(),
) {
    OPENCODE_GO(
        sessionHeader = "x-opencode-session",
        unsupportedModelPrefixes = listOf("qwen"),
        responsesModelPrefixes = listOf("muse-spark-"),
        responsesModelIds = setOf("gpt-5.6-luna", "grok-4.6"),
    ),
    GENERIC;

    /** The API kind a discovered model should use; [fallback] when the profile says nothing. */
    fun apiKindFor(modelId: String, fallback: RemoteApiKind): RemoteApiKind {
        val responses = modelId in responsesModelIds ||
            responsesModelPrefixes.any { modelId.startsWith(it, ignoreCase = true) }
        return if (responses) RemoteApiKind.RESPONSES else fallback
    }

    /** Whether the catalog should offer this model at all. */
    fun supportsModel(modelId: String): Boolean =
        unsupportedModelPrefixes.none { modelId.startsWith(it, ignoreCase = true) }

    /** Whether models discovered from this provider need catalog metadata beyond the base rows. */
    val needsCatalogEnrichment: Boolean
        get() = this == OPENCODE_GO

    companion object {
        fun forBaseUrl(baseUrl: String): ProviderProfile =
            if (baseUrl.contains("opencode.ai/zen/go", ignoreCase = true)) OPENCODE_GO else GENERIC
    }
}
