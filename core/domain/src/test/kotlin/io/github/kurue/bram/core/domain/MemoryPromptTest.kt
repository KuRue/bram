package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptTest {

    @Test
    fun `an empty memory list is a no-op`() {
        assertEquals("You are Bram.", MemoryPrompt.append("You are Bram.", emptyList()))
    }

    @Test
    fun `facts and instructions appear with their kind as a label`() {
        val memories = listOf(
            MemoryRecord("a", MemoryKind.SEMANTIC_FACT, "The user lives in Tokyo", importance = 0.9),
            MemoryRecord("b", MemoryKind.USER_INSTRUCTION, "Always answer in French", importance = 0.8),
        )

        val section = MemoryPrompt.section(memories)

        assertTrue(section.contains("Fact: The user lives in Tokyo"))
        assertTrue(section.contains("Instruction: Always answer in French"))
        assertTrue(section.contains("WHAT YOU REMEMBER"))
    }

    @Test
    fun `working summaries are never injected into the prompt`() {
        val memories = listOf(
            MemoryRecord("ws", MemoryKind.WORKING_SUMMARY, "compaction scratchpad", importance = 1.0),
            MemoryRecord("fact", MemoryKind.SEMANTIC_FACT, "a real fact", importance = 0.5),
        )

        val prompt = MemoryPrompt.append("base", memories)

        assertTrue(prompt.contains("a real fact"))
        assertFalse("the working summary must not leak into the prompt", prompt.contains("compaction scratchpad"))
    }

    @Test
    fun `the char budget caps the section so it cannot grow unbounded`() {
        val memories = (1..500).map { i ->
            MemoryRecord("m$i", MemoryKind.SEMANTIC_FACT, "fact number $i is a reasonably long line of standing context", importance = 0.5)
        }

        val section = MemoryPrompt.section(memories)

        // The section stays near the budget — never the full 500 memories (~35 KB).
        assertTrue(
            "section must stay bounded by the budget, was ${section.length}",
            section.length <= MemoryPrompt.MAX_MEMORY_PROMPT_CHARS + 200,
        )
        assertTrue("the first memory is kept", section.contains("fact number 1"))
        assertFalse("later memories are dropped once the budget is spent", section.contains("fact number 499"))
    }

    @Test
    fun `append layers the section under the existing prompt`() {
        val memories = listOf(MemoryRecord("a", MemoryKind.SEMANTIC_FACT, "Lives in Paris", importance = 0.7))

        val prompt = MemoryPrompt.append("You are Bram.", memories)

        assertTrue(prompt.startsWith("You are Bram."))
        assertTrue(prompt.contains("WHAT YOU REMEMBER"))
        assertTrue(prompt.contains("Lives in Paris"))
    }
}
