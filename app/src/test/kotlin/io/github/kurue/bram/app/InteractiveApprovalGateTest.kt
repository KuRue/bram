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
        scopeKeys: List<String> = emptyList(),
    ) = ToolDefinition(
        name = name,
        description = "does a thing",
        inputSchemaJson = "{}",
        readOnly = readOnly,
        requiredPermissions = permissions,
        approvalScopeKeys = scopeKeys,
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

    // A permission granted for good has to be granted for something. "Always allow run_command" is
    // a blanket grant; "always allow run_command with command = git status" is a real decision.

    @Test
    fun `an allowance covers the target it was granted for`() = runTest {
        val permissions = FakePermissions()
        val gate = InteractiveApprovalGate(permissions)
        val runner = tool(name = "run_command", scopeKeys = listOf("command"))

        val first = async { gate.decide(runner, """{"command":"git status"}""") }
        yield()
        gate.pending.value?.resolve(ToolApprovalDecision.ALLOW_ALWAYS)
        first.await()

        // The same command again goes through.
        assertEquals(
            ToolApprovalDecision.ALLOW_ONCE,
            gate.decide(runner, """{"command":"git status"}"""),
        )
    }

    @Test
    fun `an allowance does not cover a different target`() = runTest {
        val permissions = FakePermissions()
        val gate = InteractiveApprovalGate(permissions)
        val runner = tool(name = "run_command", scopeKeys = listOf("command"))

        val first = async { gate.decide(runner, """{"command":"git status"}""") }
        yield()
        gate.pending.value?.resolve(ToolApprovalDecision.ALLOW_ALWAYS)
        first.await()

        // Allowing one command must not allow every command.
        val second = async { gate.decide(runner, """{"command":"rm -rf /"}""") }
        yield()
        assertEquals("run_command", gate.pending.value?.toolName)
        gate.pending.value?.resolve(ToolApprovalDecision.DENY)
        assertEquals(ToolApprovalDecision.DENY, second.await())
    }

    @Test
    fun `a missing target is not the same as any target`() = runTest {
        // Otherwise an allowance granted for a call that omitted the field would silently cover a
        // later call that supplies one.
        val withTarget = InteractiveApprovalGate.approvalScope(
            tool(name = "write_file", scopeKeys = listOf("path")),
            """{"path":"/tmp/x"}""",
        )
        val withoutTarget = InteractiveApprovalGate.approvalScope(
            tool(name = "write_file", scopeKeys = listOf("path")),
            "{}",
        )
        assertTrue(withTarget != withoutTarget)
    }

    @Test
    fun `a tool with no target scopes to the tool itself`() = runTest {
        assertEquals(
            "read_clock",
            InteractiveApprovalGate.approvalScope(tool(name = "read_clock"), """{"zone":"UTC"}"""),
        )
    }

    @Test
    fun `the label says what always would grant`() {
        val label = InteractiveApprovalGate.scopeLabel(
            tool(name = "run_command", scopeKeys = listOf("command")),
            """{"command":"git status"}""",
        )
        assertEquals("run_command with command = git status", label)
    }

    // A recovered call is text read as an intent, and text can be echoed from anywhere the model
    // has read — including a tool's own output. It is always asked about.

    @Test
    fun `a recovered call is asked about even when the tool is always allowed`() = runTest {
        val permissions = FakePermissions()
        val gate = InteractiveApprovalGate(permissions)

        val first = async { gate.decide(tool(), "{}") }
        yield()
        gate.pending.value?.resolve(ToolApprovalDecision.ALLOW_ALWAYS)
        first.await()
        assertEquals(ToolApprovalDecision.ALLOW_ONCE, gate.decide(tool(), "{}"))

        val recovered = async { gate.decide(tool(), "{}", recovered = true) }
        yield()
        assertEquals("send_message", gate.pending.value?.toolName)
        gate.pending.value?.resolve(ToolApprovalDecision.DENY)
        assertEquals(ToolApprovalDecision.DENY, recovered.await())
    }

    @Test
    fun `a recovered call is asked about even when the tool only reads`() = runTest {
        val gate = InteractiveApprovalGate(FakePermissions())
        val decision = async { gate.decide(tool(readOnly = true), "{}", recovered = true) }
        yield()
        assertEquals("send_message", gate.pending.value?.toolName)
        gate.pending.value?.resolve(ToolApprovalDecision.DENY)
        assertEquals(ToolApprovalDecision.DENY, decision.await())
    }

    @Test
    fun `allowing a recovered call always is not remembered`() = runTest {
        val permissions = FakePermissions()
        val gate = InteractiveApprovalGate(permissions)
        val decision = async { gate.decide(tool(), "{}", recovered = true) }
        yield()
        gate.pending.value?.resolve(ToolApprovalDecision.ALLOW_ALWAYS)
        decision.await()
        assertTrue(permissions.allowed.isEmpty())
    }
}
