package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.AgentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript is the product, so the order of its rows is pinned: a turn that reasoned, called
 * a tool, and then reasoned again must persist pre-call reasoning, the call, post-call reasoning —
 * not the post-call reasoning pulled to the front.
 */
class SettleActivitiesTest {

    private fun thought(text: String, ms: Long = 0) = AgentActivity.Thinking(text, ms, inProgress = false)

    private fun tool(name: String) = AgentActivity.ToolInvocation(id = name, name = name, argumentsJson = "{}")

    @Test
    fun `a remote tool turn keeps thinking, call, thinking in that order`() {
        val rows = settleActivities(
            activity = listOf(thought("weighing the request", ms = 28_877), tool("get_weather")),
            trailingReasoning = thought("writing the answer", ms = 5_969),
            parsedReasoning = null,
        )
        assertEquals(
            listOf("weighing the request", "get_weather", "writing the answer"),
            rows.map { row -> if (row is AgentActivity.Thinking) row.text else (row as AgentActivity.ToolInvocation).name },
        )
    }

    @Test
    fun `several tool rounds keep every row after the one before it`() {
        val rows = settleActivities(
            activity = listOf(
                thought("first"),
                tool("read_screen"),
                tool("tap"),
                thought("between calls"),
                tool("read_screen"),
            ),
            trailingReasoning = thought("final"),
            parsedReasoning = null,
        )
        assertEquals(
            listOf("first", "read_screen", "tap", "between calls", "read_screen", "final"),
            rows.map { row -> if (row is AgentActivity.Thinking) row.text else (row as AgentActivity.ToolInvocation).name },
        )
    }

    @Test
    fun `a turn with no tool appends its only reasoning`() {
        val rows = settleActivities(
            activity = emptyList(),
            trailingReasoning = thought("the whole turn", ms = 5_000),
            parsedReasoning = null,
        )
        assertEquals(listOf("the whole turn"), rows.map { (it as AgentActivity.Thinking).text })
    }

    @Test
    fun `the parsed fallback is used only when the stream recorded no thinking`() {
        val fromStream = settleActivities(
            activity = listOf(thought("streamed"), tool("read_screen")),
            trailingReasoning = null,
            parsedReasoning = thought("parsed from the finished text"),
        )
        assertEquals(1, fromStream.filterIsInstance<AgentActivity.Thinking>().size)
        assertEquals("streamed", (fromStream.first() as AgentActivity.Thinking).text)

        val fallback = settleActivities(
            activity = emptyList(),
            trailingReasoning = null,
            parsedReasoning = thought("parsed from the finished text", ms = 7_000),
        )
        assertEquals("parsed from the finished text", (fallback.single() as AgentActivity.Thinking).text)
        assertEquals(7_000, (fallback.single() as AgentActivity.Thinking).durationMillis)
    }

    @Test
    fun `the trailing row keeps its own duration`() {
        val rows = settleActivities(
            activity = listOf(thought("pre", ms = 1_000), tool("tap")),
            trailingReasoning = thought("post", ms = 2_500),
            parsedReasoning = null,
        )
        assertTrue(rows.last() is AgentActivity.Thinking)
        assertEquals(2_500, (rows.last() as AgentActivity.Thinking).durationMillis)
    }
}
