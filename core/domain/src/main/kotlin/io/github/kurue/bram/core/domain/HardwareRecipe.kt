package io.github.kurue.bram.core.domain

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Hardware recipes: measured settings for one device, engine, and model, published so a phone does
 * not have to rediscover them by sweeping.
 *
 * A recipe is *data*, never code. It carries no prompts, no scripts, no download URLs, and no
 * permissions, and it cannot reach a setting Bram does not already offer: the field allowlist below
 * is the whole vocabulary, and anything outside it is refused with a reason. That is deliberate —
 * a recipe comes from a repository Bram did not write, and the safe way to accept untrusted tuning
 * advice is to accept only numbers and enum names that map onto existing settings, each bounded.
 *
 * Three promises the format keeps, so the app can say them:
 *
 * * **Exact or seed.** A recipe matches this phone exactly (device, engine build, model bytes) or it
 *   is only a seed worth trying ([RecipeFidelity]). The app shows which, and revalidates locally
 *   either way before the settings take effect.
 * * **Evidence is dated and bounded.** Numbers are recorded with the method and conditions that
 *   produced them; a recipe that is stale revalidates rather than pretends.
 * * **Nothing unsafe is expressible.** The CPU agreement gate, memory ceilings, remote routing, and
 *   native library loading live in the app and appear nowhere here — there is no field to set.
 *
 * The full format is described field by field in [HardwareRecipe.parse] and the data classes below.
 */
const val RECIPE_SCHEMA = "bram.hardware-recipe"
const val RECIPE_INDEX_SCHEMA = "bram.hardware-recipes.index"
const val RECIPE_SCHEMA_VERSION = 1

/** Documents larger than these are refused before parsing: a source cannot spend the phone's RAM. */
const val MAX_RECIPE_DOCUMENT_BYTES = 64 * 1024
const val MAX_RECIPE_INDEX_BYTES = 256 * 1024

/** A parse either yields the value or every reason it was refused, so a UI can say what was wrong. */
sealed interface RecipeParse<out T> {
    data class Parsed<T>(val value: T) : RecipeParse<T>

    /**
     * One line per problem, each naming the field it belongs to ("proposal.threads: must be
     * between 1 and 256"). Never a bare "invalid".
     */
    data class Refused(val problems: List<String>) : RecipeParse<Nothing>
}

/** Who published a snapshot, and whether Bram itself did. */
enum class RecipeSourceKind(val wire: String) {
    /** The bundled snapshot or another Bram-vouched source: hashes pinned by the app. */
    OFFICIAL("official"),

    /** A source the user added: explicit trust, pinned revision, key-change review. */
    EXTERNAL("external"),
    ;

    companion object {
        fun fromWire(value: String): RecipeSourceKind? = entries.firstOrNull { it.wire == value }
    }
}

data class RecipeSource(val id: String, val name: String, val kind: RecipeSourceKind)

/** One recipe's identity and content hash inside an index. */
data class RecipeDigest(val id: String, val version: Int, val file: String, val sha256: String)

/**
 * The entry document of a source: what it is, which snapshot it is, and the hashes of the recipes
 * it lists. Hashes give integrity, not authorship — see the plan's note that a signed recipe can
 * still be slow or wrong.
 */
data class RecipeIndex(
    val schemaVersion: Int,
    val source: RecipeSource,
    val revision: String,
    val generatedAt: String,
    val recipes: List<RecipeDigest>,
) {
    companion object {
        fun parse(json: String, maxBytes: Int = MAX_RECIPE_INDEX_BYTES): RecipeParse<RecipeIndex> =
            RecipeDocuments.parseIndex(json, maxBytes)
    }
}

/** A device feature a recipe can require; mapped from [CpuFeatures]. */
enum class RecipeCpuFeature(val wire: String) {
    FP16("fp16"),
    DOTPROD("dotprod"),
    I8MM("i8mm"),
    BF16("bf16"),
    SVE("sve"),
    ;

    companion object {
        fun fromWire(value: String): RecipeCpuFeature? = entries.firstOrNull { it.wire == value }

        /** The features this device reports, as the set a recipe's requirements are tested against. */
        fun of(cpu: CpuFeatures?): Set<RecipeCpuFeature> {
            if (cpu == null) return emptySet()
            return buildSet {
                if (cpu.fp16) add(FP16)
                if (cpu.dotProduct) add(DOTPROD)
                if (cpu.int8Matmul) add(I8MM)
                if (cpu.bf16) add(BF16)
                if (cpu.sve) add(SVE)
            }
        }
    }
}

/**
 * An execution backend a recipe can name or require. The wire names are the recipe vocabulary; the
 * ids are what [ModelProfile.backendId] stores, kept beside them so neither drifts.
 */
enum class RecipeBackend(val wire: String, val profileId: String) {
    CPU("cpu", ""),
    VULKAN("vulkan", "VULKAN"),
    OPENCL("opencl", "OPENCL"),
    HEXAGON("hexagon", "HEXAGON"),
    ;

    companion object {
        fun fromWire(value: String): RecipeBackend? = entries.firstOrNull { it.wire == value }

        /** The runtime's own name for a profile's backend, so "does this recipe name it" is asked once. */
        fun ofProfile(backendId: String): RecipeBackend =
            entries.firstOrNull { it.profileId == backendId } ?: CPU
    }
}

/** Which of the recipe's conditions a phone must still satisfy before it applies again. */
enum class RecipeRevalidation(val wire: String) {
    ENGINE_COMMIT("engine_commit"),
    MODEL_HASH("model_hash"),
    SETTINGS_SCHEMA("settings_schema"),
    ;

    companion object {
        fun fromWire(value: String): RecipeRevalidation? = entries.firstOrNull { it.wire == value }
    }
}

/** The workload a recipe's numbers were taken under. */
enum class RecipeWorkloadPreference(val wire: String) {
    CHAT("chat"),
    LONG_CONTEXT("long_context"),
    ;

    companion object {
        fun fromWire(value: String): RecipeWorkloadPreference? = entries.firstOrNull { it.wire == value }
    }
}

/** Which phones a recipe is for. Everything here is a condition, not a suggestion. */
data class RecipeDeviceMatch(
    /** Manufacturer names, compared case-insensitively ("samsung"). */
    val manufacturers: List<String>,
    /** Device model aliases ("sm-s938u1", "sm-s938b", ...). Matching one is an exact device. */
    val models: List<String>,
    /** SoC aliases ("sm8750", "snapdragon 8 elite"). Matching one is a family seed unless a model matched too. */
    val socs: List<String>,
    /** CPU features the phone must report, or the recipe does not apply at all. */
    val requireCpuFeatures: Set<RecipeCpuFeature>,
    /** Android API level floor; 0 when the recipe claims none. */
    val minAndroidApi: Int,
)

/** What the recipe's evidence is valid for. */
data class RecipeRuntime(
    /** The engine builds the numbers were taken on. A different build makes a recipe a seed. */
    val engineCommits: List<String>,
    /** The settings schema the proposal speaks; a newer app revalidates rather than guesses. */
    val settingsSchemaVersion: Int,
    /** Backends that must be compiled into this build for the recipe to apply. */
    val availableBackends: Set<RecipeBackend>,
)

/** Which models a recipe's settings are for. */
data class RecipeModel(
    /** Architectures this is candidate for ("lfm2", "qwen3", ...). */
    val architectures: List<String>,
    /** Quantizations this is candidate for ("q4_0", "q8_0", ...). */
    val quantizations: List<String>,
    /** The exact file the evidence was measured on, when it is known. */
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    /** Below this context the numbers do not describe the run, so it does not apply. */
    val minContextTokens: Int,
)

data class RecipeWorkload(
    val promptTokens: Int,
    val decodeTokens: Int,
    val contextDepth: Int,
    val preference: RecipeWorkloadPreference,
)

/**
 * The settings a recipe suggests, each optional: an absent field means "leave it alone", so a
 * proposal is a list of deliberate changes rather than a whole profile in disguise.
 */
data class RecipeProposal(
    val backend: RecipeBackend? = null,
    val contextTokens: Int? = null,
    val threads: Int? = null,
    val decodeThreads: Int? = null,
    val cpuMask: String? = null,
    val poll: Int? = null,
    val flashAttention: FlashAttentionMode? = null,
    val kvCache: KvCacheType? = null,
    val loadMode: LoadMode? = null,
    val batchTokens: Int? = null,
    val ubatchTokens: Int? = null,
    val threadPriority: ThreadPriority? = null,
)

/** The numbers, with the method that produced them. Absent means "not measured", never zero. */
data class RecipeEvidence(
    val method: String,
    /** Teacher-forced agreement with the CPU reference; 1.0 for a CPU recipe. */
    val agreement: Double? = null,
    val promptTokPerSec: Double? = null,
    val promptSpread: Double? = null,
    val decodeTokPerSec: Double? = null,
    val decodeSpread: Double? = null,
    val depthDecodeTokPerSec: Double? = null,
    val depthDecodeSpread: Double? = null,
    val batteryCelsiusStart: Double? = null,
    val batteryCelsiusEnd: Double? = null,
)

/** When a recipe stops being trusted, and what it already knows is bad. */
data class RecipeLimits(
    /** Days after the evidence date before a refresh is expected; null when it does not expire. */
    val expiresAfterDays: Int?,
    val revalidateOn: Set<RecipeRevalidation>,
    /** Combinations the maintainer already found broken on this hardware, one line each. */
    val incompatible: List<String>,
)

/**
 * One measured configuration for one device, engine, and model.
 *
 * Parsed strictly: every object's keys are checked against the allowlist for that object, so a
 * document that tries to carry a system prompt, a sampler, a script, a URL, or an unknown future
 * field is refused by name instead of silently dropping it.
 */
data class HardwareRecipe(
    val id: String,
    val version: Int,
    val maintainer: String,
    /** ISO date (yyyy-MM-dd) the evidence was taken. */
    val evidenceDate: String,
    val match: RecipeDeviceMatch,
    val runtime: RecipeRuntime,
    val model: RecipeModel,
    val workload: RecipeWorkload,
    val proposal: RecipeProposal,
    val evidence: RecipeEvidence,
    val limits: RecipeLimits,
) {
    companion object {
        /**
         * Reads a recipe document, or refuses it with every problem found.
         *
         * The accepted fields are exactly the ones in the tables of the product plan: identity,
         * match, runtime, model, workload, proposal, evidence, limits. There is no field for a
         * prompt, sampler values, permissions, URLs, scripts, or memory limits — a recipe that
         * carries one is refused rather than partially accepted.
         */
        fun parse(json: String, maxBytes: Int = MAX_RECIPE_DOCUMENT_BYTES): RecipeParse<HardwareRecipe> =
            RecipeDocuments.parseRecipe(json, maxBytes)
    }
}

/** Everything about the phone a recipe is matched against. Built by the app from the profiler. */
data class RecipeDeviceIdentity(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val androidApi: Int,
    val cpuFeatures: CpuFeatures?,
    val engineCommit: String,
    val availableBackends: Set<RecipeBackend>,
)

/** Everything about the model a recipe is matched against. */
data class RecipeModelIdentity(
    val architecture: String,
    val quantization: String,
    val sizeBytes: Long,
    /** Null when the app has not hashed this file (a 1.6 GB hash is not free). */
    val sha256: String?,
    /** The context the profile would load it at. */
    val contextTokens: Int,
)

/**
 * Strict reading of recipe documents. Split out so the data classes stay declarative and the
 * "every problem, by field name" behaviour is in one place.
 */
private object RecipeDocuments {

    private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._:-]{2,120}$")
    private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    private val HEX_MASK_PATTERN = Regex("^[0-9a-fA-F]{1,16}$")
    private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val TIMESTAMP_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}(T\\d{2}:\\d{2}:\\d{2}Z)?$")
    private val ENGINE_COMMIT_PATTERN = Regex("^[0-9a-fA-F]{7,40}$")

    private val TOKENS_PER_SECOND_CEILING = 100_000.0

    fun parseIndex(json: String, maxBytes: Int): RecipeParse<RecipeIndex> {
        val problems = mutableListOf<String>()
        val obj = objectOf(json, maxBytes, "index", problems) ?: return RecipeParse.Refused(problems)
        checkKeys(
            obj, "index", problems,
            setOf("schema", "schemaVersion", "source", "revision", "generatedAt", "recipes"),
        )
        requireString(obj, "schema", "index", problems) { value ->
            if (value == RECIPE_INDEX_SCHEMA) null else "must be \"$RECIPE_INDEX_SCHEMA\""
        }
        val schemaVersion = requiredIntIn(obj, "schemaVersion", "index", problems, 1, 1_000_000)
        if (schemaVersion != null && schemaVersion != RECIPE_SCHEMA_VERSION) {
            problems += "index.schemaVersion: this app reads version $RECIPE_SCHEMA_VERSION, not $schemaVersion"
        }
        val source = obj.optJSONObject("source")?.let { sourceObject ->
            checkKeys(sourceObject, "source", problems, setOf("id", "name", "kind"))
            RecipeSource(
                id = requiredText(sourceObject, "id", "source", problems, 120).orEmpty(),
                name = requiredText(sourceObject, "name", "source", problems, 120).orEmpty(),
                // Trust is decided by this field, so a document that omits it is not read as external.
                kind = requiredEnumOf(sourceObject, "kind", "source", problems, RecipeSourceKind.entries) { it.wire }
                    ?: RecipeSourceKind.EXTERNAL,
            )
        } ?: run {
            problems += "source: required"
            null
        }
        val revision = requiredText(obj, "revision", "index", problems, 120)
        val generatedAt = requiredText(obj, "generatedAt", "index", problems, 40)?.also {
            if (!TIMESTAMP_PATTERN.matches(it)) problems += "index.generatedAt: expected yyyy-MM-dd or yyyy-MM-ddTHH:MM:SSZ, got \"$it\""
        }
        val recipes = arrayIn(obj, "recipes", "index.recipes", problems, 1, 256) { element, path ->
            val digest = element as? JSONObject ?: run {
                problems += "$path: expected an object"
                return@arrayIn null
            }
            checkKeys(digest, path, problems, setOf("id", "version", "file", "sha256"))
            RecipeDigest(
                id = text(digest, "id", path, problems, 120).orEmpty(),
                version = intIn(digest, "version", path, problems, 1, 1_000_000) ?: 0,
                file = text(digest, "file", path, problems, 200).orEmpty(),
                sha256 = text(digest, "sha256", path, problems, 64).orEmpty(),
            )
        }.orEmpty()
        recipes.forEachIndexed { position, digest ->
            val at = "index.recipes[$position]"
            if (!ID_PATTERN.matches(digest.id)) problems += "$at.id: must match ${ID_PATTERN.pattern}, got \"${digest.id}\""
            if (!SHA256_PATTERN.matches(digest.sha256)) problems += "$at.sha256: expected 64 lowercase hex characters"
            if (digest.file.isBlank() || digest.file.contains("..") || digest.file.startsWith("/")) {
                problems += "$at.file: expected a plain file name inside the source"
            }
        }
        if (recipes.map { it.id }.distinct().size != recipes.size) problems += "index.recipes: duplicate recipe ids"
        if (problems.isNotEmpty()) return RecipeParse.Refused(problems)
        return RecipeParse.Parsed(
            RecipeIndex(
                schemaVersion = schemaVersion!!,
                source = source!!,
                revision = revision.orEmpty(),
                generatedAt = generatedAt.orEmpty(),
                recipes = recipes,
            ),
        )
    }

    fun parseRecipe(json: String, maxBytes: Int): RecipeParse<HardwareRecipe> {
        val problems = mutableListOf<String>()
        val obj = objectOf(json, maxBytes, "recipe", problems) ?: return RecipeParse.Refused(problems)
        checkKeys(
            obj, "recipe", problems,
            setOf(
                "schema", "schemaVersion", "id", "version", "maintainer", "evidenceDate",
                "match", "runtime", "model", "workload", "proposal", "evidence", "limits",
            ),
        )
        requireString(obj, "schema", "recipe", problems) { value ->
            if (value == RECIPE_SCHEMA) null else "must be \"$RECIPE_SCHEMA\""
        }
        val schemaVersion = requiredIntIn(obj, "schemaVersion", "recipe", problems, 1, 1_000_000)
        if (schemaVersion != null && schemaVersion != RECIPE_SCHEMA_VERSION) {
            problems += "recipe.schemaVersion: this app reads version $RECIPE_SCHEMA_VERSION, not $schemaVersion"
        }
        val id = requiredText(obj, "id", "recipe", problems, 120)
        if (id != null && !ID_PATTERN.matches(id)) {
            problems += "recipe.id: must match ${ID_PATTERN.pattern}, got \"$id\""
        }
        val version = requiredIntIn(obj, "version", "recipe", problems, 1, 1_000_000)
        val maintainer = requiredText(obj, "maintainer", "recipe", problems, 120)
        val evidenceDate = requiredText(obj, "evidenceDate", "recipe", problems, 10)?.also {
            if (!DATE_PATTERN.matches(it)) problems += "recipe.evidenceDate: expected yyyy-MM-dd, got \"$it\""
        }
        val match = objectOf(obj, "match", "match", problems)
        val deviceMatch = match?.let { deviceMatch(it, problems) }
        val runtime = objectOf(obj, "runtime", "runtime", problems)?.let { runtimeObject(it, problems) }
        val model = objectOf(obj, "model", "model", problems)?.let { modelObject(it, problems) }
        val workload = objectOf(obj, "workload", "workload", problems)?.let { workloadObject(it, problems) }
        val proposal = objectOf(obj, "proposal", "proposal", problems)?.let { proposalObject(it, problems) }
        val evidence = objectOf(obj, "evidence", "evidence", problems)?.let { evidenceObject(it, problems) }
        val limits = objectOf(obj, "limits", "limits", problems)?.let { limitsObject(it, problems) }

        if (problems.isNotEmpty()) return RecipeParse.Refused(problems)
        return RecipeParse.Parsed(
            HardwareRecipe(
                id = id!!,
                version = version!!,
                maintainer = maintainer!!,
                evidenceDate = evidenceDate!!,
                match = deviceMatch!!,
                runtime = runtime!!,
                model = model!!,
                workload = workload!!,
                proposal = proposal!!,
                evidence = evidence!!,
                limits = limits!!,
            ),
        )
    }

    private fun deviceMatch(obj: JSONObject, problems: MutableList<String>): RecipeDeviceMatch {
        checkKeys(
            obj, "match", problems,
            setOf("manufacturers", "models", "socs", "requireCpuFeatures", "minAndroidApi"),
        )
        val manufacturers = stringList(obj, "manufacturers", "match", problems, 1, 16, 64).orEmpty()
        val models = stringList(obj, "models", "match", problems, 1, 64, 64).orEmpty()
        val socs = stringList(obj, "socs", "match", problems, 1, 32, 64).orEmpty()
        if (models.isEmpty() && socs.isEmpty()) problems += "match: at least one of models or socs is required"
        val features = enumList(obj, "requireCpuFeatures", "match", problems, RecipeCpuFeature.entries) { it.wire }
        val minAndroidApi = intIn(obj, "minAndroidApi", "match", problems, 0, 100)
        return RecipeDeviceMatch(
            manufacturers = manufacturers.map(String::lowercase),
            models = models.map(String::lowercase),
            socs = socs.map(String::lowercase),
            requireCpuFeatures = features,
            minAndroidApi = minAndroidApi ?: 0,
        )
    }

    private fun runtimeObject(obj: JSONObject, problems: MutableList<String>): RecipeRuntime {
        checkKeys(obj, "runtime", problems, setOf("engineCommits", "settingsSchemaVersion", "availableBackends"))
        val commits = requiredStringList(obj, "engineCommits", "runtime", problems, 1, 16, 40)?.also { commits ->
            commits.forEachIndexed { index, commit ->
                if (!ENGINE_COMMIT_PATTERN.matches(commit)) {
                    problems += "runtime.engineCommits[$index]: expected a hex engine commit, got \"$commit\""
                }
            }
        }.orEmpty()
        val settingsSchemaVersion = requiredIntIn(obj, "settingsSchemaVersion", "runtime", problems, 1, 1_000)
        if (settingsSchemaVersion != null && settingsSchemaVersion > RECIPE_SCHEMA_VERSION) {
            problems += "runtime.settingsSchemaVersion: newer than this app's $RECIPE_SCHEMA_VERSION"
        }
        val backends = enumList(obj, "availableBackends", "runtime", problems, RecipeBackend.entries) { it.wire }
        if (backends.isEmpty()) problems += "runtime.availableBackends: name at least one backend"
        return RecipeRuntime(
            engineCommits = commits.map(String::lowercase),
            settingsSchemaVersion = settingsSchemaVersion ?: RECIPE_SCHEMA_VERSION,
            availableBackends = backends,
        )
    }

    private fun modelObject(obj: JSONObject, problems: MutableList<String>): RecipeModel {
        checkKeys(obj, "model", problems, setOf("architectures", "quantizations", "sha256", "sizeBytes", "minContextTokens"))
        val architectures = requiredStringList(obj, "architectures", "model", problems, 1, 32, 64).orEmpty()
        val quantizations = requiredStringList(obj, "quantizations", "model", problems, 1, 32, 64).orEmpty()
        val sha256 = text(obj, "sha256", "model", problems, 64)?.also {
            if (!SHA256_PATTERN.matches(it)) problems += "model.sha256: expected 64 lowercase hex characters"
        }
        val sizeBytes = longIn(obj, "sizeBytes", "model", problems, 1, Long.MAX_VALUE)
        val minContextTokens = intIn(obj, "minContextTokens", "model", problems, 512, 1_048_576) ?: 0
        return RecipeModel(
            architectures = architectures.map(String::lowercase),
            quantizations = quantizations.map(String::lowercase),
            sha256 = sha256,
            sizeBytes = sizeBytes,
            minContextTokens = minContextTokens,
        )
    }

    private fun workloadObject(obj: JSONObject, problems: MutableList<String>): RecipeWorkload {
        checkKeys(obj, "workload", problems, setOf("promptTokens", "decodeTokens", "contextDepth", "preference"))
        return RecipeWorkload(
            promptTokens = intIn(obj, "promptTokens", "workload", problems, 1, 1_048_576) ?: 0,
            decodeTokens = intIn(obj, "decodeTokens", "workload", problems, 1, 1_048_576) ?: 0,
            contextDepth = intIn(obj, "contextDepth", "workload", problems, 0, 1_048_576) ?: 0,
            preference = enumOf(obj, "preference", "workload", problems, RecipeWorkloadPreference.entries) { it.wire }
                ?: RecipeWorkloadPreference.CHAT,
        )
    }

    private fun proposalObject(obj: JSONObject, problems: MutableList<String>): RecipeProposal {
        checkKeys(
            obj, "proposal", problems,
            setOf(
                "backend", "contextTokens", "threads", "decodeThreads", "cpuMask", "poll",
                "flashAttention", "kvCache", "loadMode", "batchTokens", "ubatchTokens", "threadPriority",
            ),
        )
        // The empty mask is a value, not an omission: it means the engine's default affinity, and
        // it is how "all cores" is written in a proposal.
        val cpuMask = textAllowingBlank(obj, "cpuMask", "proposal", problems, 16)?.also {
            if (it.isNotEmpty() && !HEX_MASK_PATTERN.matches(it)) {
                problems += "proposal.cpuMask: expected up to 16 hex characters, got \"$it\""
            }
        }
        return RecipeProposal(
            backend = enumOf(obj, "backend", "proposal", problems, RecipeBackend.entries) { it.wire },
            contextTokens = intIn(obj, "contextTokens", "proposal", problems, 512, 1_048_576),
            threads = intIn(obj, "threads", "proposal", problems, 1, 256),
            decodeThreads = intIn(obj, "decodeThreads", "proposal", problems, 1, 256),
            cpuMask = cpuMask,
            poll = intIn(obj, "poll", "proposal", problems, -1, 100),
            flashAttention = enumOf(obj, "flashAttention", "proposal", problems, FlashAttentionMode.entries) { it.wire },
            kvCache = enumOf(obj, "kvCache", "proposal", problems, KvCacheType.entries) { it.wire },
            loadMode = enumOf(obj, "loadMode", "proposal", problems, LoadMode.entries) { it.wire },
            batchTokens = intIn(obj, "batchTokens", "proposal", problems, 1, 16_384),
            ubatchTokens = intIn(obj, "ubatchTokens", "proposal", problems, 1, 16_384),
            threadPriority = enumOf(obj, "threadPriority", "proposal", problems, ThreadPriority.entries) { it.wire },
        )
    }

    private fun evidenceObject(obj: JSONObject, problems: MutableList<String>): RecipeEvidence {
        checkKeys(
            obj, "evidence", problems,
            setOf(
                "method", "agreement", "promptTokPerSec", "promptSpread", "decodeTokPerSec", "decodeSpread",
                "depthDecodeTokPerSec", "depthDecodeSpread", "batteryCelsiusStart", "batteryCelsiusEnd",
            ),
        )
        val agreement = doubleIn(obj, "agreement", "evidence", problems, 0.0, 1.0)
        return RecipeEvidence(
            method = text(obj, "method", "evidence", problems, 512).orEmpty(),
            agreement = agreement,
            promptTokPerSec = rate(obj, "promptTokPerSec", problems),
            promptSpread = rate(obj, "promptSpread", problems),
            decodeTokPerSec = rate(obj, "decodeTokPerSec", problems),
            decodeSpread = rate(obj, "decodeSpread", problems),
            depthDecodeTokPerSec = rate(obj, "depthDecodeTokPerSec", problems),
            depthDecodeSpread = rate(obj, "depthDecodeSpread", problems),
            batteryCelsiusStart = doubleIn(obj, "batteryCelsiusStart", "evidence", problems, -40.0, 120.0),
            batteryCelsiusEnd = doubleIn(obj, "batteryCelsiusEnd", "evidence", problems, -40.0, 120.0),
        )
    }

    private fun limitsObject(obj: JSONObject, problems: MutableList<String>): RecipeLimits {
        checkKeys(obj, "limits", problems, setOf("expiresAfterDays", "revalidateOn", "incompatible"))
        return RecipeLimits(
            expiresAfterDays = intIn(obj, "expiresAfterDays", "limits", problems, 1, 3_650),
            revalidateOn = enumList(obj, "revalidateOn", "limits", problems, RecipeRevalidation.entries) { it.wire },
            incompatible = stringList(obj, "incompatible", "limits", problems, 0, 16, 200).orEmpty(),
        )
    }

    private fun rate(obj: JSONObject, key: String, problems: MutableList<String>): Double? =
        doubleIn(obj, key, "evidence", problems, 0.0, TOKENS_PER_SECOND_CEILING)

    // ---------------------------------------------------------------- primitive readers

    private fun objectOf(json: String, maxBytes: Int, path: String, problems: MutableList<String>): JSONObject? {
        if (json.length > maxBytes) {
            problems += "$path: document is ${json.length} characters, over the $maxBytes limit"
            return null
        }
        return try {
            JSONObject(json)
        } catch (error: JSONException) {
            problems += "$path: not a JSON object (${error.message})"
            null
        }
    }

    /** A required nested object; a missing or mistyped section is named rather than defaulted. */
    private fun objectOf(parent: JSONObject, key: String, path: String, problems: MutableList<String>): JSONObject? {
        if (!parent.has(key) || parent.isNull(key)) {
            problems += "$path: required"
            return null
        }
        val value = parent.get(key)
        if (value !is JSONObject) {
            problems += "$path: expected an object"
            return null
        }
        return value
    }

    private fun <T> arrayIn(
        obj: JSONObject,
        key: String,
        path: String,
        problems: MutableList<String>,
        minSize: Int,
        maxSize: Int,
        element: (Any?, String) -> T?,
    ): List<T>? {
        if (!obj.has(key) || obj.isNull(key)) {
            problems += "$path: required"
            return null
        }
        val array = obj.get(key)
        if (array !is JSONArray) {
            problems += "$path: expected an array"
            return null
        }
        if (array.length() < minSize || array.length() > maxSize) {
            problems += "$path: must have between $minSize and $maxSize entries, got ${array.length()}"
            return null
        }
        val values = mutableListOf<T>()
        for (index in 0 until array.length()) {
            element(array.get(index), "$path[$index]")?.let(values::add)
        }
        return values
    }

    private fun checkKeys(obj: JSONObject, path: String, problems: MutableList<String>, allowed: Set<String>) {
        obj.keys().asSequence()
            .filter { it !in allowed }
            .sorted()
            .forEach { problems += "$path.$it: unknown field" }
    }

    /**
     * A field a document must carry. Identity is not optional: a recipe with no maintainer, date,
     * or schema version is not something to interpret generously, and the parser would otherwise
     * reach a non-null assertion with nothing to assert.
     */
    private fun requiredText(
        obj: JSONObject,
        key: String,
        path: String,
        problems: MutableList<String>,
        maxLength: Int,
    ): String? {
        if (!obj.has(key) || obj.isNull(key)) {
            problems += "$path.$key: required"
            return null
        }
        return text(obj, key, path, problems, maxLength)
    }

    /** A bounded integer a document must carry; absent is named as required, not silently zero. */
    private fun requiredIntIn(
        obj: JSONObject,
        key: String,
        path: String,
        problems: MutableList<String>,
        min: Int,
        max: Int,
    ): Int? {
        if (!obj.has(key) || obj.isNull(key)) {
            problems += "$path.$key: required"
            return null
        }
        return intIn(obj, key, path, problems, min, max)
    }

    /**
     * A list a document must carry and that must not be empty: an empty engine-commit or model list
     * can never match anything, so accepting it would be accepting a recipe that cannot apply.
     */
    private fun requiredStringList(
        obj: JSONObject,
        key: String,
        path: String,
        problems: MutableList<String>,
        minSize: Int,
        maxSize: Int,
        maxEntryLength: Int,
    ): List<String>? {
        if (!obj.has(key) || obj.isNull(key)) {
            problems += "$path.$key: required"
            return null
        }
        return stringList(obj, key, path, problems, minSize, maxSize, maxEntryLength)
    }

    /** An enum value a document must carry; absent is named as required rather than defaulted. */
    private fun <T> requiredEnumOf(
        obj: JSONObject,
        key: String,
        path: String,
        problems: MutableList<String>,
        entries: List<T>,
        wire: (T) -> String,
    ): T? {
        if (!obj.has(key) || obj.isNull(key)) {
            problems += "$path.$key: required"
            return null
        }
        return enumOf(obj, key, path, problems, entries, wire)
    }

    /** Like [text], but accepts "" — for fields where empty is a value (the CPU mask). */
    private fun textAllowingBlank(
        obj: JSONObject,
        key: String,
        path: String,
        problems: MutableList<String>,
        maxLength: Int,
    ): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.get(key)
        if (value !is String) {
            problems += "$path.$key: expected a string"
            return null
        }
        if (value.length > maxLength) {
            problems += "$path.$key: longer than $maxLength characters"
            return null
        }
        return value
    }

    private fun text(obj: JSONObject, key: String, path: String, problems: MutableList<String>, maxLength: Int): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.get(key)
        if (value !is String) {
            problems += "$path.$key: expected a string"
            return null
        }
        if (value.length > maxLength) {
            problems += "$path.$key: longer than $maxLength characters"
            return null
        }
        if (value.isBlank()) {
            problems += "$path.$key: must not be blank"
            return null
        }
        return value
    }

    private inline fun requireString(
        obj: JSONObject,
        key: String,
        path: String,
        problems: MutableList<String>,
        check: (String) -> String?,
    ) {
        val value = text(obj, key, path, problems, 200) ?: run {
            if (!obj.has(key)) problems += "$path.$key: required"
            return
        }
        check(value)?.let { problems += "$path.$key: $it" }
    }

    private fun intIn(
        obj: JSONObject,
        key: String,
        path: String,
        problems: MutableList<String>,
        min: Int,
        max: Int,
    ): Int? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.get(key)
        if (value !is Number || value.toDouble() != Math.floor(value.toDouble())) {
            problems += "$path.$key: expected an integer"
            return null
        }
        val asInt = value.toInt()
        if (asInt < min || asInt > max) {
            problems += "$path.$key: must be between $min and $max, got $asInt"
            return null
        }
        return asInt
    }

    private fun longIn(
        obj: JSONObject,
        key: String,
        path: String,
        problems: MutableList<String>,
        min: Long,
        max: Long,
    ): Long? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.get(key)
        if (value !is Number || value.toDouble() != Math.floor(value.toDouble())) {
            problems += "$path.$key: expected an integer"
            return null
        }
        val asLong = value.toLong()
        if (asLong < min || asLong > max) {
            problems += "$path.$key: must be between $min and $max, got $asLong"
            return null
        }
        return asLong
    }

    private fun doubleIn(
        obj: JSONObject,
        key: String,
        path: String,
        problems: MutableList<String>,
        min: Double,
        max: Double,
    ): Double? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.get(key)
        // Numbers only: a quoted "1.0" is a different document from 1.0, and the schema says so too.
        if (value !is Number) {
            problems += "$path.$key: expected a number"
            return null
        }
        val asDouble = value.toDouble()
        if (!asDouble.isFinite()) {
            problems += "$path.$key: expected a finite number"
            return null
        }
        if (asDouble < min || asDouble > max) {
            problems += "$path.$key: must be between $min and $max, got $asDouble"
            return null
        }
        return asDouble
    }

    private fun stringList(
        obj: JSONObject,
        key: String,
        path: String,
        problems: MutableList<String>,
        minSize: Int,
        maxSize: Int,
        maxEntryLength: Int,
    ): List<String>? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val array = obj.get(key)
        if (array !is JSONArray) {
            problems += "$path.$key: expected an array of strings"
            return null
        }
        if (array.length() < minSize || array.length() > maxSize) {
            problems += "$path.$key: must have between $minSize and $maxSize entries, got ${array.length()}"
            return null
        }
        val values = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val raw = array.get(index)
            if (raw !is String || raw.isBlank() || raw.length > maxEntryLength) {
                problems += "$path.$key[$index]: expected a non-blank string of at most $maxEntryLength characters"
                return null
            }
            values += raw.trim()
        }
        return values
    }

    private fun <T> enumOf(
        obj: JSONObject,
        key: String,
        path: String,
        problems: MutableList<String>,
        entries: List<T>,
        wire: (T) -> String,
    ): T? {
        val raw = text(obj, key, path, problems, 64) ?: return null
        val match = entries.firstOrNull { wire(it).equals(raw, ignoreCase = true) }
        if (match == null) {
            val names = entries.joinToString(", ") { wire(it) }
            problems += "$path.$key: unknown value \"$raw\" (expected one of: $names)"
        }
        return match
    }

    private fun <T> enumList(
        obj: JSONObject,
        key: String,
        path: String,
        problems: MutableList<String>,
        entries: List<T>,
        wire: (T) -> String,
    ): Set<T> {
        val raw = stringList(obj, key, path, problems, 0, 32, 64) ?: return emptySet()
        val resolved = mutableSetOf<T>()
        raw.forEachIndexed { index, value ->
            val match = entries.firstOrNull { wire(it).equals(value, ignoreCase = true) }
            if (match == null) {
                val names = entries.joinToString(", ") { wire(it) }
                problems += "$path.$key[$index]: unknown value \"$value\" (expected one of: $names)"
            } else {
                resolved += match
            }
        }
        return resolved
    }
}
