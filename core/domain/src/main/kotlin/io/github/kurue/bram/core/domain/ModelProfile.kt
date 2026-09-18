package io.github.kurue.bram.core.domain

/**
 * Canonicalizes a cpu-mask string: lowercase hex without the `0x` prefix, or empty when the
 * input is not a hex mask at all. A partially-garbage mask names the wrong cores, so it is
 * rejected whole rather than filtered — "not-a-mask" must never become "aa".
 */
fun canonicalCpuMask(raw: String): String {
    val stripped = raw.trim().removePrefix("0x").lowercase()
    if (stripped.isEmpty()) return ""
    return stripped.takeIf { it.all { character -> character in "0123456789abcdef" } }.orEmpty()
}

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

    /**
     * Named for what it buys rather than for the storage format. "Q8_0" tells someone who already
     * knows what it means, which is the one person who does not need to be told.
     */
    val label: String
        get() = when (this) {
            F16 -> "Exact"
            Q8_0 -> "Compact"
        }

    /** The trade, in one line, under the chips. */
    val summary: String
        get() = when (this) {
            F16 -> "Full precision. The default."
            Q8_0 -> "Half the memory, so roughly twice the context. Very slightly less exact."
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
 * is a wrong one, however fast it ran. Speed only ranks the backends that passed, and the speed
 * the card shows is absolute throughput (tokens per second of the measured run) rather than a
 * comparison sentence.
 */
data class BackendMeasurement(
    /** [RuntimeBackend] name, or empty for the CPU reference itself. */
    val backendId: String,
    val label: String,
    val agrees: Boolean,
    val agreement: Double,
    val speedup: Double,
    /** Prompt processing throughput of the measured run, tokens per second. */
    val promptTokPerSec: Double = 0.0,
    /** Decode throughput of the measured run, tokens per second. */
    val decodeTokPerSec: Double = 0.0,
) {
    /** The CPU is the yardstick, so it neither passes nor fails: it defines 1.0x. */
    val isReference: Boolean get() = backendId.isEmpty()
}

/**
 * How busy the generation threadpool is allowed to be.
 *
 * The threadpool is created from the profile's tuning fields (see [ModelProfile.threads],
 * [ModelProfile.cpuMask], [ModelProfile.poll], [ModelProfile.threadPriority]); NORMAL is what
 * Bram always ran, HIGH is a per-profile experiment for latency-critical generation.
 */
enum class ThreadPriority(val wire: String) {
    NORMAL("normal"),
    HIGH("high");

    val label: String
        get() = when (this) {
            NORMAL -> "Normal"
            HIGH -> "High"
        }

    companion object {
        fun fromWire(value: String): ThreadPriority =
            entries.firstOrNull { it.wire == value } ?: NORMAL
    }
}

/**
 * How the GGUF is mapped into memory.
 *
 * MMAP is what Bram always used. NO_MMAP reads the weights into private allocations, which the
 * Qualcomm reference scripts use for the Hexagon path (the backend repacks for HTP anyway).
 * AUTO lets llama.cpp decide per model and device.
 */
enum class LoadMode(val wire: String) {
    AUTO("auto"),
    MMAP("mmap"),
    NO_MMAP("no_mmap");

    val label: String
        get() = when (this) {
            AUTO -> "Auto"
            MMAP -> "Mmap"
            NO_MMAP -> "No mmap"
        }

    companion object {
        fun fromWire(value: String): LoadMode =
            entries.firstOrNull { it.wire == value } ?: AUTO
    }
}

/**
 * The Hexagon backend's host-side switches, applied as `GGML_HEXAGON_*` environment variables in
 * the inference process. They are read once, at backend registration, so they are process-level:
 * changing them while a model is loaded restarts the process rather than pretending to apply.
 *
 * Every flag is inert on a build or device without the Hexagon backend.
 */
data class HexFlags(
    /** `GGML_HEXAGON_USE_HMX=1` — prefer the HMX co-processor for matmuls. */
    val useHmx: Boolean = false,
    /** `GGML_HEXAGON_NHVX=0` — with HMX on, do not also schedule HVX work. */
    val disableNhvx: Boolean = false,
    /** `GGML_HEXAGON_HOSTBUF=1` — host-side compute buffers instead of DSP-side ones. */
    val hostBuf: Boolean = false,
    /** `GGML_HEXAGON_OPBATCH=0xN` — op batching bitmask, 0 meaning the backend default. */
    val opBatch: Int = 0,
    /** `GGML_HEXAGON_NDEV=N` — HTP devices to use, 0 meaning the backend default. */
    val nDev: Int = 0,
) {
    val isDefault: Boolean get() = this == HexFlags()

    fun sanitized(): HexFlags = copy(
        opBatch = opBatch.coerceIn(0, 0xF),
        nDev = nDev.coerceIn(0, 8),
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
    val flashAttention: FlashAttentionMode = FlashAttentionMode.AUTO,
    val kvCacheType: KvCacheType = KvCacheType.F16,
    /**
     * How many prompt tokens llama.cpp evaluates at once, or 0 for the llama.cpp default of 512.
     *
     * A larger batch means fewer evaluations for the same prompt, so this is the biggest lever on
     * time-to-first-token — and the first thing to trade against context size, because the batch
     * is a context parameter and its space comes out of the same memory. It is a profile setting
     * because the best value is specific to the model, the device, and the backend it loads onto.
     */
    val batchTokens: Int = 0,
    /**
     * Tokens llama.cpp computes between model evaluations, or 0 for the llama.cpp default of 128.
     *
     * Almost always left alone: the micro-batch mostly matters at the extremes, and llama.cpp's
     * default suits small and mid-range models. It exists so the tuning measurement can try both
     * the obvious answer (128) and the larger one (256) and keep what actually wins.
     */
    val ubatchTokens: Int = 0,
    /**
     * Threads for generation, or 0 for the device default (all usable cores, which is what Bram
     * always used). The tuned value is written by the tuning measurement; 0 keeps "Default".
     */
    val threads: Int = 0,
    /**
     * Hex string naming the CPU cores the generation threadpool may use, "" for default affinity
     * (which is today's behavior on any device whose topology was not measured). "0xfc" means
     * cores 2–7, which is the Snapdragon reference config for the Hexagon path.
     */
    val cpuMask: String = "",
    /** Pin the threadpool strictly to [cpuMask] rather than treating it as a preference. */
    val cpuStrict: Boolean = false,
    /** Threadpool polling level 0–100, or -1 for the backend default. */
    val poll: Int = -1,
    val threadPriority: ThreadPriority = ThreadPriority.NORMAL,
    val loadMode: LoadMode = LoadMode.AUTO,
    val hexFlags: HexFlags = HexFlags(),
    /**
     * Stream this MoE's experts from flash instead of loading them resident. This is what lets a
     * model several times larger than RAM run at all — each token reads only the experts it routes
     * to, straight from the gguf, so the whole model never has to fit in memory. Off for models that
     * fit, and only offered for streamable MoE architectures.
     */
    val streamExperts: Boolean = false,
    /** Resident expert-cache budget in MiB while streaming, or 0 for unbounded (fits-in-RAM only). */
    val streamCacheMb: Int = 0,
    /** Pin the always-used weights in anon RAM so the OS cannot reclaim them mid-generation. */
    val streamDenseAnon: Boolean = false,
    /** Overlap expert reads with compute: background reader lanes plus the per-expert kernel hook. */
    val streamOverlap: Boolean = false,
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
     * What the last batch tuning chose and why, in words. Kept beside the backend note so a
     * measured choice can be inspected instead of trusted, and empty when the profile has never
     * been tuned or its batch settings were set by hand.
     */
    val batchTuneNote: String = "",
    /** When that batch measurement was taken. */
    val batchTunedAtEpochMillis: Long = 0L,
    /**
     * What the other tuning dimensions chose and why, most recent first. Batch has its own legacy
     * note fields above; threads, mask, poll, load mode, and the Hexagon flags live here.
     */
    val tuning: List<DimensionTuneNote> = emptyList(),
    /**
     * The measurement fingerprint (device + app build + engine build + CPU features) under which
     * [measurements], [autoConfiguredNote], and [tuning] were recorded. Empty for a profile that
     * has never been measured. When it differs from the current device's fingerprint, the results
     * are stale — the profile moved to another phone, the app or engine was rebuilt, or the CPU
     * kernels changed — and the card says so instead of trusting them.
     */
    val measuredFingerprint: String = "",
    /**
     * Whether Bram made this profile rather than the user. A model gets one on import so it is
     * usable immediately, and an untouched default can be renamed or reshaped without the user
     * having to first understand that profiles exist.
     */
    val isDefault: Boolean = false,
) {
    /**
     * Bounds every runtime-tuning field so a stored profile cannot produce an unusable load.
     * Pure so it is JVM-testable; [io.github.kurue.bram.runtime.llamacpp.ModelProfileStore] applies
     * it on save.
     */
    fun sanitizedRuntime(): ModelProfile {
        val hex = canonicalCpuMask(cpuMask)
        return copy(
            threads = threads.coerceIn(0, 64),
            cpuMask = hex,
            // Strict placement without a mask names nothing; treat it as unset.
            cpuStrict = cpuStrict && hex.isNotEmpty(),
            poll = poll.coerceIn(-1, 100),
            hexFlags = hexFlags.sanitized(),
        )
    }

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
