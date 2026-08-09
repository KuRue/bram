package io.github.kurue.bram.runtime.litertlm

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ToolCall as LiteRtToolCall
import io.github.kurue.bram.core.domain.ConversationMessage
import io.github.kurue.bram.core.domain.MessageRole
import io.github.kurue.bram.core.domain.SamplerSettings
import io.github.kurue.bram.core.domain.ToolCall
import io.github.kurue.bram.core.domain.ToolDefinition
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtLmRequestsTest {

    private fun user(text: String) = ConversationMessage(role = MessageRole.USER, content = text)

    private fun system(text: String) = ConversationMessage(role = MessageRole.SYSTEM, content = text)

    private fun assistant(text: String, calls: List<ToolCall> = emptyList()) =
        ConversationMessage(role = MessageRole.ASSISTANT, content = text, toolCalls = calls)

    private fun toolResult(callId: String, content: String) =
        ConversationMessage(role = MessageRole.TOOL, content = content, toolCallId = callId)

    @Test
    fun `leading system messages fold into the system instruction and leave the replay`() {
        val plan = LiteRtLmRequests.planConversation(
            listOf(
                system("You are Bram."),
                system("Use skills when they match."),
                user("Hello"),
            ),
        )
        assertNotNull(plan)
        val text = plan!!.systemInstruction.toString()
        assertTrue(text.contains("You are Bram."))
        assertTrue(text.contains("Use skills when they match."))
        // The single user message is the turn itself; nothing is left to replay behind it.
        assertEquals(0, plan.replay.size)
        assertEquals("Hello", plan.lastMessage.toString())
    }

    @Test
    fun `empty history has no plan`() {
        assertNull(LiteRtLmRequests.planConversation(emptyList()))
        assertNull(LiteRtLmRequests.planConversation(listOf(system("only a system message"))))
    }

    @Test
    fun `user and assistant turns map to the library roles`() {
        val plan = LiteRtLmRequests.planConversation(
            listOf(system("S"), user("Question"), assistant("Answer")),
        )
        assertEquals("Question", plan!!.replay[0].toString())
        assertEquals("Answer", plan.lastMessage.toString())
    }

    @Test
    fun `assistant tool call replays with its name and parsed arguments`() {
        val plan = LiteRtLmRequests.planConversation(
            listOf(
                system("S"),
                user("Write a note"),
                assistant(
                    "",
                    calls = listOf(ToolCall("call_1", "write_note", """{"name":"x","n":3}""")),
                ),
                toolResult("call_1", """{"ok":true}"""),
            ),
        )
        val callMessage = plan!!.replay[1]
        assertEquals(1, callMessage.toolCalls.size)
        val call = callMessage.toolCalls[0]
        assertEquals("write_note", call.name)
        assertEquals(3, call.arguments["n"])
        val resultMessage = plan.lastMessage
        assertEquals(1, resultMessage.contents.contents.size)
        val response = resultMessage.contents.contents[0] as Content.ToolResponse
        assertEquals("write_note", response.name)
        assertTrue(response.response is Map<*, *>)
    }

    @Test
    fun `a tool result without a matching call is dropped`() {
        val plan = LiteRtLmRequests.planConversation(
            listOf(user("Hi"), toolResult("unknown", "{}")),
        )
        // The orphaned result cannot name its tool and is dropped; the user message becomes the
        // turn itself, so nothing is left in the replay.
        assertEquals(0, plan!!.replay.size)
        assertEquals("Hi", plan.lastMessage.toString())
    }

    @Test
    fun `mid-conversation system message is demoted to a user message`() {
        val plan = LiteRtLmRequests.planConversation(
            listOf(system("S"), user("A"), system("late instruction"), user("B")),
        )
        assertEquals(2, plan!!.replay.size)
        assertEquals("A", plan.replay[0].toString())
        assertEquals("late instruction", plan.replay[1].toString())
    }

    @Test
    fun `sampler maps with clamped ranges`() {
        val config = LiteRtLmRequests.samplerConfig(
            SamplerSettings(temperature = 0.9f, topP = 0.95f, topK = 40),
        )
        assertEquals(40, config.topK)
        // The sampler carries Bram's floats, so the library sees the float widening, not an exact
        // 0.95; compare with float tolerance.
        assertEquals(0.95f, config.topP.toFloat(), 1e-6f)
        assertEquals(0.9f, config.temperature.toFloat(), 1e-6f)
        val clamped = LiteRtLmRequests.samplerConfig(
            SamplerSettings(temperature = -1f, topP = 2f, topK = 0),
        )
        assertEquals(1, clamped.topK)
        assertEquals(1.0, clamped.topP, 1e-9)
        assertEquals(0.0, clamped.temperature, 1e-9)
    }

    @Test
    fun `repetition penalty is only passed when at or above one`() {
        assertNotNull(LiteRtLmRequests.repetitionPenalty(SamplerSettings(repeatPenalty = 1.1f)))
        assertNull(LiteRtLmRequests.repetitionPenalty(SamplerSettings(repeatPenalty = 0.9f)))
        assertNull(LiteRtLmRequests.repetitionPenalty(SamplerSettings(repeatPenalty = 1.0f)))
    }

    @Test
    fun `tool providers carry the OpenAPI description with the parameters embedded`() {
        val tool = ToolDefinition(
            name = "write_note",
            description = "Writes a note",
            inputSchemaJson = """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
        )
        assertEquals(1, LiteRtLmRequests.toolProviders(listOf(tool)).size)
        val description = JSONObject(LiteRtLmRequests.toolDescriptionJson(tool))
        assertEquals("write_note", description.getString("name"))
        assertEquals("Writes a note", description.getString("description"))
        assertEquals("object", description.getJSONObject("parameters").getString("type"))
    }

    @Test
    fun `tool call arguments become a JSON string`() {
        val json = LiteRtLmRequests.toolCallArgumentsJson(
            LiteRtToolCall(
                "write_note",
                mapOf("name" to "x", "count" to 2, "tags" to listOf("a", "b"), "nested" to mapOf("k" to true)),
            ),
        )
        val parsed = JSONObject(json)
        assertEquals("x", parsed.getString("name"))
        assertEquals(2, parsed.getInt("count"))
        assertEquals(2, parsed.getJSONArray("tags").length())
        assertTrue(parsed.getJSONObject("nested").getBoolean("k"))
    }

    @Test
    fun `tool responses keep structure and plain text as plain text`() {
        val structured = LiteRtLmRequests.toolResponseValue("""{"ok":true,"n":7}""")
        assertTrue(structured is Map<*, *>)
        val list = LiteRtLmRequests.toolResponseValue("""[1,2,3]""")
        assertTrue(list is List<*>)
        val prose = LiteRtLmRequests.toolResponseValue("just a message")
        assertEquals("just a message", prose)
    }
}
