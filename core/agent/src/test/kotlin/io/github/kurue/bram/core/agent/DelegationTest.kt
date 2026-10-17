package io.github.kurue.bram.core.agent

import io.github.kurue.bram.core.domain.AgentCatalog
import io.github.kurue.bram.core.domain.ToolDefinition
import io.github.kurue.bram.core.domain.ToolHandler
import io.github.kurue.bram.core.domain.ToolRegistry

import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Handler(
    name: String,
    readOnly: Boolean = true,
) : ToolHandler {
    override val definition = ToolDefinition(
        name = name,
        description = name,
        inputSchemaJson = "{}",
        readOnly = readOnly,
    )

    override suspend fun execute(argumentsJson: String): String = "{}"
}

private class FakeRegistry(handlers: List<ToolHandler>) : ToolRegistry {
    private val map = handlers.associateBy { it.definition.name }
    override fun definitions(): List<ToolDefinition> = handlers().map { it.definition }
    override fun find(name: String): ToolHandler? = map[name]
    private fun handlers() = map.values.toList()
}

class DelegationTest {
    private val parentTools = FakeRegistry(
        listOf(
            Handler("web_search"),
            Handler("file_read"),
            Handler("clipboard_set", readOnly = false),
            DelegateTool(executor = FakeExecutor(), rootRunIdProvider = { "root" }),
        ),
    )

    // --- policy ---

    @Test
    fun `a child sees only read-only allowed tools and never the delegate tool`() {
        val handlers = DelegationPolicy.childHandlers(parentTools, AgentCatalog.RESEARCH)

        val names = handlers.map { it.definition.name }
        assertFalse("one nesting level: a child must not be able to spawn children", "delegate" in names)
        assertFalse("a child can never be armed, whatever the definition says", "clipboard_set" in names)
        assertTrue("web_search" in names)
        assertTrue("file_read" in names)
    }

    @Test
    fun `an empty allowlist means every read-only tool the parent had`() {
        val definition = AgentCatalog.RESEARCH.copy(toolAllowlist = emptySet())

        val names = DelegationPolicy.childHandlers(parentTools, definition).map { it.definition.name }

        assertTrue("web_search" in names)
        assertTrue("file_read" in names)
        assertFalse("clipboard_set" in names)
        assertFalse("delegate" in names)
    }

    @Test
    fun `an allowlist narrows but cannot re-arm a side-effecting tool`() {
        val definition = AgentCatalog.RESEARCH.copy(toolAllowlist = setOf("clipboard_set"))

        val names = DelegationPolicy.childHandlers(parentTools, definition).map { it.definition.name }

        assertTrue(names.isEmpty())
    }

    // --- tool ---

    @Test
    fun `an unknown agent is a structured failure, not a crash`() = runBlocking {
        val tool = DelegateTool(FakeExecutor()) { "root" }

        val result = JSONObject(tool.execute("""{"agent":"butler","task":"x"}"""))

        assertEquals("failed", result.getString("status"))
        assertTrue(result.getString("reason").contains("unknown agent"))
    }

    @Test
    fun `the default agent cannot be delegated to`() = runBlocking {
        val tool = DelegateTool(FakeExecutor()) { "root" }

        val result = JSONObject(tool.execute("""{"agent":"bram","task":"x"}"""))

        assertEquals("failed", result.getString("status"))
    }

    @Test
    fun `an empty task is refused`() = runBlocking {
        val tool = DelegateTool(FakeExecutor()) { "root" }

        val result = JSONObject(tool.execute("""{"agent":"research","task":"   "}"""))

        assertEquals("failed", result.getString("status"))
    }

    @Test
    fun `a successful child returns its bounded summary and transcript link`() = runBlocking {
        val executor = FakeExecutor(
            result = ChildRunResult(
                succeeded = true,
                summary = "The answer is 42.",
                conversationId = "agent-research-1",
                usage = io.github.kurue.bram.core.domain.TokenUsage(outputTokens = 200),
            ),
        )
        var seenRoot: String? = null
        val tool = DelegateTool(executor) { seenRoot ?: "root" }

        val result = JSONObject(
            tool.execute("""{"agent":"research","task":"find the answer","context":"see note 3"}"""),
        )

        assertEquals("completed", result.getString("status"))
        assertEquals("The answer is 42.", result.getString("summary"))
        assertEquals("agent-research-1", result.getString("transcript"))
        assertEquals(200, result.getInt("tokensUsed"))
        assertEquals("find the answer", executor.lastRequest?.task)
        assertEquals("see note 3", executor.lastRequest?.contextNotes)
        assertEquals("research", executor.lastRequest?.definition?.id)
        assertEquals("root", executor.lastRequest?.parentRootRunId)
    }

    @Test
    fun `a failed child is a structured failure the parent can report`() = runBlocking {
        val executor = FakeExecutor(
            result = ChildRunResult(
                succeeded = false,
                summary = "",
                conversationId = "agent-research-2",
                failureReason = "the model produced no reply",
            ),
        )
        val tool = DelegateTool(executor) { "root" }

        val result = JSONObject(tool.execute("""{"agent":"research","task":"x"}"""))

        assertEquals("failed", result.getString("status"))
        assertTrue(result.getString("reason").contains("no reply"))
    }
}

private class FakeExecutor(
    private val result: ChildRunResult = ChildRunResult(succeeded = true, summary = "ok", conversationId = "c"),
) : ChildRunExecutor {
    var lastRequest: ChildRunRequest? = null
    override suspend fun runChild(request: ChildRunRequest): ChildRunResult {
        lastRequest = request
        return result
    }
}
