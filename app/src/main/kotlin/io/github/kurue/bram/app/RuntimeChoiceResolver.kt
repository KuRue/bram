package io.github.kurue.bram.app

/**
 * Decides which runtime a fresh launch should start on.
 *
 * This exists because that decision used to be made implicitly, by whatever `restoreLastModel()`
 * happened to reach first, and every branch of it was local-only. A user who explicitly picked a
 * remote endpoint had that pick thrown away on the next launch, because the selection was never
 * persisted and the last-used profile was restored ahead of anything else. Making the rule explicit
 * and pure is what stops the precedence from drifting again, and it is deliberately tiny so it can
 * grow into a full resolver later without a second migration of the persisted value.
 *
 * Precedence, highest first:
 *  1. [persistedChoice] — what the user last actually chose, local or remote. It wins outright: a
 *     local restore must never clobber an explicit remote pick.
 *  2. [profileRuntimeId] — the runtime implied by the last-used profile. This is the back-compat
 *     path, and it reproduces today's behaviour for every install that predates the new key.
 *  3. [defaultRuntimeId] — the cold-start default, normally the first available local model.
 */
internal object RuntimeChoiceResolver {
    fun resolve(
        persistedChoice: String?,
        profileRuntimeId: String?,
        defaultRuntimeId: String?,
    ): String? = persistedChoice ?: profileRuntimeId ?: defaultRuntimeId
}
