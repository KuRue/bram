package io.github.kurue.bram.core.domain

/**
 * How a model should sample.
 *
 * Kept as its own type because these travel together: changing temperature without the truncation
 * settings beside it produces surprises, and the process boundary has to carry all of them or none.
 * The defaults are llama.cpp's usual chat values, which is what Bram used when they were fixed.
 */
data class SamplerSettings(
    /** Zero means greedy decoding, which is what the accelerator comparison relies on. */
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    /**
     * Penalty applied to tokens already in the window. Small models repeat themselves on a phone
     * more than the same model does on a desktop, because the shorter context leaves less to draw
     * on, so this is worth exposing rather than assuming.
     */
    val repeatPenalty: Float = 1.1f,
    /** How many recent tokens [repeatPenalty] considers. */
    val repeatLastTokens: Int = 64,
) {
    val isGreedy: Boolean get() = temperature <= 0f

    /** Clamps to ranges llama.cpp accepts, so a stored profile cannot produce an unusable load. */
    fun sanitized(): SamplerSettings = copy(
        temperature = temperature.coerceIn(0f, 2f),
        topP = topP.coerceIn(0.01f, 1f),
        topK = topK.coerceIn(0, 1_000),
        repeatPenalty = repeatPenalty.coerceIn(1f, 2f),
        repeatLastTokens = repeatLastTokens.coerceIn(0, 2_048),
    )
}

/**
 * A saved way of running a model.
 *
 * These settings used to live on [LocalModelRecord], one set per imported file, which meant the
 * choices a file was imported with were the only choices it had. A profile separates the two: one
 * GGUF can back several profiles that differ in context size, sampling, reasoning, or the processor
 * they load onto, and the interface selects a profile rather than a file.
 *
 * [systemPrompt] overrides Bram's own identity prompt when set, which is the point of having
 * profiles at all for anything other than tuning — a profile can be a persona.
 */
data class ModelProfile(
    val id: String,
    val name: String,
    /** The GGUF this profile runs. Several profiles may name the same one. */
    val modelId: ModelId,
    val contextTokens: Int,
    /** Which processor to load onto. Empty means CPU. */
    val backendId: String = "",
    val thinkingEnabled: Boolean = false,
    val sampler: SamplerSettings = SamplerSettings(),
    val systemPrompt: String = "",
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    /**
     * Whether Bram made this profile rather than the user. A model gets one on import so it is
     * usable immediately, and an untouched default can be renamed or reshaped without the user
     * having to first understand that profiles exist.
     */
    val isDefault: Boolean = false,
) {
    companion object {
        /** The profile a freshly imported model is usable through, before anyone configures one. */
        fun defaultFor(model: LocalModelRecord): ModelProfile = ModelProfile(
            id = "profile:${model.id.value}:default",
            name = model.displayName,
            modelId = model.id,
            contextTokens = model.preferredContextTokens,
            backendId = model.preferredBackendId,
            thinkingEnabled = model.thinkingEnabled,
            isDefault = true,
        )
    }
}
