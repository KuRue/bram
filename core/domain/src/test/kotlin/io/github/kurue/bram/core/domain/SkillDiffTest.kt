package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillDiffTest {

    private fun kinds(before: String, after: String) = SkillDiff.lines(before, after).map { it.kind }

    private fun texts(before: String, after: String) = SkillDiff.lines(before, after).map { it.prefix + it.text }

    @Test
    fun `identical text is all unchanged`() {
        val lines = SkillDiff.lines("one\ntwo", "one\ntwo")
        assertEquals(listOf(DiffLine.Kind.SAME, DiffLine.Kind.SAME), lines.map { it.kind })
    }

    @Test
    fun `an added line shows as added`() {
        assertEquals(
            listOf(DiffLine.Kind.SAME, DiffLine.Kind.ADDED, DiffLine.Kind.SAME),
            kinds("one\ntwo", "one\nnew\ntwo"),
        )
        assertEquals(
            listOf("  one", "+ new", "  two"),
            texts("one\ntwo", "one\nnew\ntwo"),
        )
    }

    @Test
    fun `a removed line shows as removed`() {
        assertEquals(
            listOf("  one", "- gone", "  two"),
            texts("one\ngone\ntwo", "one\ntwo"),
        )
    }

    @Test
    fun `a changed line reads as a removal and an addition`() {
        assertEquals(
            listOf(DiffLine.Kind.SAME, DiffLine.Kind.REMOVED, DiffLine.Kind.ADDED, DiffLine.Kind.SAME),
            kinds("head\nold\ntail", "head\nnew\ntail"),
        )
    }

    @Test
    fun `a brand-new document is all added`() {
        val lines = SkillDiff.lines("", "first\nsecond")
        assertEquals(listOf(DiffLine.Kind.ADDED, DiffLine.Kind.ADDED), lines.map { it.kind })
    }

    @Test
    fun `a long unchanged document with one edit trims to the edit`() {
        val body = (1..2_000).joinToString("\n") { "line $it" }
        val edited = body.replace("line 2000", "line 2000 changed")
        val lines = SkillDiff.lines(body, edited)
        val changed = lines.filter { it.kind != DiffLine.Kind.SAME }
        assertEquals(2, changed.size)
        assertEquals(listOf("line 2000", "line 2000 changed"), changed.map { it.text })
        assertEquals(2_001, lines.size)
    }

    @Test
    fun `an enormous changed middle degrades instead of building a giant table`() {
        val before = (1..SkillDiff.MAX_LCS_LINES + 1).joinToString("\n") { "old $it" }
        val after = (1..SkillDiff.MAX_LCS_LINES + 1).joinToString("\n") { "new $it" }
        val lines = SkillDiff.lines(before, after)
        assertEquals((SkillDiff.MAX_LCS_LINES + 1) * 2, lines.size)
        assertTrue(lines.none { it.kind == DiffLine.Kind.SAME })
    }
}
