package io.github.kurue.bram.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The persistence half of the routing fix, tested without Android.
 *
 * Bram's app-module unit tests run on a plain JVM — there is no Robolectric and no Android context —
 * so anything that touches `SharedPreferences` cannot be asserted here. Rather than let the save/load
 * behaviour go untested, the orchestration is kept pure and the store is injected as a pair of
 * suspending lambdas. That is the same reason the precedence itself lives in
 * [RuntimeChoiceResolver]: the I/O edges stay thin and the rules stay checkable.
 */
class RuntimeChoiceCoordinatorTest {
    @Test
    fun clearingAChoicePersistsTheAbsenceRatherThanLeavingAStaleValue() = runTest {
        var stored: String? = "local:old"
        val coordinator = RuntimeChoiceCoordinator(
            loadPersistedChoice = { stored },
            savePersistedChoice = { stored = it },
        )

        coordinator.onChoiceSelected(null)

        assertEquals(null, stored)
    }

    @Test
    fun selectingARemoteEndpointPersistsIt() = runTest {
        var stored: String? = null
        val coordinator = RuntimeChoiceCoordinator(
            loadPersistedChoice = { stored },
            savePersistedChoice = { stored = it },
        )

        coordinator.onChoiceSelected("remote:mock")

        assertEquals("remote:mock", stored)
    }

    @Test
    fun selectingALocalModelPersistsItJustTheSame() = runTest {
        var stored: String? = null
        val coordinator = RuntimeChoiceCoordinator(
            loadPersistedChoice = { stored },
            savePersistedChoice = { stored = it },
        )

        coordinator.onChoiceSelected("local:abc")

        assertEquals("local:abc", stored)
    }

    @Test
    fun anExplicitLocalChoiceAlsoBeatsTheProfileDerivedRuntime() = runTest {
        // The symmetric case of the remote one: selecting a local model explicitly must stick too,
        // or `selectLocalModel` would save a choice the restore path then throws away.
        val coordinator = RuntimeChoiceCoordinator(
            loadPersistedChoice = { "local:chosen" },
            savePersistedChoice = {},
        )

        val resolved = coordinator.resolveOnLaunch(
            profileRuntimeId = "local:abc",
            defaultRuntimeId = "local:first",
        )

        assertEquals("local:chosen", resolved)
    }

    @Test
    fun aLaunchRestoresThePersistedChoiceOverTheProfileDerivedOne() = runTest {
        val coordinator = RuntimeChoiceCoordinator(
            loadPersistedChoice = { "remote:mock" },
            savePersistedChoice = {},
        )

        val resolved = coordinator.resolveOnLaunch(
            profileRuntimeId = "local:abc",
            defaultRuntimeId = "local:first",
        )

        assertEquals("remote:mock", resolved)
    }

    @Test
    fun anInstallWithNoPersistedChoiceStillResolvesToItsProfile() = runTest {
        val coordinator = RuntimeChoiceCoordinator(
            loadPersistedChoice = { null },
            savePersistedChoice = {},
        )

        val resolved = coordinator.resolveOnLaunch(
            profileRuntimeId = "local:abc",
            defaultRuntimeId = "local:first",
        )

        assertEquals("local:abc", resolved)
    }
}
