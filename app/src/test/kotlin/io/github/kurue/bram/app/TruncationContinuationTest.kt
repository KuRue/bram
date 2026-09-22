package io.github.kurue.bram.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a turn the runtime cut off at the length limit is handled.
 *
 * The failure this pins was seen on the phone: a reasoning model spent its whole reply budget
 * inside an unclosed ` thinking` block, and the turn settled with thinking rows and a tool row but
 * no answer at all. A truncated round with no visible answer gets one more run with a nudge; a
 * truncated reply that exists is left alone and marked.
 */
class TruncationContinuationTest {

    @Test
    fun `a truncated turn with no visible answer is run again`() {
        assertTrue(shouldContinueAfterTruncation(truncated = true, visibleAnswer = "", attempts = 0))
    }

    @Test
    fun `a truncated reply that exists is not run again`() {
        assertFalse(
            shouldContinueAfterTruncation(
                truncated = true,
                visibleAnswer = "The answer so far",
                attempts = 0,
            ),
        )
    }

    @Test
    fun `an untruncated turn is never run again`() {
        assertFalse(shouldContinueAfterTruncation(truncated = false, visibleAnswer = "", attempts = 0))
    }

    @Test
    fun `a turn is only continued once`() {
        assertFalse(shouldContinueAfterTruncation(truncated = true, visibleAnswer = "", attempts = 1))
    }

    @Test
    fun `a finished reply is left alone`() {
        assertEquals("All done.", withTruncationNotice("All done.", truncated = false))
    }

    @Test
    fun `a cut-off reply carries the notice after its text`() {
        assertEquals(
            "The answer so far\n\n" + TRUNCATION_NOTICE,
            withTruncationNotice("The answer so far", truncated = true),
        )
    }

    @Test
    fun `a cut-off reply with nothing visible is only the notice`() {
        // Better than an empty message beside the thinking rows: it says why there is no answer.
        assertEquals(TRUNCATION_NOTICE, withTruncationNotice("", truncated = true))
    }
}
