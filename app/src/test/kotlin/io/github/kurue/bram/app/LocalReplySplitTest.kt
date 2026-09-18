package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ReasoningFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a finished local reply becomes the answer and reasoning the transcript persists.
 *
 * The runtime's parser has the last word when it found reasoning: its content is the answer even
 * when that content is empty. The bug this pins was found on-device with Qwen3.5-0.8B, which spent
 * its whole reply budget inside an unclosed ` thinking` block: the raw text - marker and all - was
 * saved as the "answer" and shown beside the reasoning row, so the reasoning appeared twice.
 */
class LocalReplySplitTest {

    /** What the runtime reports for a Qwen or DeepSeek style format. */
    private val thinkTags = ReasoningFormat(
        supportsThinking = true,
        startTag = " thinking",
        endTags = listOf("</think>"),
    )

    @Test
    fun `reasoning-only reply has no answer, not the raw block`() {
        val raw = " thinkingThinking Process:\n1. Analyze the request..."
        val (visible, reasoning) = splitLocalReply(
            rawReply = raw,
            parsedContent = "",
            parsedReasoning = "Thinking Process:\n1. Analyze the request...",
            format = thinkTags,
        )
        assertEquals("", visible)
        assertEquals("Thinking Process:\n1. Analyze the request...", reasoning)
    }

    @Test
    fun `reasoning with an answer keeps the parsed pair`() {
        val (visible, reasoning) = splitLocalReply(
            rawReply = " thinkingweighed it</think>The answer is four.",
            parsedContent = "The answer is four.",
            parsedReasoning = "weighed it",
            format = thinkTags,
        )
        assertEquals("The answer is four.", visible)
        assertEquals("weighed it", reasoning)
    }

    @Test
    fun `a closed think-only reply does not fall back to the raw markup`() {
        // The parser strips the block to nothing; the raw text still has it, and used to be saved
        // as the answer because the stripped content was blank.
        val (visible, reasoning) = splitLocalReply(
            rawReply = " thinkingweighed it</think>",
            parsedContent = "",
            parsedReasoning = "weighed it",
            format = thinkTags,
        )
        assertEquals("", visible)
        assertEquals("weighed it", reasoning)
    }

    @Test
    fun `a parser that found no reasoning still folds the stream's markers`() {
        val (visible, reasoning) = splitLocalReply(
            rawReply = "Sure.  thinkinghow to phrase it</think>Here you go.",
            parsedContent = "Sure.  thinkinghow to phrase it</think>Here you go.",
            parsedReasoning = "",
            format = thinkTags,
        )
        assertEquals("Sure. Here you go.", visible)
        assertEquals("how to phrase it", reasoning)
    }

    @Test
    fun `an unclosed fallback block leaves no answer`() {
        val (visible, reasoning) = splitLocalReply(
            rawReply = "prefix  thinkingstill going",
            parsedContent = "prefix  thinkingstill going",
            parsedReasoning = "",
            format = thinkTags,
        )
        assertEquals("prefix", visible)
        assertEquals("still going", reasoning)
    }

    @Test
    fun `plain text survives untouched`() {
        val (visible, reasoning) = splitLocalReply(
            rawReply = "Hello there.",
            parsedContent = "Hello there.",
            parsedReasoning = "",
            format = thinkTags,
        )
        assertEquals("Hello there.", visible)
        assertEquals("", reasoning)
    }

    @Test
    fun `an empty parse falls back to the stream split rather than losing the reply`() {
        // A parser that returned nothing at all is not the same as one that found reasoning: the
        // raw reply may still be the only copy of the answer.
        val (visible, reasoning) = splitLocalReply(
            rawReply = "The answer is four.",
            parsedContent = "",
            parsedReasoning = "",
            format = thinkTags,
        )
        assertEquals("The answer is four.", visible)
        assertEquals("", reasoning)
    }
}
