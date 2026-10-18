package io.github.kurue.bram.core.domain

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * A recipe built from what this phone measured, with the index entry a contributor has to paste
 * beside it.
 *
 * The point of the exercise is that a measurement taken here is useful somewhere else, without
 * anybody retyping it. What makes that safe is what is *not* in here: the export reads named fields
 * and writes named fields, so a system prompt, a chat, a file name, or a tuning note cannot ride
 * along — there is no path from them to the document. [RecipeExportBuilder] only ever sees the
 * device identity, the model identity, the profile's runtime settings, and the benchmark.
 */
data class RecipeExport(
    val recipe: HardwareRecipe,
    /** The file to commit: `bram.hardware-recipe` JSON, in the order the schemas list it. */
    val document: String,
    /** Suggested file name for [document], inside a source next to its index. */
    val fileName: String,
    /** SHA-256 of [document]'s bytes, which the source index has to carry. */
    val sha256: String,
    /** The ready-to-paste `recipes` entry for a source index. */
    val indexEntry: String,
    /** What the numbers came from, for the person deciding whether to publish them. */
    val provenance: String,
)

/** Either a shareable recipe, or the reason there is not one yet. */
sealed interface RecipeDraft {
    data class Ready(val export: RecipeExport) : RecipeDraft

    /** [reason] is written for the person deciding, not for a log. */
    data class NotReady(val reason: String) : RecipeDraft
}

/**
 * Builds the export from one profile and one benchmark of it.
 *
 * Everything it refuses, it refuses for the same reason: a recipe is a claim that someone else can
 * reproduce a measurement, and a claim with a missing ingredient is worse than no recipe. A build
 * that cannot name its engine, a model with no content hash, a benchmark from another phone or
 * another Bram build, or a run without both prompt and decode results all fail that test.
 */
object RecipeExportBuilder {

    /** The method sentence the evidence carries; the numbers are meaningless without it. */
    const val METHOD = "Bram's benchmark on the device: three repetitions per test, a cool-down " +
        "before the depth test, battery temperature recorded. Absolute throughput, not a comparison."

    fun build(
        device: RecipeDeviceIdentity,
        model: RecipeModelIdentity,
        profile: ModelProfile,
        run: BenchRun,
        maintainer: String,
        today: String,
        /** The current measurement fingerprint, so a run from another device or build is refused. */
        currentFingerprint: String,
        /** The profile's recorded accelerator measurement, when the backend is not the CPU. */
        measurement: BackendMeasurement? = null,
    ): RecipeDraft {
        if (device.engineCommit.isBlank()) return notReady("The engine build could not be identified")
        if (device.model.isBlank() && device.soc.isBlank()) {
            return notReady("This phone did not report a model or SoC, so a recipe could not say who it is for")
        }
        if (model.sha256.isNullOrBlank()) return notReady("This model's content hash is unavailable")
        if (model.architecture.isBlank() || model.quantization.isBlank()) {
            return notReady("This model's architecture or quantization is unknown")
        }
        if (currentFingerprint.isBlank() || run.fingerprint != currentFingerprint) {
            return notReady("That benchmark was measured on another device or Bram build; run one now")
        }
        val prompt = run.results.firstOrNull { it.test.kind == BenchTest.Kind.PROMPT && it.test.depth == 0 && it.skipped == null && it.mean > 0 }
            ?: return notReady("Run a benchmark on this profile first: a recipe needs prompt numbers")
        val decode = run.results.firstOrNull { it.test.kind == BenchTest.Kind.GENERATION && it.test.depth == 0 && it.skipped == null && it.mean > 0 }
            ?: return notReady("Run a benchmark on this profile first: a recipe needs decode numbers")
        val deep = run.results.firstOrNull {
            it.test.kind == BenchTest.Kind.GENERATION && it.test.depth > 0 && it.skipped == null && it.mean > 0
        }
        if (!maintainer.matches(Regex("^[A-Za-z0-9 ._-]{2,120}$"))) {
            return notReady("A recipe names its maintainer: letters, digits, spaces, dots, dashes and underscores")
        }
        val backend = RecipeBackend.ofProfile(profile.backendId)
        if (profile.backendId.isNotBlank() && backend == RecipeBackend.CPU) {
            return notReady("The profile names a backend this format cannot describe")
        }
        if (backend !in device.availableBackends) {
            return notReady("The ${backend.label} backend is not compiled into this build")
        }

        val id = recipeId(device, model, backend)
        val recipe = HardwareRecipe(
            id = id,
            version = 1,
            maintainer = maintainer,
            evidenceDate = today,
            match = RecipeDeviceMatch(
                manufacturers = listOfNotNull(device.manufacturer.lowercase().takeIf(String::isNotBlank)),
                models = listOfNotNull(device.model.lowercase().takeIf(String::isNotBlank)),
                socs = listOfNotNull(device.soc.lowercase().takeIf(String::isNotBlank)),
                // What the measurement ran with. A phone without these kernels is a different
                // configuration, not a differently named one.
                requireCpuFeatures = RecipeCpuFeature.of(device.cpuFeatures),
                minAndroidApi = device.androidApi,
            ),
            runtime = RecipeRuntime(
                engineCommits = listOf(device.engineCommit.lowercase()),
                settingsSchemaVersion = RECIPE_SCHEMA_VERSION,
                availableBackends = setOf(backend),
            ),
            model = RecipeModel(
                architectures = listOf(model.architecture.lowercase()),
                quantizations = listOf(model.quantization.lowercase()),
                sha256 = model.sha256,
                sizeBytes = model.sizeBytes.takeIf { it > 0 },
                // No context floor: the proposal is what sets the context, so demanding the reader
                // already be at it would hide the recipe from exactly the phones that want it.
                minContextTokens = 0,
            ),
            workload = RecipeWorkload(
                promptTokens = prompt.test.tokens,
                decodeTokens = decode.test.tokens,
                contextDepth = deep?.test?.depth ?: 0,
                preference = RecipeWorkloadPreference.CHAT,
            ),
            proposal = proposalOf(profile, backend),
            evidence = RecipeEvidence(
                method = METHOD,
                agreement = measurement?.takeIf { !it.isReference }?.agreement,
                promptTokPerSec = prompt.mean,
                promptSpread = prompt.stdDev,
                decodeTokPerSec = decode.mean,
                decodeSpread = decode.stdDev,
                depthDecodeTokPerSec = deep?.mean,
                depthDecodeSpread = deep?.stdDev,
                batteryCelsiusStart = prompt.batteryTempBefore,
                batteryCelsiusEnd = (deep ?: decode).batteryTempAfter,
            ),
            limits = RecipeLimits(
                expiresAfterDays = null,
                revalidateOn = setOf(
                    RecipeRevalidation.ENGINE_COMMIT,
                    RecipeRevalidation.MODEL_HASH,
                    RecipeRevalidation.SETTINGS_SCHEMA,
                ),
                incompatible = emptyList(),
            ),
        )

        val document = recipe.toJson()
        val fileName = "$id.json"
        return RecipeDraft.Ready(
            RecipeExport(
                recipe = recipe,
                document = document,
                fileName = fileName,
                sha256 = sha256(document),
                indexEntry = indexEntryOf(recipe, fileName, sha256(document)),
                provenance = "Measured on ${device.manufacturer} ${device.model} (${device.soc}) on " +
                    "$today, engine ${device.engineCommit.take(12)}, ${run.backend} at ${run.config.ifBlank { "unknown configuration" }}.",
            ),
        )
    }

    private fun notReady(reason: String): RecipeDraft = RecipeDraft.NotReady(reason)

    /** Only what was chosen: an absent field means "leave it alone", so engine-chosen zeros are omitted. */
    private fun proposalOf(profile: ModelProfile, backend: RecipeBackend): RecipeProposal = RecipeProposal(
        backend = backend,
        contextTokens = profile.contextTokens,
        threads = profile.threads.takeIf { it > 0 },
        decodeThreads = profile.decodeThreads.takeIf { it > 0 },
        // Stated only when cores were pinned; its absence means this measurement used the engine's
        // default affinity, which is what "leave it alone" should do for a reader too.
        cpuMask = profile.cpuMask.lowercase().takeIf { it.isNotEmpty() },
        poll = profile.poll.takeIf { it >= 0 },
        flashAttention = profile.flashAttention,
        kvCache = profile.kvCacheType,
        loadMode = profile.loadMode,
        batchTokens = profile.batchTokens.takeIf { it > 0 },
        ubatchTokens = profile.ubatchTokens.takeIf { it > 0 },
        threadPriority = profile.threadPriority,
    )

    /** A readable, stable id: what it is for, then what it runs. */
    private fun recipeId(device: RecipeDeviceIdentity, model: RecipeModelIdentity, backend: RecipeBackend): String {
        val parts = listOf(
            device.manufacturer.lowercase(),
            device.model.lowercase(),
            device.soc.lowercase(),
            model.architecture.lowercase(),
            model.quantization.lowercase(),
            backend.wire,
        )
        val slug = parts.joinToString("-") { part ->
            part.replace(Regex("[^a-z0-9._:]+"), "-").trim('-')
        }.replace(Regex("-{2,}"), "-").trim('-')
        val bounded = slug.take(120).trim('-')
        return if (bounded.length >= 3) bounded else "recipe-$bounded"
    }

    private fun indexEntryOf(recipe: HardwareRecipe, fileName: String, sha256: String): String =
        JSONObject()
            .put("id", recipe.id)
            .put("version", recipe.version)
            .put("file", fileName)
            .put("sha256", sha256)
            .toString(2)

    internal fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * The document a contributor commits, written in the order the schemas and the draft list, with
 * absent fields omitted rather than written as null. Deterministic: the same recipe writes the same
 * bytes, which is what makes the index's hash meaningful.
 */
fun HardwareRecipe.toJson(): String {
    fun list(values: Collection<String>) = JSONArray().apply { values.forEach(::put) }

    val match = JSONObject()
        .putIfNotEmpty("manufacturers", match.manufacturers)
        .putIfNotEmpty("models", match.models)
        .putIfNotEmpty("socs", match.socs)
        .putIfNotEmpty("requireCpuFeatures", match.requireCpuFeatures.map { it.wire }.sorted())
        .putIfPositive("minAndroidApi", match.minAndroidApi)

    val runtime = JSONObject()
        .put("engineCommits", list(runtime.engineCommits))
        .put("settingsSchemaVersion", runtime.settingsSchemaVersion)
        .put("availableBackends", list(runtime.availableBackends.map { it.wire }.sorted()))

    val model = JSONObject()
        .put("architectures", list(model.architectures))
        .put("quantizations", list(model.quantizations))
        .apply {
            model.sha256?.let { put("sha256", it) }
            model.sizeBytes?.let { put("sizeBytes", it) }
            if (model.minContextTokens > 0) put("minContextTokens", model.minContextTokens)
        }

    val workload = JSONObject()
        .putIfPositive("promptTokens", workload.promptTokens)
        .putIfPositive("decodeTokens", workload.decodeTokens)
        .putIfPositive("contextDepth", workload.contextDepth)
        .put("preference", workload.preference.wire)

    val proposal = JSONObject()
        .apply {
            proposal.backend?.let { put("backend", it.wire) }
            proposal.contextTokens?.let { put("contextTokens", it) }
            proposal.threads?.let { put("threads", it) }
            proposal.decodeThreads?.let { put("decodeThreads", it) }
            proposal.cpuMask?.let { put("cpuMask", it) }
            proposal.poll?.let { put("poll", it) }
            proposal.flashAttention?.let { put("flashAttention", it.wire) }
            proposal.kvCache?.let { put("kvCache", it.wire) }
            proposal.loadMode?.let { put("loadMode", it.wire) }
            proposal.batchTokens?.let { put("batchTokens", it) }
            proposal.ubatchTokens?.let { put("ubatchTokens", it) }
            proposal.threadPriority?.let { put("threadPriority", it.wire) }
        }

    val evidence = JSONObject()
        .put("method", evidence.method)
        .apply {
            evidence.agreement?.let { put("agreement", it) }
            evidence.promptTokPerSec?.let { put("promptTokPerSec", it) }
            evidence.promptSpread?.let { put("promptSpread", it) }
            evidence.decodeTokPerSec?.let { put("decodeTokPerSec", it) }
            evidence.decodeSpread?.let { put("decodeSpread", it) }
            evidence.depthDecodeTokPerSec?.let { put("depthDecodeTokPerSec", it) }
            evidence.depthDecodeSpread?.let { put("depthDecodeSpread", it) }
            evidence.batteryCelsiusStart?.let { put("batteryCelsiusStart", it) }
            evidence.batteryCelsiusEnd?.let { put("batteryCelsiusEnd", it) }
        }

    val limits = JSONObject()
        .apply {
            limits.expiresAfterDays?.let { put("expiresAfterDays", it) }
            if (limits.revalidateOn.isNotEmpty()) {
                put("revalidateOn", list(limits.revalidateOn.map { it.wire }.sorted()))
            }
            if (limits.incompatible.isNotEmpty()) put("incompatible", list(limits.incompatible))
        }

    return JSONObject()
        .put("schema", RECIPE_SCHEMA)
        .put("schemaVersion", RECIPE_SCHEMA_VERSION)
        .put("id", id)
        .put("version", version)
        .put("maintainer", maintainer)
        .put("evidenceDate", evidenceDate)
        .put("match", match)
        .put("runtime", runtime)
        .put("model", model)
        .put("workload", workload)
        .put("proposal", proposal)
        .put("evidence", evidence)
        .put("limits", limits)
        .toString(2) + "\n"
}

private fun JSONObject.putIfNotEmpty(key: String, values: List<String>): JSONObject =
    if (values.isEmpty()) this else put(key, JSONArray().apply { values.forEach(::put) })

private fun JSONObject.putIfPositive(key: String, value: Int): JSONObject =
    if (value > 0) put(key, value) else this
