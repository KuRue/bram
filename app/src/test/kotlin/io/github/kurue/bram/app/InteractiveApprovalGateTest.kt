package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.PermissionMode
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
    fun `bypass mode runs a side-effecting tool without asking`() = runTest {
        val gate = InteractiveApprovalGate(FakePermissions())
        gate.setMode(PermissionMode.BYPASS)
        val decision = gate.decide(tool(), "{}")
        assertEquals(ToolApprovalDecision.ALLOW_ONCE, decision)
        assertNull(gate.pending.value)
    }

    // Bypass and recovered calls. The exception used to be "recovered", full stop, which on a
    // format that never marks its calls meant every call was the exception and bypass did nothing.
    // What makes a recovered call dangerous is not the recovery, it is that the text may be
    // repeating something the model read.

    @Test
    fun `bypass runs a recovered call when nothing outside has been read`() = runTest {
        // With no fetched content in the conversation there is nothing for the model to be
        // echoing, so the call is its own intent, which is what bypass exists to stop asking about.
        val gate = InteractiveApprovalGate(FakePermissions())
        gate.setMode(PermissionMode.BYPASS)
        val decision = gate.decide(tool(), "{}", recovered = true, untrustedContext = false)
        assertEquals(ToolApprovalDecision.ALLOW_ONCE, decision)
        assertNull(gate.pending.value)
    }

    @Test
    fun `bypass asks about a recovered call once a page has been read`() = runTest {
        // The fence goes back up: taking responsibility for a run is not the same as vouching for
        // every page it reads.
        val gate = InteractiveApprovalGate(FakePermissions())
        gate.setMode(PermissionMode.BYPASS)
        val decision = async { gate.decide(tool(), "{}", recovered = true, untrustedContext = true) }
        yield()
        assertEquals("send_message", gate.pending.value?.toolName)
        gate.pending.value?.resolve(ToolApprovalDecision.DENY)
        assertEquals(ToolApprovalDecision.DENY, decision.await())
    }

    @Test
    fun `bypass runs a marked call even after a page has been read`() = runTest {
        // Outside content on its own changes nothing. It only matters for a call that was read out
        // of unmarked text, since that is the only path an echoed instruction could take.
        val gate = InteractiveApprovalGate(FakePermissions())
        gate.setMode(PermissionMode.BYPASS)
        val decision = gate.decide(tool(), "{}", recovered = false, untrustedContext = true)
        assertEquals(ToolApprovalDecision.ALLOW_ONCE, decision)
        assertNull(gate.pending.value)
    }

    @Test
    fun `outside content does not loosen the other modes`() = runTest {
        // The untrusted flag is a reason to ask, never a reason not to. In AUTO a recovered call is
        // asked about whatever the conversation contains.
        val gate = InteractiveApprovalGate(FakePermissions())
        val decision = async { gate.decide(tool(), "{}", recovered = true, untrustedContext = false) }
        yield()
        assertEquals("send_message", gate.pending.value?.toolName)
        gate.pending.value?.resolve(ToolApprovalDecision.DENY)
        assertEquals(ToolApprovalDecision.DENY, decision.await())
    }

    @Test
    fun `the card records why it is asking`() = runTest {
        // So the UI can say "this came from a page" rather than asking an unexplained question in
        // a mode the user set to stop being asked.
        val gate = InteractiveApprovalGate(FakePermissions())
        gate.setMode(PermissionMode.BYPASS)
        val decision = async { gate.decide(tool(), "{}", recovered = true, untrustedContext = true) }
        yield()
        val pending = requireNotNull(gate.pending.value)
        assertTrue(pending.recovered)
        assertTrue(pending.untrustedContext)
        pending.resolve(ToolApprovalDecision.DENY)
        decision.await()
    }

    @Test
    fun `manual mode asks about a read-only tool that auto would run silently`() = runTest {
        val gate = InteractiveApprovalGate(FakePermissions())
        gate.setMode(PermissionMode.MANUAL)
        val decision = async { gate.decide(tool(readOnly = true), "{}") }
        yield()
        // AUTO would have allowed this without prompting; MANUAL asks.
        assertEquals("send_message", gate.pending.value?.toolName)
        gate.pending.value?.resolve(ToolApprovalDecision.DENY)
        assertEquals(ToolApprovalDecision.DENY, decision.await())
    }

    @Test
    fun `an always-allowed tool still runs in manual mode`() = runTest {
        // An explicit "allow always" grant is stronger than the mode, so MANUAL does not re-ask a
        // tool the user has already trusted for good.
        val permissions = FakePermissions()
        permissions.allowAlways("send_message")
        val gate = InteractiveApprovalGate(permissions)
        gate.setMode(PermissionMode.MANUAL)
        assertEquals(ToolApprovalDecision.ALLOW_ONCE, gate.decide(tool(), "{}"))
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
    fun `an unattended run refuses a tool that needs asking instead of waiting`() = runTest {
        // A backgrounded or scheduled run cannot reach the approval card, so it must not hold the
        // run for the timeout. The long timeout is deliberate: if the gate waited, the card would
        // be published and this test would fail on the pending assertion.
        val gate = InteractiveApprovalGate(FakePermissions(), timeoutMillis = 60_000)
        gate.setAttended(false)
        val decision = async { gate.decide(tool(), "{}") }
        yield()
        assertNull("no card is published when nobody can answer it", gate.pending.value)
        assertEquals(ToolApprovalDecision.DENY, decision.await())
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

    // An allowance is granted for a tool, not for one set of arguments. Scoping to the target was
    // safer and unusable: re-approving every URL made the grant worthless, so people either stopped
    // granting or stopped reading. Revocation is the control that keeps this honest — see the
    // always-allowed list in Settings.

    @Test
    fun `an allowance covers later calls to the same tool`() = runTest {
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
    fun `an allowance covers a different target for the same tool`() = runTest {
        // Deliberate: "you may fetch" rather than "you may fetch this one address". The narrower
        // grant is recorded in the git history if it needs to come back.
        val permissions = FakePermissions()
        val gate = InteractiveApprovalGate(permissions)
        val runner = tool(name = "run_command", scopeKeys = listOf("command"))

        val first = async { gate.decide(runner, """{"command":"git status"}""") }
        yield()
        gate.pending.value?.resolve(ToolApprovalDecision.ALLOW_ALWAYS)
        first.await()

        assertEquals(
            ToolApprovalDecision.ALLOW_ONCE,
            gate.decide(runner, """{"command":"git log"}"""),
        )
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
        assertEquals("every use of run_command", label)
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
