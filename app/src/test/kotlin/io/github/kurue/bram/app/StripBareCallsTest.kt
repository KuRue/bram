package io.github.kurue.bram.app

import org.junit.Assert.assertEquals
import org.junit.Test

class StripBareCallsTest {

    @Test
    fun `a recovered call that is the whole reply is cleared`() {
        assertEquals(
            "",
            stripBareCalls("[web_fetch(url='https://example.com')]", "web_fetch"),
        )
        assertEquals(
            "",
            stripBareCalls("web_fetch(url='https://example.com')", "web_fetch"),
        )
    }

    @Test
    fun `ordinary prose keeps its parentheses`() {
        // The old regex removed any word(...) shape anywhere, so an answer explaining f(x) lost it.
        val answer = "A function like f(x) is differentiable when its limit exists."
        assertEquals(answer, stripBareCalls(answer, "web_fetch"))
    }

    @Test
    fun `a call to a different tool is not stripped`() {
        assertEquals(
            "[web_fetch(url='https://example.com')]",
            stripBareCalls("[web_fetch(url='https://example.com')]", "device_status"),
        )
    }

    @Test
    fun `a call with prose around it is left alone`() {
        // Only a whole-reply call is recoverable at all, so this can never be the call this round
        // ran; stripping it would delete text the model wrote to be read.
        val answer = "I checked the page. [web_fetch(url='https://example.com')]"
        assertEquals(answer, stripBareCalls(answer, "web_fetch"))
    }
}
