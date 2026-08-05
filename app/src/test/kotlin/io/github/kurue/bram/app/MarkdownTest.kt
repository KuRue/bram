package io.github.kurue.bram.app

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test
    fun `plain text is unchanged`() {
        assertEquals("Hello there", renderMarkdown("Hello there").text)
    }

    @Test
    fun `bold markers are removed and the span is bold`() {
        val rendered = renderMarkdown("a **strong** word")

        assertEquals("a strong word", rendered.text)
        val bold = rendered.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("strong", rendered.text.substring(bold.start, bold.end))
    }

    @Test
    fun `italic markers are removed and the span is italic`() {
        val rendered = renderMarkdown("an *emphasised* word")

        assertEquals("an emphasised word", rendered.text)
        val italic = rendered.spanStyles.single { it.item.fontStyle == FontStyle.Italic }
        assertEquals("emphasised", rendered.text.substring(italic.start, italic.end))
    }

    @Test
    fun `bold is not mistaken for two italics`() {
        val rendered = renderMarkdown("**both**")

        assertEquals("both", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(rendered.spanStyles.none { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun `asterisks inside words are left alone`() {
        // Identifiers and globs are common in replies about code, and mangling them loses meaning.
        val source = "use file*.kt and a*b"
        assertEquals(source, renderMarkdown(source).text)
    }

    @Test
    fun `inline code keeps its contents verbatim`() {
        val rendered = renderMarkdown("call `foo(**bar**)` now")

        assertEquals("call foo(**bar**) now", rendered.text)
        assertTrue(rendered.spanStyles.none { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `bullets become a marker and keep their text`() {
        val rendered = renderMarkdown("- one\n- two")
        assertEquals("• one\n• two", rendered.text)
    }

    @Test
    fun `headings drop their hashes and are bold`() {
        val rendered = renderMarkdown("## Title")

        assertEquals("Title", rendered.text)
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `fenced blocks keep their contents and drop the fences`() {
        val rendered = renderMarkdown("before\n```\nval x = 1\nval y = 2\n```\nafter")

        assertTrue(rendered.text.contains("val x = 1\nval y = 2"))
        assertTrue(rendered.text.contains("before"))
        assertTrue(rendered.text.contains("after"))
        assertTrue("fence markers should not survive", !rendered.text.contains("```"))
    }

    @Test
    fun `an unterminated bold marker is shown rather than swallowed`() {
        // A streamed reply is regularly incomplete; text must never vanish mid-generation.
        assertEquals("a **partial", renderMarkdown("a **partial").text)
    }

    @Test
    fun `empty input produces empty output`() {
        assertEquals("", renderMarkdown("").text)
    }
}
