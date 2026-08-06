package io.github.kurue.bram.runtime.llamacpp.inference

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fences matter more than the parsing here: this turns text into an action, so what it
 * *refuses* is the part worth pinning.
 */
class BareToolCallTest {

    private val offered = setOf("write_note", "device_status")

    @Test
    fun `recovers the call a model writes without the marker`() {
        val call = BareToolCall.recover("[write_note(name='x', body='y')]", offered)
        assertEquals("write_note", call?.optString("name"))
        val arguments = JSONObject(call?.optString("arguments").orEmpty())
        assertEquals("x", arguments.optString("name"))
        assertEquals("y", arguments.optString("body"))
    }

    @Test
    fun `recovers without the surrounding brackets`() {
        assertEquals("write_note", BareToolCall.recover("write_note(name='x')", offered)?.optString("name"))
    }

    @Test
    fun `refuses a tool that was not offered`() {
        // Otherwise any identifier followed by a bracket becomes a callable name.
        assertNull(BareToolCall.recover("[delete_everything(path='/')]", offered))
    }

    @Test
    fun `refuses a call mentioned in the middle of prose`() {
        // A model explaining itself, or quoting a user, must not fire a tool.
        assertNull(
            BareToolCall.recover(
                "You could use [write_note(name='x', body='y')] to do that.",
                offered,
            ),
        )
    }

    @Test
    fun `refuses text that merely names a tool`() {
        assertNull(BareToolCall.recover("I would use write_note for this.", offered))
    }

    @Test
    fun `refuses arguments that are not plain literals`() {
        // A nested call is a sign this is prose about calls rather than a call.
        assertNull(BareToolCall.recover("[write_note(name=device_status())]", offered))
    }

    @Test
    fun `refuses an unterminated string`() {
        assertNull(BareToolCall.recover("[write_note(name='x)]", offered))
    }

    @Test
    fun `refuses everything when no tools were offered`() {
        assertNull(BareToolCall.recover("[write_note(name='x')]", emptySet()))
    }

    @Test
    fun `keeps a comma inside a quoted value`() {
        val call = BareToolCall.recover("""[write_note(name='x', body='milk, eggs')]""", offered)
        val arguments = JSONObject(call?.optString("arguments").orEmpty())
        assertEquals("milk, eggs", arguments.optString("body"))
    }

    @Test
    fun `reads numbers and booleans as themselves`() {
        val call = BareToolCall.recover("[write_note(name='x', count=3, force=true)]", offered)
        val arguments = JSONObject(call?.optString("arguments").orEmpty())
        assertEquals(3L, arguments.optLong("count"))
        assertEquals(true, arguments.optBoolean("force"))
    }
}
