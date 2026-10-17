package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class ActivitySummaryTest {

    private val zone = ZoneId.of("America/New_York")
    private val october = YearMonth.of(2026, 10)

    private fun at(day: Int, hour: Int = 12) =
        LocalDate.of(2026, 10, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun run(
        id: String,
        start: Long,
        status: RunStatus = RunStatus.SUCCEEDED,
        root: String? = id,
        output: Int? = 100,
        input: Int? = 400,
        tools: Int? = 0,
        remote: Boolean = false,
        origin: RunOrigin = RunOrigin.CHAT,
        measured: Boolean = output != null || input != null,
    ) = RunJournalEntry(
        id = id,
        conversationId = ConversationId("c"),
        startedAtEpochMillis = start,
        finishedAtEpochMillis = start + 1_000,
        status = status,
        inputTokens = input,
        outputTokens = output,
        rootRunId = root,
        remote = remote,
        origin = origin,
        toolCalls = tools,
        usageMeasured = measured,
    )

    @Test
    fun `a turn that failed over and then answered is one completed run, with both attempts' tokens`() {
        val month = ActivitySummary.month(
            listOf(
                run("a1", at(3), status = RunStatus.FAILED, root = "turn", output = 20, remote = true),
                run("a2", at(3, 13), root = "turn", output = 100),
            ),
            october, zone,
        )
        assertEquals(1, month.completedRuns)
        assertEquals(0, month.unfinishedRuns)
        assertEquals(120L, month.generatedTokens)
        assertEquals(100L, month.localGeneratedTokens)
        assertEquals(20L, month.remoteGeneratedTokens)
    }

    @Test
    fun `unreported usage is unknown, while a measured zero is zero`() {
        val unknown = ActivitySummary.month(listOf(run("a", at(5), output = null, input = null)), october, zone)
        assertNull(unknown.generatedTokens)
        assertFalse("a run without usage makes the month incomplete", unknown.complete)
        assertEquals(1, unknown.unmeasuredRuns)
        assertEquals(0, unknown.legacyRuns)

        val zero = ActivitySummary.month(listOf(run("a", at(5), output = 0, input = 0, measured = true)), october, zone)
        assertEquals(0L, zero.generatedTokens)
        assertTrue(zero.complete)
    }

    @Test
    fun `months and days follow the phone's time zone`() {
        // 23:30 on 31 October in New York is already 1 November in UTC.
        val lateHalloween = at(31, 23) + 30 * 60_000
        val firstOfNovember = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val month = ActivitySummary.month(
            listOf(run("a", lateHalloween), run("b", firstOfNovember)),
            october, zone,
        )
        assertEquals(1, month.completedRuns)
        assertEquals(mapOf(LocalDate.of(2026, 10, 31) to 1), month.runsPerDay)
    }

    @Test
    fun `failed and cancelled turns are unfinished, and running ones are not counted yet`() {
        val month = ActivitySummary.month(
            listOf(
                run("f", at(2), status = RunStatus.FAILED),
                run("c", at(2), status = RunStatus.CANCELLED),
                run("r", at(2), status = RunStatus.RUNNING),
            ),
            october, zone,
        )
        assertEquals(0, month.completedRuns)
        assertEquals(2, month.unfinishedRuns)
    }

    @Test
    fun `tool calls are executions, summed, and rows from before the count make the month partial`() {
        val month = ActivitySummary.month(
            listOf(run("a", at(1), tools = 3), run("b", at(2), tools = 2), run("old", at(2), tools = null)),
            october, zone,
        )
        assertEquals(5, month.toolCalls)
        assertFalse(month.complete)
        assertEquals(1, month.legacyRuns)
    }

    @Test
    fun `a month with only fully recorded runs is complete, even on a fresh install`() {
        val month = ActivitySummary.month(listOf(run("a", at(20))), october, zone)
        assertTrue(month.complete)
        assertEquals(1, month.completedRuns)
    }

    @Test
    fun `completed runs are split by origin`() {
        val month = ActivitySummary.month(
            listOf(run("a", at(1)), run("b", at(2), origin = RunOrigin.TASK), run("c", at(3), origin = RunOrigin.TASK)),
            october, zone,
        )
        assertEquals(mapOf(RunOrigin.CHAT to 1, RunOrigin.TASK to 2), month.completedByOrigin)
    }
}
