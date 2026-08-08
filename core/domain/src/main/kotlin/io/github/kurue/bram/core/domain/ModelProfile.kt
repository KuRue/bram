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
 * Whether llama.cpp's FlashAttention path is used for this profile's runs.
 *
 * AUTO (the default) lets llama.cpp decide per graph, which keeps today's behavior: CPU gets
 * FlashAttention wherever it is compiled in, and a backend that cannot run the ops falls back to
 * plain attention. ON forces the flash path and fails loudly if the backend refuses it, which is
 * the diagnostic that told us whether the Hexagon HTP path can take the fast kernels at all. OFF
 * disables it, which is the reference everyone else is measured against.
 */
enum class FlashAttentionMode(val wire: String) {
    AUTO("auto"),
    ON("on"),
    OFF("off");

    val label: String
        get() = when (this) {
            AUTO -> "Auto"
            ON -> "On"
            OFF -> "Off"
        }

    companion object {
        fun fromWire(value: String): FlashAttentionMode =
            entries.firstOrNull { it.wire == value } ?: AUTO
    }
}

/**
 * How the KV cache is stored.
 *
 * F16 is what Bram always used. Q8_0 halves the cache and can only ever shift attention scores a
 * little, but it is exactly the kind of quiet change that only matters when it is measured — and
 * this is a profile setting precisely so the accelerator comparison can tell us whether it is a
 * good idea on a given device.
 */
enum class KvCacheType(val wire: String) {
    F16("f16"),
    Q8_0("q8_0");

    val label: String
        get() = when (this) {
            F16 -> "F16"
            Q8_0 -> "Q8"
        }

    companion object {
        fun fromWire(value: String): KvCacheType =
            entries.firstOrNull { it.wire == value } ?: F16
    }
}

/**
 * What one processor scored against the CPU reference.
 *
 * [agrees] is the part that decides anything: a backend that disagrees is not a slower option, it
 * is a wrong one, however fast it ran. Speed only ranks the backends that passed.
 */
data class BackendMeasurement(
    /** [RuntimeBackend] name, or empty for the CPU reference itself. */
    val backendId: String,
    val label: String,
    val agrees: Boolean,
    val agreement: Double,
    val speedup: Double,
) {
    /** The CPU is the yardstick, so it neither passes nor fails: it defines 1.0x. */
    val isReference: Boolean get() = backendId.isEmpty()
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
    val flashAttention: FlashAttentionMode = FlashAttentionMode.AUTO,
    val kvCacheType: KvCacheType = KvCacheType.F16,
    val sampler: SamplerSettings = SamplerSettings(),
    val systemPrompt: String = "",
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    /**
     * What each processor scored the last time they were measured, best first.
     *
     * Kept as results rather than a sentence so the card can show them side by side: a person
     * comparing "NPU 1.4x" against "Vulkan failed" reads the shape of the device in a glance, where
     * a paragraph about the winner hides everything it rejected and why.
     */
    val measurements: List<BackendMeasurement> = emptyList(),
    /**
     * Why the processor is what it is, in words, from the last automatic measurement.
     *
     * Kept so an automatic choice can be inspected instead of trusted. Empty when it was chosen by
     * hand.
     */
    val autoConfiguredNote: String = "",
    /**
     * When that measurement was taken.
     *
     * It expires in practice rather than formally: free memory, thermal state, and a new build all
     * change the answer, and a build once dropped a whole backend without saying so.
     */
    val autoConfiguredAtEpochMillis: Long = 0L,
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
