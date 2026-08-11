package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingWorkloadClassifierTest {
    @Test
    fun `short conversational requests stay routine`() {
        assertEquals(RoutingWorkload.ROUTINE, RoutingWorkloadClassifier.classify("What's the weather?"))
        assertEquals(RoutingWorkload.ROUTINE, RoutingWorkloadClassifier.classify("Summarize this sentence."))
    }

    @Test
    fun `substantial code debugging escalates`() {
        val request = """
            Debug and refactor this coroutine code. Explain the root cause and add tests.
            ```kotlin
            suspend fun load() = repository.fetch()
            ```
        """.trimIndent()

        assertEquals(RoutingWorkload.DEMANDING, RoutingWorkloadClassifier.classify(request))
    }

    @Test
    fun `long requests escalate without magic wording`() {
        assertEquals(
            RoutingWorkload.DEMANDING,
            RoutingWorkloadClassifier.classify("x".repeat(700)),
        )
    }

    @Test
    fun `long conversation gives a moderately complex request enough context to escalate`() {
        assertEquals(
            RoutingWorkload.DEMANDING,
            RoutingWorkloadClassifier.classify(
                "Review this approach, compare the trade-offs, and recommend the next step.",
                conversationCharacters = 6_000,
            ),
        )
    }
}
