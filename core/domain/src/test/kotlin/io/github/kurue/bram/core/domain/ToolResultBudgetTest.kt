package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultBudgetTest {

    @Test
    fun `a result that fits passes through unchanged`() {
        val result = "short result"
        assertEquals(result, ToolResultBudget.apply(result, 8_192))
    }

    @Test
    fun `an oversized result is bounded and marked`() {
        val result = "x".repeat(200_000)
        val bounded = ToolResultBudget.apply(result, 8_192)
        val cap = ToolResultBudget.capChars(8_192)
        assertTrue("bounded length ${bounded.length} exceeds cap $cap", bounded.length <= cap + 64)
        assertTrue(bounded.contains("tool result truncated: 200000 characters total"))
        assertTrue("keeps the head", bounded.startsWith("x"))
        assertTrue("keeps the tail", bounded.endsWith("x"))
    }

    @Test
    fun `the cap tracks the context window and stays inside its bounds`() {
        assertEquals(ToolResultBudget.MIN_CHARS, ToolResultBudget.capChars(2_048))
        assertEquals(6_144, ToolResultBudget.capChars(8_192))
        assertEquals(24_576, ToolResultBudget.capChars(32_768))
        assertEquals(ToolResultBudget.MAX_CHARS, ToolResultBudget.capChars(1_048_576))
    }

    @Test
    fun `a huge result for a tiny window cannot exceed the smallest budget`() {
        val result = "y".repeat(500_000)
        val bounded = ToolResultBudget.apply(result, 1_024)
        assertTrue(bounded.length <= ToolResultBudget.MIN_CHARS + 64)
    }
}
