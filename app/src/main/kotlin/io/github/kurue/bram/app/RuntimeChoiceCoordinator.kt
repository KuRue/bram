package io.github.kurue.bram.app

/**
 * Persists and restores the runtime a user has chosen, with the store injected as a pair of
 * suspending lambdas.
 *
 * The lambdas exist so this stays checkable: the app module's unit tests run on a plain JVM with no
 * Robolectric and no Android context, so a coordinator that reached for `SharedPreferences` itself
 * could not be tested at all. Keeping the edges here means the behaviour that caused the routing
 * defect — a choice being made and then forgotten — is asserted directly, and the real store stays a
 * two-line adapter at the edge.
 *
 * A `null` choice is written rather than removed, so the store cannot be left claiming a selection
 * that was never made.
 */
internal class RuntimeChoiceCoordinator(
    private val loadPersistedChoice: suspend () -> String?,
    private val savePersistedChoice: suspend (String?) -> Unit,
) {
    /** Records an explicit selection. Both local and remote go through here unchanged. */
    suspend fun onChoiceSelected(runtimeId: String?) = savePersistedChoice(runtimeId)

    /**
     * Decides what a launch starts on, by handing the persisted choice to the same pure precedence
     * the rest of the fix uses. With nothing persisted this resolves exactly as it did before the
     * key existed, which is what keeps an existing local install's behaviour unchanged.
     */
    suspend fun resolveOnLaunch(
        profileRuntimeId: String?,
        defaultRuntimeId: String?,
    ): String? = RuntimeChoiceResolver.resolve(
        persistedChoice = loadPersistedChoice(),
        profileRuntimeId = profileRuntimeId,
        defaultRuntimeId = defaultRuntimeId,
    )
}
