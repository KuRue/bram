package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ToolApprovalDecision
import io.github.kurue.bram.core.domain.ToolDefinition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionAwareApprovalGateTest {

    private val tool = ToolDefinition(
        name = "read_contacts",
        description = "reads the address book",
        inputSchemaJson = "{}",
        readOnly = true,
        requiredPermissions = setOf(RuntimePermissions.TOKEN_CONTACTS),
    )

    /** A store that already trusts the tool, so the delegate allows without showing a card. */
    private fun trustingStore() = object : ToolPermissions {
        override fun alwaysAllowed(): Set<String> = setOf("read_contacts")
        override fun allowAlways(toolName: String) {}
        override fun withdraw(toolName: String) {}
    }

    private fun emptyStore() = object : ToolPermissions {
        override fun alwaysAllowed(): Set<String> = emptySet()
        override fun allowAlways(toolName: String) {}
        override fun withdraw(toolName: String) {}
    }

    @Test
    fun `a granted permission keeps the allowance`() = runTest {
        val gate = PermissionAwareApprovalGate(
            InteractiveApprovalGate(trustingStore()),
            RuntimePermissionAsker { _, _ -> emptySet() },
        )
        assertEquals(ToolApprovalDecision.ALLOW_ONCE, gate.decide(tool, "{}"))
    }

    @Test
    fun `a refused permission is an OS denial, not a user refusal`() = runTest {
        // Nobody declined the call; Android declined a permission. The model must be told the
        // difference, because the fix is a system setting it cannot change and retrying is useless.
        val gate = PermissionAwareApprovalGate(
            InteractiveApprovalGate(trustingStore()),
            RuntimePermissionAsker { tokens, _ -> tokens },
        )
        assertEquals(ToolApprovalDecision.DENY_OS_PERMISSION, gate.decide(tool, "{}"))
    }

    @Test
    fun `the gate's own refusal never reaches the permission ask`() = runTest {
        var asked = false
        val delegate = InteractiveApprovalGate(emptyStore()).apply {
            // Nobody is at the card and no notification can be posted, so the gate refuses at once
            // rather than holding the run.
            setAttended(false)
        }
        val gate = PermissionAwareApprovalGate(
            delegate,
            RuntimePermissionAsker { _, _ ->
                asked = true
                emptySet()
            },
        )
        assertEquals(ToolApprovalDecision.DENY_UNATTENDED, gate.decide(tool, "{}"))
        assertEquals("a refused call must not ask for permissions", false, asked)
    }
}
