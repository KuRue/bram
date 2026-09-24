package io.github.kurue.bram.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the precedence that decides which runtime a cold start resumes on.
 *
 * Each case here is a way the routing bug came back before: a remote pick that did not survive a
 * restart, a local profile restore that clobbered an explicit remote choice, and an existing install
 * that silently changed behaviour because a new key appeared.
 */
class RuntimeChoiceResolverTest {
    @Test
    fun anExplicitChoiceSurvivesARestart() {
        assertEquals(
            "remote:mock",
            RuntimeChoiceResolver.resolve(
                persistedChoice = "remote:mock",
                profileRuntimeId = "local:abc",
                defaultRuntimeId = "local:first",
            ),
        )
    }

    @Test
    fun aLocalProfileRestoreDoesNotClobberAnExplicitRemoteChoice() {
        assertEquals(
            "remote:mock",
            RuntimeChoiceResolver.resolve(
                persistedChoice = "remote:mock",
                profileRuntimeId = "local:abc",
                defaultRuntimeId = null,
            ),
        )
    }

    @Test
    fun anInstallWithoutTheNewKeyBehavesExactlyAsItDidBefore() {
        // Back-compat: no persisted choice is the state of every pre-existing install. It must keep
        // resolving to the profile's runtime, so adding the key changes nothing for a local user.
        assertEquals(
            "local:abc",
            RuntimeChoiceResolver.resolve(
                persistedChoice = null,
                profileRuntimeId = "local:abc",
                defaultRuntimeId = "local:first",
            ),
        )
    }

    @Test
    fun aColdStartWithNothingSavedFallsBackToTheDefault() {
        assertEquals(
            "local:first",
            RuntimeChoiceResolver.resolve(
                persistedChoice = null,
                profileRuntimeId = null,
                defaultRuntimeId = "local:first",
            ),
        )
    }

    @Test
    fun aColdStartWithNothingSavedAtAllResolvesToNothingRatherThanGuessing() {
        assertEquals(
            null,
            RuntimeChoiceResolver.resolve(
                persistedChoice = null,
                profileRuntimeId = null,
                defaultRuntimeId = null,
            ),
        )
    }
}
