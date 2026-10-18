package io.github.kurue.bram.core.domain

import java.util.Locale

/**
 * How closely a recipe's evidence describes this phone.
 *
 * [EXACT] means the device, the engine build, and (when recorded) the exact model file all match
 * what the recipe was measured on. [SEED] means the device family matched but something did not —
 * the reasons say what — so the settings are worth trying and must be revalidated locally.
 */
enum class RecipeFidelity { EXACT, SEED }

/** One field a proposal would change, in the words the settings screens already use. */
data class ProposalChange(val field: String, val label: String, val from: String, val to: String)

/** A recipe that applies to this phone, with what it would change and why it is only a seed. */
data class RecipeCandidate(
    val recipe: HardwareRecipe,
    val fidelity: RecipeFidelity,
    /** Empty for an exact match; otherwise one line per ingredient that did not match. */
    val reasons: List<String>,
    val changes: List<ProposalChange>,
)

/**
 * Ranks recipes against a phone.
 *
 * The order of the product plan is kept literally: exact device + engine + model first, then
 * SoC/model-family seeds, then nothing. A seed is not evidence — it is a search suggestion that
 * still has to survive local validation, which is why the reasons travel with the candidate
 * instead of being flattened into a score.
 */
object RecipeMatcher {

    fun candidates(
        recipes: List<HardwareRecipe>,
        device: RecipeDeviceIdentity,
        model: RecipeModelIdentity,
        profile: ModelProfile,
    ): List<RecipeCandidate> =
        recipes.mapNotNull { recipe -> candidate(recipe, device, model, profile) }
            .sortedWith(compareBy({ if (it.fidelity == RecipeFidelity.EXACT) 0 else 1 }, { it.recipe.id }))

    private fun candidate(
        recipe: HardwareRecipe,
        device: RecipeDeviceIdentity,
        model: RecipeModelIdentity,
        profile: ModelProfile,
    ): RecipeCandidate? {
        val reasons = mutableListOf<String>()

        if (recipe.match.manufacturers.none { it.equals(device.manufacturer, ignoreCase = true) }) return null
        val modelMatched = recipe.match.models.any { it.equals(device.model, ignoreCase = true) }
        val socMatched = recipe.match.socs.any { it.equals(device.soc, ignoreCase = true) }
        // A chip the recipe never named is the end of it: settings like thread counts and CPU masks
        // are shaped by the SoC, so a same-name device string is not enough to carry them.
        if (device.soc.isNotBlank() && recipe.match.socs.isNotEmpty() && !socMatched) return null
        if (!modelMatched && !socMatched) return null
        if (!modelMatched) {
            reasons += "measured on a different model in the same family"
        }

        if (recipe.match.minAndroidApi > 0 && device.androidApi < recipe.match.minAndroidApi) return null
        val features = RecipeCpuFeature.of(device.cpuFeatures)
        val missing = recipe.match.requireCpuFeatures - features
        if (missing.isNotEmpty()) return null
        val backendsMissing = recipe.runtime.availableBackends - device.availableBackends
        if (backendsMissing.isNotEmpty()) return null

        if (model.architecture.lowercase() !in recipe.model.architectures) return null
        if (model.quantization.lowercase() !in recipe.model.quantizations) return null
        if (recipe.model.minContextTokens > 0 && model.contextTokens < recipe.model.minContextTokens) return null

        if (recipe.runtime.engineCommits.none { it.equals(device.engineCommit, ignoreCase = true) }) {
            reasons += "measured on engine ${recipe.runtime.engineCommits.firstOrNull() ?: "unknown"}, this build is ${device.engineCommit}"
        }
        if (recipe.model.sha256 != null && model.sha256 == null) {
            reasons += "this model's content hash is unknown"
        } else if (recipe.model.sha256 != null && recipe.model.sha256 != model.sha256) {
            reasons += "measured on different model bytes"
        }
        if (recipe.model.sha256 == null) reasons += "the recipe does not identify the measured model bytes"
        if (recipe.model.sizeBytes != null && recipe.model.sizeBytes != model.sizeBytes) {
            reasons += "measured on a different model size"
        }

        return RecipeCandidate(
            recipe = recipe,
            fidelity = if (reasons.isEmpty()) RecipeFidelity.EXACT else RecipeFidelity.SEED,
            reasons = reasons,
            changes = recipe.proposal.changesFor(profile),
        )
    }
}

/**
 * What a proposal would change, asked of the profile it would apply to.
 *
 * Only differences are returned, so the UI can show "Batch 512 → 1024" instead of restating the
 * settings that already match. Applying is the caller's decision and happens after local
 * validation; this function computes nothing but the delta.
 */
fun RecipeProposal.changesFor(profile: ModelProfile): List<ProposalChange> {
    val changes = mutableListOf<ProposalChange>()
    backend?.let { proposed ->
        val current = RecipeBackend.ofProfile(profile.backendId)
        if (current != proposed) {
            changes += ProposalChange("backend", "Backend", current.label, proposed.label)
        }
    }
    contextTokens?.let { proposed ->
        if (proposed != profile.contextTokens) {
            changes += ProposalChange("contextTokens", "Context", tokens(profile.contextTokens), tokens(proposed))
        }
    }
    threads?.let { proposed ->
        if (proposed != profile.threads) {
            changes += ProposalChange("threads", "Threads", threads(profile.threads), threads(proposed))
        }
    }
    decodeThreads?.let { proposed ->
        if (proposed != profile.decodeThreads) {
            changes += ProposalChange(
                "decodeThreads", "Decode threads", threads(profile.decodeThreads), threads(proposed),
            )
        }
    }
    cpuMask?.let { proposed ->
        val current = profile.cpuMask.lowercase(Locale.US)
        if (!proposed.equals(current, ignoreCase = true)) {
            changes += ProposalChange("cpuMask", "CPU mask", mask(current), mask(proposed))
        }
    }
    poll?.let { proposed ->
        if (proposed != profile.poll) {
            changes += ProposalChange("poll", "Poll", poll(profile.poll), poll(proposed))
        }
    }
    flashAttention?.let { proposed ->
        if (proposed != profile.flashAttention) {
            changes += ProposalChange("flashAttention", "Attention", profile.flashAttention.label, proposed.label)
        }
    }
    kvCache?.let { proposed ->
        if (proposed != profile.kvCacheType) {
            changes += ProposalChange("kvCache", "KV cache", profile.kvCacheType.label, proposed.label)
        }
    }
    loadMode?.let { proposed ->
        if (proposed != profile.loadMode) {
            changes += ProposalChange("loadMode", "Load mode", profile.loadMode.label, proposed.label)
        }
    }
    batchTokens?.let { proposed ->
        if (proposed != profile.batchTokens) {
            changes += ProposalChange("batchTokens", "Batch", batch(profile.batchTokens), batch(proposed))
        }
    }
    ubatchTokens?.let { proposed ->
        if (proposed != profile.ubatchTokens) {
            changes += ProposalChange("ubatchTokens", "Micro-batch", batch(profile.ubatchTokens), batch(proposed))
        }
    }
    threadPriority?.let { proposed ->
        if (proposed != profile.threadPriority) {
            changes += ProposalChange(
                "threadPriority", "Thread priority", profile.threadPriority.label, proposed.label,
            )
        }
    }
    return changes
}

/** The name Bram's UI already uses for a backend; the recipe vocabulary is not user-facing. */
val RecipeBackend.label: String
    get() = when (this) {
        RecipeBackend.CPU -> "CPU"
        RecipeBackend.VULKAN -> "Vulkan"
        RecipeBackend.OPENCL -> "OpenCL"
        RecipeBackend.HEXAGON -> "NPU"
    }

/** "8K", the way token counts are shown everywhere else. */
private fun tokens(value: Int): String = when {
    value <= 0 -> "unknown"
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.US, "%.0fK", value / 1_000.0)
    else -> value.toString()
}

/** "Auto" for the engine-chosen values, which are stored as zero. */
private fun threads(value: Int): String = if (value <= 0) "Auto" else value.toString()

private fun batch(value: Int): String = if (value <= 0) "Auto" else value.toString()

private fun poll(value: Int): String = if (value < 0) "Default" else value.toString()

private fun mask(value: String): String = when {
    value.isEmpty() -> "All cores"
    else -> "0x$value"
}
