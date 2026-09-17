package io.github.kurue.bram.core.domain

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SkillAwareToolSelectorTest {

    private fun tool(name: String) = ToolDefinition(name = name, description = "$name tool", inputSchemaJson = "{}")

    private class FixedSelector(private val selected: List<ToolDefinition>) : ToolSelector {
        override suspend fun select(
            query: String,
            contextWindowTokens: Int,
            available: List<ToolDefinition>,
        ): List<ToolDefinition> = selected
    }

    private class FakeSkillStore(private val active: List<ActiveSkill>) : SkillStore {
        override suspend fun packages(): List<SkillPackage> = emptyList()
        override suspend fun activeSkills(): List<ActiveSkill> = active
        override suspend fun importDocument(document: String): SkillImportOutcome =
            SkillImportOutcome.Rejected("not used")
        override suspend fun proposeDraft(document: String): SkillImportOutcome =
            SkillImportOutcome.Rejected("not used")
        override suspend fun activateDraft(skillId: String): SkillActionOutcome = SkillActionOutcome.Failed("not used")
        override suspend fun rollback(skillId: String): SkillActionOutcome = SkillActionOutcome.Failed("not used")
        override suspend fun disable(skillId: String): SkillActionOutcome = SkillActionOutcome.Failed("not used")
        override suspend fun enable(skillId: String): SkillActionOutcome = SkillActionOutcome.Failed("not used")
        override suspend fun remove(skillId: String): SkillActionOutcome = SkillActionOutcome.Failed("not used")
    }

    private fun activeSkill(tools: Set<String>) = ActiveSkill(
        id = "weather-scout",
        name = "Weather Scout",
        version = "1.0.0",
        description = "Check conditions before planning outdoors",
        instructions = "Report the weather.",
        tools = tools,
    )

    @Test
    fun `a tool an active skill declares is offered even when ranking dropped it`() = runBlocking {
        // The skill's steps call get_weather; following it with the tool hidden would strand the
        // model mid-procedure, so the declaration puts the tool back into the offer.
        val available = listOf(tool("read_skill"), tool("get_weather"))
        val selector = SkillAwareToolSelector(
            delegate = FixedSelector(listOf(tool("read_skill"))),
            skillStore = FakeSkillStore(listOf(activeSkill(tools = setOf("get_weather")))),
        )

        assertEquals(listOf("read_skill", "get_weather"), selector.select("q", 4_096, available).map { it.name })
    }

    @Test
    fun `a declared tool that is not installed changes nothing`() = runBlocking {
        val available = listOf(tool("read_skill"))
        val selector = SkillAwareToolSelector(
            delegate = FixedSelector(available),
            skillStore = FakeSkillStore(listOf(activeSkill(tools = setOf("imaginary_tool")))),
        )

        assertEquals(listOf("read_skill"), selector.select("q", 4_096, available).map { it.name })
    }

    @Test
    fun `no active skills means the delegate's answer is untouched`() = runBlocking {
        val available = listOf(tool("read_skill"), tool("get_weather"))
        val selector = SkillAwareToolSelector(
            delegate = FixedSelector(listOf(tool("get_weather"))),
            skillStore = FakeSkillStore(emptyList()),
        )

        assertEquals(listOf("get_weather"), selector.select("q", 4_096, available).map { it.name })
    }

    @Test
    fun `a declared tool is offered once, not duplicated`() = runBlocking {
        val available = listOf(tool("read_skill"), tool("get_weather"))
        val selector = SkillAwareToolSelector(
            delegate = FixedSelector(listOf(tool("get_weather"))),
            skillStore = FakeSkillStore(listOf(activeSkill(tools = setOf("get_weather")))),
        )

        assertEquals(listOf("get_weather"), selector.select("q", 4_096, available).map { it.name })
    }
}
