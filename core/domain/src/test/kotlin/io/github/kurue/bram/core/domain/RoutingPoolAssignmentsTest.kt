package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingPoolAssignmentsTest {
    @Test
    fun `assigning a target moves it between roles`() {
        val original = RoutingPoolAssignments(primaryTargetId = "local:small")

        val moved = original.assign(RoutingPoolSlot.POWER, "local:small")

        assertNull(moved.primaryTargetId)
        assertEquals("local:small", moved.powerTargetId)
    }

    @Test
    fun `blank assignment clears the requested role`() {
        val original = RoutingPoolAssignments(primaryTargetId = "local:small")

        val cleared = original.assign(RoutingPoolSlot.PRIMARY, "  ")

        assertNull(cleared.primaryTargetId)
    }

    @Test
    fun `retain available removes deleted targets`() {
        val original = RoutingPoolAssignments(
            primaryTargetId = "local:small",
            powerTargetId = "local:large",
            remoteOffloadTargetId = "remote:cloud",
        )

        val retained = original.retainAvailable(setOf("local:small", "remote:cloud"))

        assertEquals("local:small", retained.primaryTargetId)
        assertNull(retained.powerTargetId)
        assertEquals("remote:cloud", retained.remoteOffloadTargetId)
    }
}
