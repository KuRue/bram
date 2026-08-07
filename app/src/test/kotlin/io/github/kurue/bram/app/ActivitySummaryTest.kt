package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.AgentActivity
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivitySummaryTest {

    private fun thought(text: String = "", ms: Long = 0, inProgress: Boolean = false) =
        AgentActivity.Thinking(text, ms, inProgress)

    private fun tool(name: String, result: String? = "{}", failed: Boolean = false) =
        AgentActivity.ToolInvocation(id = name, name = name, argumentsJson = "{}", result = result, failed = failed)

    @Test
    fun `a single finished thought reports its duration`() {
        assertEquals("Thought 8s", summariseActivity(listOf(thought(ms = 8_000))))
    }

    @Test
    fun `an open thought shows it is still going`() {
        assertEquals("Thought…", summariseActivity(listOf(thought(inProgress = true))))
    }

    @Test
    fun `several thoughts total their time and count`() {
        val activity = listOf(thought(ms = 3_000), thought(ms = 5_000))
        assertEquals("Thought 8s (2x)", summariseActivity(activity))
    }

    @Test
    fun `a single tool is singular`() {
        assertEquals("1 tool", summariseActivity(listOf(tool("web_search"))))
    }

    @Test
    fun `in-flight tools are marked`() {
        val activity = listOf(tool("web_search", result = null), tool("web_fetch", result = "{}"), tool("write_note", result = null))
        assertEquals("3 tools…", summariseActivity(activity))
    }

    @Test
    fun `failed tools are counted`() {
        val activity = listOf(tool("web_search"), tool("web_fetch", result = "err", failed = true))
        assertEquals("2 tools, 1 failed", summariseActivity(activity))
    }

    @Test
    fun `thinking and tools are joined`() {
        val activity = listOf(thought(ms = 8_000), tool("web_search"), tool("web_fetch"))
        assertEquals("Thought 8s · 2 tools", summariseActivity(activity))
    }

    @Test
    fun `an empty activity list is empty`() {
        assertEquals("", summariseActivity(emptyList()))
    }
}
