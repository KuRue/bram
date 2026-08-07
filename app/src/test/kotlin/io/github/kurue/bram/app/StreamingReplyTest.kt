package io.github.kurue.bram.app

import io.github.kurue.bram.core.domain.ReasoningFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The split that runs on every token, before the runtime's own parser sees the finished reply.
 *
 * What matters here is that the transcript never shows raw `<think>` markup: a block folds into a
 * collapsed row the moment it closes, not when the turn ends.
 */
class StreamingReplyTest {

    /** What the runtime reports for a Qwen or DeepSeek style format. */
    private val thinkTags = ReasoningFormat(
        supportsThinking = true,
        startTag = "<think>",
        endTags = listOf("</think>"),
    )

    @Test
    fun `plain text is left alone`() {
        val split = streamingReply("Hello there.", thinkTags)
        assertEquals("Hello there.", split.visibleText)
        assertEquals(emptyList<String>(), split.closedReasoning)
        assertNull(split.openReasoning)
    }

    @Test
    fun `an open block is reasoning and there is no answer yet`() {
        val split = streamingReply("<think>weighing the option", thinkTags)
        assertEquals("", split.visibleText)
        assertEquals(emptyList<String>(), split.closedReasoning)
        assertEquals("weighing the option", split.openReasoning)
    }

    @Test
    fun `a closed block leaves the answer visible and the markup gone`() {
        val split = streamingReply("<think>weighed it</think>The answer is four.", thinkTags)
        assertEquals("The answer is four.", split.visibleText)
        assertEquals(listOf("weighed it"), split.closedReasoning)
        assertNull(split.openReasoning)
    }

    @Test
    fun `a partial closing marker still counts as open`() {
        // Deltas arrive mid-token, so "</thi" must not be mistaken for a finished block.
        val split = streamingReply("<think>still going</thi", thinkTags)
        assertEquals("", split.visibleText)
        assertEquals("still going</thi", split.openReasoning)
    }

    @Test
    fun `text before a block is kept`() {
        val split = streamingReply("Sure. <think>how to phrase it</think>Here you go.", thinkTags)
        assertEquals("Sure. Here you go.", split.visibleText)
        assertEquals(listOf("how to phrase it"), split.closedReasoning)
    }

    @Test
    fun `several blocks are reported in order`() {
        val split = streamingReply("<think>one</think>A<think>two</think>B<think>three", thinkTags)
        assertEquals("AB", split.visibleText)
        assertEquals(listOf("one", "two"), split.closedReasoning)
        assertEquals("three", split.openReasoning)
    }

    // Some chat formats open the <think> block in the assistant prompt, so the model's stream has
    // no opening marker — only reasoning and a closing </think>. The stream must fold reasoning as
    // it arrives, not only once the whole reply ends.

    @Test
    fun `an injected block folds reasoning as it streams, with no opening marker`() {
        val split = streamingReply("weighing the option", thinkTags.copy(startsOpen = true))
        assertEquals("", split.visibleText)
        assertEquals(emptyList<String>(), split.closedReasoning)
        assertEquals("weighing the option", split.openReasoning)
    }

    @Test
    fun `an injected block closes when the think marker arrives`() {
        val split = streamingReply("weighed it</think>The answer is four.", thinkTags.copy(startsOpen = true))
        assertEquals("The answer is four.", split.visibleText)
        assertEquals(listOf("weighed it"), split.closedReasoning)
        assertNull(split.openReasoning)
    }

    @Test
    fun `an explicit think marker overrides an injected open`() {
        // A model that emits its own <think> is read content-first even when an open was injected.
        val split = streamingReply("<think>real</think>answer", thinkTags.copy(startsOpen = true))
        assertEquals("answer", split.visibleText)
        assertEquals(listOf("real"), split.closedReasoning)
        assertNull(split.openReasoning)
    }

    @Test
    fun `a partial closing marker in an injected block stays open`() {
        val split = streamingReply("still going</thi", thinkTags.copy(startsOpen = true))
        assertEquals("", split.visibleText)
        assertEquals(emptyList<String>(), split.closedReasoning)
        assertEquals("still going</thi", split.openReasoning)
    }

    @Test
    fun `text with no marker is an answer when the block was not opened for it`() {
        // startsOpen is false, so unmarked prose is an answer rather than reasoning.
        val split = streamingReply("Just an answer, no reasoning.", thinkTags)
        assertEquals("Just an answer, no reasoning.", split.visibleText)
        assertNull(split.openReasoning)
    }

    // The tags used to be hardcoded to <think>. llama.cpp reports whatever the loaded format
    // actually uses, and several formats use neither of those spellings.

    @Test
    fun `a format with different tags is split on its own markers`() {
        val magistral = ReasoningFormat(
            supportsThinking = true,
            startTag = "[THINK]",
            endTags = listOf("[/THINK]"),
        )
        val split = streamingReply("[THINK]weighed it[/THINK]Four.", magistral)
        assertEquals("Four.", split.visibleText)
        assertEquals(listOf("weighed it"), split.closedReasoning)
    }

    @Test
    fun `a block ends at whichever closing tag comes first`() {
        // One format lists </think> and <tool_call> together, so the block ends at the earlier of
        // the two rather than the one listed first.
        val multi = ReasoningFormat(
            supportsThinking = true,
            startTag = "<think>",
            endTags = listOf("</think>", "<tool_call>"),
        )
        val split = streamingReply("<think>I should call it<tool_call>{}", multi)
        assertEquals(listOf("I should call it"), split.closedReasoning)
        assertEquals("{}", split.visibleText)
    }

    @Test
    fun `a runtime that reports no tags leaves unmarked text alone`() {
        // Nothing is claimed about a format Bram cannot describe, and text with no reasoning markers
        // is never split on a guess.
        val split = streamingReply("Just an answer, no markup.", ReasoningFormat())
        assertEquals("Just an answer, no markup.", split.visibleText)
        assertEquals(emptyList<String>(), split.closedReasoning)
        assertNull(split.openReasoning)
    }

    // Some formats the runtime cannot describe (LFM2.5 among them) still emit the standard markers.
    // Rather than show that reasoning raw, the stream falls back to <think></think> when the markers
    // are plainly in the reply.

    @Test
    fun `an undescribed format still folds a standard think block present in the text`() {
        val split = streamingReply("<think>weighed it</think>The answer is four.", ReasoningFormat())
        assertEquals("The answer is four.", split.visibleText)
        assertEquals(listOf("weighed it"), split.closedReasoning)
        assertNull(split.openReasoning)
    }

    @Test
    fun `an undescribed format folds reasoning that closes with no opening marker`() {
        // LFM2.5: the prompt opens the block, so the stream is reasoning then </think> then the
        // answer, with no <think> of its own.
        val split = streamingReply("deliberating</think>The answer is four.", ReasoningFormat())
        assertEquals("The answer is four.", split.visibleText)
        assertEquals(listOf("deliberating"), split.closedReasoning)
        assertNull(split.openReasoning)
    }
}
