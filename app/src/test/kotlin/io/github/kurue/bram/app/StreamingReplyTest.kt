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
}
