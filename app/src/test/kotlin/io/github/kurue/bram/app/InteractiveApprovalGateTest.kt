package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ToolApprovalDecision
import io.github.kurue.bram.core.domain.ToolDefinition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InteractiveApprovalGateTest {

    /** Stands in for the preferences-backed store, which needs a Context. */
    private class FakePermissions : ToolPermissions {
        val allowed = mutableSetOf<String>()
        override fun alwaysAllowed(): Set<String> = allowed
        override fun allowAlways(toolName: String) {
            allowed += toolName
        }

        override fun withdraw(toolName: String) {
            allowed -= toolName
        }
    }

    private fun tool(
        name: String = "send_message",
        readOnly: Boolean = false,
        permissions: Set<String> = emptySet(),
    ) = ToolDefinition(
        name = name,
        description = "does a thing",
        inputSchemaJson = "{}",
        readOnly = readOnly,
        requiredPermissions = permissions,
    )

    @Test
    fun `a read-only tool needing nothing runs without asking`() = runTest {
        // Approving every reading of the battery level teaches people to approve without reading.
        val gate = InteractiveApprovalGate(FakePermissions())
        val decision = gate.decide(tool(readOnly = true), "{}")
        assertEquals(ToolApprovalDecision.ALLOW_ONCE, decision)
        assertNull(gate.pending.value)
    }

    @Test
    fun `a read-only tool that still needs a permission is asked about`() = runTest {
        val gate = InteractiveApprovalGate(FakePermissions())
        val decision = async { gate.decide(tool(readOnly = true, permissions = setOf("contacts")), "{}") }
        yield()
        assertEquals("send_message", gate.pending.value?.toolName)
        gate.pending.value?.resolve(ToolApprovalDecision.DENY)
        assertEquals(ToolApprovalDecision.DENY, decision.await())
    }

    @Test
    fun `silence is refusal rather than a hang`() = runTest {
        // An unattended run has nobody to answer it. Waiting forever holds the foreground service
        // open with nothing happening.
        val gate = InteractiveApprovalGate(FakePermissions(), timeoutMillis = 50)
        assertEquals(ToolApprovalDecision.DENY, gate.decide(tool(), "{}"))
        assertNull(gate.pending.value)
    }

    @Test
    fun `always allow is remembered and skips the next ask`() = runTest {
        val permissions = FakePermissions()
        val gate = InteractiveApprovalGate(permissions)

        val first = async { gate.decide(tool(), "{}") }
        yield()
        gate.pending.value?.resolve(ToolApprovalDecision.ALLOW_ALWAYS)
        assertEquals(ToolApprovalDecision.ALLOW_ALWAYS, first.await())
        assertTrue("send_message" in permissions.allowed)

        assertEquals(ToolApprovalDecision.ALLOW_ONCE, gate.decide(tool(), "{}"))
        assertNull(gate.pending.value)
    }

    @Test
    fun `a refusal is not remembered`() = runTest {
        // "Not now" is about one call in one moment. Remembering it would quietly make a tool
        // permanently unusable with nothing on screen saying so.
        val permissions = FakePermissions()
        val gate = InteractiveApprovalGate(permissions)

        val first = async { gate.decide(tool(), "{}") }
        yield()
        gate.pending.value?.resolve(ToolApprovalDecision.DENY)
        first.await()

        val second = async { gate.decide(tool(), "{}") }
        yield()
        assertEquals("send_message", gate.pending.value?.toolName)
        gate.pending.value?.resolve(ToolApprovalDecision.ALLOW_ONCE)
        second.await()
        assertTrue(permissions.allowed.isEmpty())
    }

    @Test
    fun `the request carries what is being approved`() = runTest {
        val gate = InteractiveApprovalGate(FakePermissions())
        val decision = async { gate.decide(tool(permissions = setOf("sms")), """{"to":"+15551234"}""") }
        yield()

        val pending = requireNotNull(gate.pending.value)
        assertEquals("""{"to":"+15551234"}""", pending.argumentsJson)
        assertEquals(setOf("sms"), pending.requiredPermissions)
        gate.pending.value?.resolve(ToolApprovalDecision.DENY)
        decision.await()
    }
}
