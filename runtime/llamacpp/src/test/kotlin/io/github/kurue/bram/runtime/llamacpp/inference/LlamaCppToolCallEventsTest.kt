package io.github.kurue.bram.runtime.llamacpp.inference

import io.github.kurue.bram.core.domain.GenerationEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaCppToolCallEventsTest {

    @Test
    fun `a named call keeps the id the chat format gave it`() {
        val events = parseToolCallEvents(
            JSONObject().put("type", "toolCalls").put(
                "calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "chatcmpl-tool-7")
                        .put("name", "device_status")
                        .put("arguments", """{"verbose":true}"""),
                ),
            ),
        )

        val call = (events.single() as GenerationEvent.ToolCallReady).call
        assertEquals("chatcmpl-tool-7", call.id)
        assertEquals("device_status", call.name)
        assertEquals("""{"verbose":true}""", call.argumentsJson)
    }

    @Test
    fun `calls the format left unnamed get distinct ids and default arguments`() {
        // Index-minted ids (`call_0`) repeat on every generation, which collides when the history
        // is replayed; each unnamed call needs an id of its own.
        val events = parseToolCallEvents(
            JSONObject().put("type", "toolCalls").put(
                "calls",
                JSONArray()
                    .put(JSONObject().put("name", "device_status"))
                    .put(JSONObject().put("name", "list_files")),
            ),
        )

        val calls = events.map { (it as GenerationEvent.ToolCallReady).call }
        assertTrue(calls.all { it.id.isNotBlank() })
        assertEquals(2, calls.map { it.id }.toSet().size)
        assertTrue(calls.all { it.argumentsJson == "{}" })
    }

    @Test
    fun `a recovered marker survives the mapping`() {
        val events = parseToolCallEvents(
            JSONObject().put("type", "toolCalls").put(
                "calls",
                JSONArray().put(JSONObject().put("name", "write_note").put("recovered", true)),
            ),
        )

        assertTrue((events.single() as GenerationEvent.ToolCallReady).call.recovered)
    }
}
