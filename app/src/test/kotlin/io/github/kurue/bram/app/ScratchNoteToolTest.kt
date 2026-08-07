package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tool itself needs a Context, so what is pinned here is the part that has to be right
 * regardless of it: that the tool declares effects, so the gate asks before it runs.
 */
class ScratchNoteToolTest {

    private val definition = ToolDefinition(
        name = "write_note",
        description = "Write a short note to Bram's private storage under a given name.",
        inputSchemaJson = "{}",
        readOnly = false,
        requiredPermissions = setOf("private_storage"),
        approvalScopeKeys = listOf("name"),
    )

    @Test
    fun `the note tool is asked about rather than taking the read-only fast path`() {
        // If either of these drifted back to a read-only default, the gate would stop asking and
        // the tool would write without anyone seeing a card.
        assertFalse(definition.readOnly)
        assertTrue(definition.requiredPermissions.isNotEmpty())
    }

    @Test
    fun `an allowance is scoped to the note being written`() {
        val shopping = InteractiveApprovalGate.approvalScope(definition, """{"name":"shopping"}""")
        val secrets = InteractiveApprovalGate.approvalScope(definition, """{"name":"secrets"}""")
        assertTrue(shopping != secrets)
        assertEquals("write_note with name = shopping", InteractiveApprovalGate.scopeLabel(definition, """{"name":"shopping"}"""))
    }
}
