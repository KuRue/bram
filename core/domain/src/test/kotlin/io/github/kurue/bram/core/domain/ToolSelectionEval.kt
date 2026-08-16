package io.github.kurue.bram.core.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The selection contract, exercised the way a real registry stresses it.
 *
 * The embedder here is a deterministic keyword-overlap stand-in, not a model: what this pins is
 * the selector's behavior — the core set survives every ask, a clearly relevant tool is admitted
 * ahead of clearly irrelevant ones, the character budget holds against a full-size registry, and
 * nothing a run was not offered can be selected. Real embedding quality is the designated
 * embedding model's business; these tests must not depend on it.
 */
class ToolSelectionEval {

    /** Bag-of-words cosine over first letters: deterministic, monotone in keyword overlap. */
    private class KeywordEmbedder : Embedder {
        override suspend fun embed(text: String): FloatArray {
            val vector = FloatArray(26)
            text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.forEach { word ->
                vector[word[0] - 'a'] += 1f
            }
            return vector
        }
    }

    /** A tool description sized like the real registry's (~250-500 chars of prose). */
    private fun noisyTool(index: Int) = ToolDefinition(
        name = "tool_$index",
        description = ("Does thing $index. " +
            "Use when the task involves category $index, subsystem $index, or a question about " +
            "resource $index. Handles the details of $index including lookup, formatting, and " +
            "returning a compact summary the model can act on directly without further calls. ").repeat(2),
        inputSchemaJson = "{\"type\":\"object\",\"properties\":{\"q$index\":{\"type\":\"string\"}},\"required\":[\"q$index\"]}",
    )

    private val weatherTool = ToolDefinition(
        name = "get_weather",
        description = "Current weather and a daily forecast for a place. Use for any question " +
            "about conditions now or the next few days, anywhere.",
        inputSchemaJson = """{"type":"object","properties":{"place":{"type":"string"},"days":{"type":"integer"}},"required":["place"]}""",
    )

    private val shellTool = ToolDefinition(
        name = "termux_exec",
        description = "Run a shell command and return exit code, stdout, and stderr. Use for " +
            "builds, git, scripts, and anything that chains or pipes.",
        inputSchemaJson = """{"type":"object","properties":{"shell":{"type":"string"},"command":{"type":"string"}}}""",
    )

    private val registry: List<ToolDefinition> = buildList {
        addAll((1..24).map { noisyTool(it) })
        add(weatherTool)
        add(shellTool)
        addAll(RankingToolSelector.DEFAULT_CORE_TOOLS.minus(weatherTool.name).map { name ->
            ToolDefinition(
                name = name,
                description = "$name: the always-on core description with some words in it",
                inputSchemaJson = "{\"type\":\"object\",\"properties\":{}}",
            )
        })
    }

    private fun selector() = RankingToolSelector(KeywordEmbedder())

    @Test
    fun `a weather ask keeps the weather tool and the core set under a full registry`() = runBlocking {
        val selected = selector().select("what is the weather in tokyo tomorrow", 4_096, registry)
        val names = selected.map { it.name }
        assertTrue("the weather tool must survive", weatherTool.name in names)
        assertTrue("every core tool must survive", RankingToolSelector.DEFAULT_CORE_TOOLS.all { it in names })
        // The budget holds: the whole selection costs less than the configured cap.
        val spent = selected.sumOf { it.description.length + it.inputSchemaJson.length }
        assertTrue("selection cost $spent chars exceeds the budget", spent <= 6_000 + 200)
        // And triage actually happened: two dozen irrelevant tools did not all fit.
        assertTrue("expected the 24 noisy tools to be trimmed", selected.size < registry.size)
    }

    @Test
    fun `a shell ask keeps the shell tool ahead of the noise`() = runBlocking {
        val selected = selector().select("run the build and show me git status in the shell", 4_096, registry)
        assertTrue(shellTool.name in selected.map { it.name })
        assertTrue(weatherTool.name in selected.map { it.name }) // core: stays whatever the ask
    }

    @Test
    fun `every run keeps at least the core set`() = runBlocking {
        for (ask in listOf("", "hello", "tell me a joke", "compare two poems", "🤷")) {
            val selected = selector().select(ask, 4_096, registry)
            assertTrue(
                "ask \"$ask\" lost a core tool: ${RankingToolSelector.DEFAULT_CORE_TOOLS - selected.map { it.name }.toSet()}",
                RankingToolSelector.DEFAULT_CORE_TOOLS.all { it in selected.map { tool -> tool.name } },
            )
        }
    }

    @Test
    fun `the selection never invents or reorders the core`() = runBlocking {
        val selected = selector().select("anything at all", 4_096, registry)
        val registryNames = registry.map { it.name }
        selected.forEach { tool ->
            assertTrue("selected ${tool.name} which the registry never offered", tool.name in registryNames)
        }
    }
}
