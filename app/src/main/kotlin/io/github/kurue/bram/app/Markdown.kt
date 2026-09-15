package io.github.kurue.bram.app

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

sealed interface MarkdownBlock {
    data class Prose(val source: String) : MarkdownBlock
    data class Code(val code: String, val language: String?) : MarkdownBlock
}

/** Splits fenced code from prose so chat can give code its own scrollable, copyable surface. */
fun parseMarkdownBlocks(source: String): List<MarkdownBlock> {
    if (source.isEmpty()) return listOf(MarkdownBlock.Prose(""))
    val blocks = mutableListOf<MarkdownBlock>()
    val prose = mutableListOf<String>()
    val code = mutableListOf<String>()
    var language: String? = null
    var inFence = false

    fun flushProse() {
        if (prose.isNotEmpty()) {
            blocks += MarkdownBlock.Prose(prose.joinToString("\n").trimEnd('\n'))
            prose.clear()
        }
    }
    fun flushCode() {
        blocks += MarkdownBlock.Code(code.joinToString("\n"), language)
        code.clear()
        language = null
    }

    source.lines().forEach { line ->
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (inFence) {
                flushCode()
                inFence = false
            } else {
                flushProse()
                language = trimmed.removePrefix("```").trim().ifEmpty { null }
                inFence = true
            }
        } else if (inFence) {
            code += line
        } else {
            prose += line
        }
    }
    if (inFence) flushCode() else flushProse()
    return blocks.ifEmpty { listOf(MarkdownBlock.Prose("")) }
}

/**
 * Renders the small subset of Markdown that language models actually emit in chat.
 *
 * Deliberately hand-rolled rather than pulled from a library: models reach for bold, italics,
 * inline code, fenced blocks, and bullets, and supporting those needs far less than a full
 * CommonMark implementation would cost. Anything unrecognised is shown verbatim, so an unsupported
 * construct degrades to plain text rather than disappearing.
 */
fun renderMarkdown(source: String): AnnotatedString = buildAnnotatedString {
    val lines = source.lines()
    var index = 0
    var firstBlock = true

    while (index < lines.size) {
        val line = lines[index]

        if (line.trimStart().startsWith("```")) {
            val fence = lines.drop(index + 1).takeWhile { !it.trimStart().startsWith("```") }
            if (!firstBlock) appendLine()
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                append(fence.joinToString("\n"))
            }
            appendLine()
            index += fence.size + 2
            firstBlock = false
            continue
        }

        if (!firstBlock) appendLine()
        firstBlock = false

        val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
        val bullet = Regex("^\\s*[-*+]\\s+(.*)$").find(line)
        when {
            heading != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInline(heading.groupValues[2])
            }
            bullet != null -> {
                append("• ")
                appendInline(bullet.groupValues[1])
            }
            else -> appendInline(line)
        }
        index++
    }
}

/** Applies the character-level marks: `**bold**`, `*italic*`, and `` `code` ``. */
private fun AnnotatedString.Builder.appendInline(text: String) {
    // One pass over the alternatives, longest marker first so ** is not mistaken for *.
    val pattern = Regex("\\*\\*(.+?)\\*\\*|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])|`([^`]+)`")
    var cursor = 0
    pattern.findAll(text).forEach { match ->
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val bold = match.groupValues[1]
        val italic = match.groupValues[2]
        val code = match.groupValues[3]
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}
