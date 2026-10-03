package io.github.kurue.bram.app

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointHealthTest {

    private var now = 1_000_000L

    @After
    fun resetClock() {
        EndpointHealth.clock = System::currentTimeMillis
    }

    @Test
    fun `a fresh endpoint is reachable and a failed one is not`() {
        EndpointHealth.clock = { now }
        assertTrue(EndpointHealth.isReachable("e1"))

        EndpointHealth.markFailure("e1")
        assertFalse("a just-failed endpoint must not be chosen again", EndpointHealth.isReachable("e1"))
        assertTrue("another endpoint is unaffected", EndpointHealth.isReachable("e2"))
    }

    @Test
    fun `a success clears a failure immediately`() {
        EndpointHealth.clock = { now }
        EndpointHealth.markFailure("e1")
        EndpointHealth.markSuccess("e1")
        assertTrue(EndpointHealth.isReachable("e1"))
    }

    @Test
    fun `reachability returns after the cooldown`() {
        EndpointHealth.clock = { now }
        EndpointHealth.markFailure("e1")
        now += 61_000L
        assertTrue("the next session may try the server again", EndpointHealth.isReachable("e1"))
        EndpointHealth.markSuccess("e1")
    }

    @Test
    fun `clearing forgets every mark at once`() {
        // A device-suite case that fails a turn on purpose marks the shared endpoint down, and the
        // process is shared, so the next case inherits a verdict it never earned. Clearing has to
        // cover every endpoint, not just the one a caller remembers.
        EndpointHealth.clock = { now }
        EndpointHealth.markFailure("e1")
        EndpointHealth.markFailure("e2")
        EndpointHealth.clear()
        assertTrue(EndpointHealth.isReachable("e1"))
        assertTrue(EndpointHealth.isReachable("e2"))
        EndpointHealth.clear()
    }
}
