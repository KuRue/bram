package io.github.kurue.bram.core.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only recipe-vocabulary settings change. Prompts, sampling, permissions and memory bounds do not. */
fun RecipeProposal.applyTo(profile: ModelProfile): ModelProfile = profile.copy(
    backendId = backend?.profileId ?: profile.backendId,
    contextTokens = contextTokens ?: profile.contextTokens,
    threads = threads ?: profile.threads,
    decodeThreads = decodeThreads ?: profile.decodeThreads,
    cpuMask = cpuMask?.lowercase() ?: profile.cpuMask,
    poll = poll ?: profile.poll,
    flashAttention = flashAttention ?: profile.flashAttention,
    kvCacheType = kvCache ?: profile.kvCacheType,
    loadMode = loadMode ?: profile.loadMode,
    batchTokens = batchTokens ?: profile.batchTokens,
    ubatchTokens = ubatchTokens ?: profile.ubatchTokens,
    threadPriority = threadPriority ?: profile.threadPriority,
)

/** The exact load inputs; evidence for one setting combination cannot exclude another. */
fun ModelProfile.recipeConfigurationIdentity(): String = listOf(
    id, modelId.value, contextTokens, backendId, threads, decodeThreads, cpuMask.lowercase(), cpuStrict,
    poll, flashAttention.wire, kvCacheType.wire, loadMode.wire, batchTokens, ubatchTokens,
    threadPriority.wire, thinkingEnabled, hexFlags, streamExperts, streamCacheMb, streamDenseAnon, streamOverlap,
).joinToString("|")

/** Refuse silent runtime clamping and policy escapes before spending time loading a candidate. */
fun recipeEligibility(profile: ModelProfile, model: LocalModelRecord, cores: Int, available: Set<RecipeBackend>): String? {
    if (profile.streamExperts) return "Recipe validation does not yet support expert-streaming profiles"
    val backend = RecipeBackend.entries.firstOrNull { it.profileId == profile.backendId }
        ?: return "The profile names an unknown backend"
    if (backend == RecipeBackend.VULKAN) return "Vulkan is excluded by the current automatic validation policy"
    if (backend !in available) return "The proposed backend is unavailable in this build"
    if (profile.contextTokens < 512 || (model.trainedContextTokens > 0 && profile.contextTokens > model.trainedContextTokens)) {
        return "Context exceeds this model's supported range"
    }
    if (profile.threads !in 0..minOf(cores, 64) || profile.decodeThreads !in 0..minOf(cores, 64)) {
        return "Thread count exceeds the cores this app can use"
    }
    if (profile.cpuMask.isNotEmpty()) {
        val mask = profile.cpuMask.toULongOrNull(16) ?: return "CPU mask is invalid"
        if (mask == 0UL || (cores < 64 && (mask shr cores.coerceAtLeast(0)) != 0UL)) return "CPU mask names unavailable cores"
    }
    val effectiveBatch = if (profile.batchTokens == 0) minOf(512, profile.contextTokens) else profile.batchTokens
    if (effectiveBatch !in 1..profile.contextTokens || profile.ubatchTokens !in 0..effectiveBatch) {
        return "Batch or micro-batch exceeds the proposed context or batch"
    }
    if (profile.sanitizedRuntime() != profile) return "The proposed settings would be changed by runtime sanitization"
    return null
}

enum class RecipeVerdict { CHECKING, APPLIED, KEPT, REJECTED, INTERRUPTED }

data class RecipeTrial(val predictions: List<Int>, val promptMillis: Long, val decodeMillis: Long) {
    val totalMillis: Long get() = promptMillis + decodeMillis
}

data class RecipeValidationDecision(
    val verdict: RecipeVerdict,
    val detail: String,
    val incumbentMillis: Long = 0,
    val proposedMillis: Long = 0,
)

/**
 * Two comparable pairs, with reversed order, must both beat the incumbent beyond the existing
 * noise margin. A single lucky timing never applies a recipe. All replays must return the full CPU
 * sequence, and the final combination must also agree near its intended context limit.
 * The adapter owns native deadlines, warmup, thermal checks and memory enforcement.
 */
object RecipeValidationRunner {
    const val REFERENCE_TOKENS = 24
    const val CONTEXT_RESERVE = 256

    suspend fun run(
        incumbent: ModelProfile,
        proposed: ModelProfile,
        contextDepth: Int,
        reference: suspend (profile: ModelProfile, pad: Int) -> List<Int>,
        trial: suspend (profile: ModelProfile, tokens: List<Int>, pad: Int) -> RecipeTrial,
        checkpoint: suspend () -> Unit = {},
        phase: (String) -> Unit = {},
    ): RecipeValidationDecision {
        suspend fun check() { currentCoroutineContext().ensureActive(); checkpoint() }
        val commonContext = minOf(incumbent.contextTokens, proposed.contextTokens)
        val pad = contextDepth.coerceIn(0, (commonContext - CONTEXT_RESERVE).coerceAtLeast(0))
        suspend fun cpu(at: ModelProfile, padding: Int): List<Int> {
            check()
            phase("Recording CPU reference at $padding padding tokens…")
            return reference(at, padding).also {
                require(it.size in 1..REFERENCE_TOKENS && it.all { token -> token >= 0 }) {
                    "CPU reference returned an empty or invalid token sequence; previous settings kept"
                }
            }
        }
        suspend fun replay(at: ModelProfile, tokens: List<Int>, padding: Int): RecipeTrial? {
            check()
            val result = trial(at, tokens, padding)
            check()
            if (result.predictions.size != tokens.size ||
                !AcceleratorAgreement.isUsable(AcceleratorAgreement.score(tokens, result.predictions)) ||
                result.promptMillis <= 0 || result.decodeMillis <= 0 || result.totalMillis <= 0) return null
            return result
        }
        val tokens = cpu(incumbent, pad)
        var oldMillis = 0L
        var newMillis = 0L
        repeat(2) { round ->
            val results = mutableMapOf<Boolean, RecipeTrial>()
            for (challenger in if (round == 0) listOf(false, true) else listOf(true, false)) {
                phase("Pair ${round + 1}/2: ${if (challenger) "recipe" else "current settings"}…")
                val result = replay(if (challenger) proposed else incumbent, tokens, pad)
                    ?: return RecipeValidationDecision(if (challenger) RecipeVerdict.REJECTED else RecipeVerdict.INTERRUPTED,
                        "${if (challenger) "Recipe" else "Current profile"} failed complete CPU agreement or returned invalid timings; previous settings kept")
                results[challenger] = result
            }
            val before = results.getValue(false).totalMillis
            val after = results.getValue(true).totalMillis
            oldMillis += before
            newMillis += after
            val winner = ConfigurationPlanner.pickWinner(listOf(TrialScore("current", before), TrialScore("recipe", after)), "current")
            if (winner?.label != "recipe") return RecipeValidationDecision(
                RecipeVerdict.KEPT, "Kept current settings: the recipe was not clearly faster in both paired checks", oldMillis, newMillis,
            )
        }
        val finalPad = (proposed.contextTokens - CONTEXT_RESERVE).coerceAtLeast(0)
        val finalTokens = if (finalPad == pad) tokens else cpu(proposed, finalPad)
        phase("Validating the final combination near ${proposed.contextTokens} context tokens…")
        if (replay(proposed, finalTokens, finalPad) == null) return RecipeValidationDecision(
            RecipeVerdict.REJECTED, "Recipe failed complete CPU agreement at its intended context; previous settings kept", oldMillis, newMillis,
        )
        check()
        return RecipeValidationDecision(
            RecipeVerdict.APPLIED, "Recipe passed CPU agreement and was more than 5% faster in both paired checks", oldMillis, newMillis,
        )
    }
}
