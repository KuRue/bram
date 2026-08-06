package io.github.kurue.bram.app

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

    @Test
    fun `plain text is left alone`() {
        val split = streamingReply("Hello there.")
        assertEquals("Hello there.", split.visibleText)
        assertEquals(emptyList<String>(), split.closedReasoning)
        assertNull(split.openReasoning)
    }

    @Test
    fun `an open block is reasoning and there is no answer yet`() {
        val split = streamingReply("<think>weighing the option")
        assertEquals("", split.visibleText)
        assertEquals(emptyList<String>(), split.closedReasoning)
        assertEquals("weighing the option", split.openReasoning)
    }

    @Test
    fun `a closed block leaves the answer visible and the markup gone`() {
        val split = streamingReply("<think>weighed it</think>The answer is four.")
        assertEquals("The answer is four.", split.visibleText)
        assertEquals(listOf("weighed it"), split.closedReasoning)
        assertNull(split.openReasoning)
    }

    @Test
    fun `a partial closing marker still counts as open`() {
        // Deltas arrive mid-token, so "</thi" must not be mistaken for a finished block.
        val split = streamingReply("<think>still going</thi")
        assertEquals("", split.visibleText)
        assertEquals("still going</thi", split.openReasoning)
    }

    @Test
    fun `text before a block is kept`() {
        val split = streamingReply("Sure. <think>how to phrase it</think>Here you go.")
        assertEquals("Sure. Here you go.", split.visibleText)
        assertEquals(listOf("how to phrase it"), split.closedReasoning)
    }

    @Test
    fun `several blocks are reported in order`() {
        val split = streamingReply("<think>one</think>A<think>two</think>B<think>three")
        assertEquals("AB", split.visibleText)
        assertEquals(listOf("one", "two"), split.closedReasoning)
        assertEquals("three", split.openReasoning)
    }

    // Some chat formats open the <think> block in the assistant prompt, so the model's stream has
    // no opening marker — only reasoning and a closing </think>. The stream must fold reasoning as
    // it arrives, not only once the whole reply ends.

    @Test
    fun `an injected block folds reasoning as it streams, with no opening marker`() {
        val split = streamingReply("weighing the option", reasoningStartsOpen = true)
        assertEquals("", split.visibleText)
        assertEquals(emptyList<String>(), split.closedReasoning)
        assertEquals("weighing the option", split.openReasoning)
    }

    @Test
    fun `an injected block closes when the think marker arrives`() {
        val split = streamingReply("weighed it</think>The answer is four.", reasoningStartsOpen = true)
        assertEquals("The answer is four.", split.visibleText)
        assertEquals(listOf("weighed it"), split.closedReasoning)
        assertNull(split.openReasoning)
    }

    @Test
    fun `an explicit think marker overrides an injected open`() {
        // A model that emits its own <think> is read content-first even when an open was injected.
        val split = streamingReply("<think>real</think>answer", reasoningStartsOpen = true)
        assertEquals("answer", split.visibleText)
        assertEquals(listOf("real"), split.closedReasoning)
        assertNull(split.openReasoning)
    }

    @Test
    fun `a partial closing marker in an injected block stays open`() {
        val split = streamingReply("still going</thi", reasoningStartsOpen = true)
        assertEquals("", split.visibleText)
        assertEquals(emptyList<String>(), split.closedReasoning)
        assertEquals("still going</thi", split.openReasoning)
    }

    @Test
    fun `an injected open with no marker is plain text when reasoning is off`() {
        // reasoningStartsOpen is only set when the model is asked to reason; otherwise unmarked
        // prose is an answer, not reasoning.
        val split = streamingReply("Just an answer, no reasoning.")
        assertEquals("Just an answer, no reasoning.", split.visibleText)
        assertNull(split.openReasoning)
    }
}
