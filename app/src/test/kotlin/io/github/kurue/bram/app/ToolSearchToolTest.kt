package io.github.kurue.bram.app

import io.github.kurue.bram.core.agent.StaticToolRegistry
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSearchToolTest {

    private class NamedHandler(name: String, description: String) : ToolHandler {
        override val definition = ToolDefinition(name = name, description = description, inputSchemaJson = """{"type":"object"}""")
        override suspend fun execute(argumentsJson: String): String = "{}"
    }

    private val registry = StaticToolRegistry(
        listOf(
            NamedHandler("web_fetch", "Fetch a page from the web and return its text."),
            NamedHandler("termux_exec", "Run a shell command and return exit code and output."),
            NamedHandler("device_status", "Report battery, storage, and thermal state."),
            NamedHandler("mcp_crm_find_customer", "Find a customer in the CRM by name or email."),
            NamedHandler("tool_search", "Find tools by what they do."),
        ),
    )

    private val tool = ToolSearchTool({ registry })

    @Test
    fun `a query finds the tools whose name or description matches`() = runBlocking {
        val result = JSONObject(tool.execute("""{"query":"customer email"}"""))
        assertEquals(1, result.getInt("count"))
        assertEquals("mcp_crm_find_customer", result.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertTrue(result.getString("note").contains("Call a tool by its name"))
    }

    @Test
    fun `a name match outranks a description match`() = runBlocking {
        val result = JSONObject(tool.execute("""{"query":"fetch web"}"""))
        val first = result.getJSONArray("tools").getJSONObject(0)
        assertEquals("web_fetch", first.getString("name"))
    }

    @Test
    fun `the result carries the schema so the call can be made correctly`() = runBlocking {
        val result = JSONObject(tool.execute("""{"query":"shell command"}"""))
        val match = result.getJSONArray("tools").getJSONObject(0)
        assertEquals("termux_exec", match.getString("name"))
        assertTrue(match.has("schema"))
    }

    @Test
    fun `a query that matches nothing says so instead of guessing`() = runBlocking {
        val result = JSONObject(tool.execute("""{"query":"quantum teleportation"}"""))
        assertEquals(0, result.getInt("count"))
        assertTrue(result.getString("note").contains("No tool matched"))
    }

    @Test
    fun `searching for a tool never returns itself`() = runBlocking {
        val result = JSONObject(tool.execute("""{"query":"tool search find"}"""))
        val names = (0 until result.getJSONArray("tools").length())
            .map { result.getJSONArray("tools").getJSONObject(it).getString("name") }
        assertTrue("tool_search must not list itself", "tool_search" !in names)
    }
}
