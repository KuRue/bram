package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.LocalModelRecord
import io.github.kurue.bram.core.domain.ModelProfile
import io.github.kurue.bram.core.domain.RecipeDraft
import io.github.kurue.bram.core.domain.RecipeExportBuilder
import io.github.kurue.bram.core.domain.RecipeModelIdentity
import io.github.kurue.bram.core.domain.RecipeBackend
import io.github.kurue.bram.core.domain.label
import java.time.LocalDate

/**
 * Turns this phone's own measurement into a recipe, or says what is missing first.
 *
 * Opt-in by construction: nothing here runs unless the person asks for it, and [RecipeExportBuilder]
 * reads only the device identity, the model's content identity, the profile's runtime settings, and
 * the benchmark — never a prompt, a conversation, a file name, or a tuning note.
 */
internal fun recipeExportFor(
    state: AppUiState,
    profileId: String,
    maintainer: String,
    today: String = LocalDate.now().toString(),
): RecipeDraft {
    val profile = state.profiles.firstOrNull { it.id == profileId }
        ?: return RecipeDraft.NotReady("Choose a local profile first")
    val model = state.localModels.firstOrNull { it.id == profile.modelId }
        ?: return RecipeDraft.NotReady("This profile's model is not in local storage")
    if (model.parts.isNotEmpty()) {
        return RecipeDraft.NotReady("A split model has no single content hash to name; export a single-file model")
    }
    val device = state.recipeDeviceIdentity
        ?: return RecipeDraft.NotReady("This phone's identity is not known yet; reopen the profile screen")
    val run = state.benchRuns
        .filter { it.profileId == profile.id && it.fingerprint == state.measurementFingerprint }
        .maxByOrNull { it.startedAtEpochMillis }
        ?: return RecipeDraft.NotReady("Run a benchmark on this profile first: a recipe quotes measurements, not intentions")
    return RecipeExportBuilder.build(
        device = device,
        model = RecipeModelIdentity(
            architecture = model.architecture,
            quantization = model.quantization,
            sizeBytes = model.fileSizeBytes,
            sha256 = model.sha256.takeIf { it.isNotBlank() },
            contextTokens = profile.contextTokens,
        ),
        profile = profile,
        run = run,
        maintainer = maintainer,
        today = today,
        currentFingerprint = state.measurementFingerprint,
        // An accelerator's speed only counts because its output agreed with the CPU; the record of
        // that agreement belongs in the recipe, and a CPU run has nothing to record.
        measurement = profile.measurements.firstOrNull { it.backendId == profile.backendId && !it.isReference },
    )
}

/** The profile a person is most likely exporting from: the one they are looking at. */
internal fun exportableProfileId(state: AppUiState, chosen: String?): String? =
    chosen ?: state.activeProfileId ?: state.profiles.firstOrNull()?.id

/** What the export screen says about the profile it is about to describe. */
internal fun exportModelLine(profile: ModelProfile, model: LocalModelRecord?): String =
    model?.let { "${it.displayName} · ${it.quantization} · ${it.fileSizeBytes / (1024 * 1024)} MB" }
        ?: "Model bytes are missing from this phone"

/** The backend a recipe would name, in the words the app uses. */
internal fun exportBackendLabel(profile: ModelProfile): String =
    RecipeBackend.ofProfile(profile.backendId).label
