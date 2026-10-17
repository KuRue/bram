package io.github.kurue.bram.core.domain

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * One month of work, built from the run journal for Home.
 *
 * The rules come from the product plan: a turn that failed over and then answered is one
 * completed run (attempts share a root id); tokens are summed from every attempt the runtime
 * measured, because retries really spend them; usage the runtime never reported is unknown, not
 * zero; tool calls are executions, not model rounds. Months and days follow the phone's time zone.
 */
data class MonthActivity(
    val month: YearMonth,
    /** Output tokens across measured attempts; null when no run this month reported usage. */
    val generatedTokens: Long?,
    val inputTokens: Long?,
    /** Generated tokens split by where they ran. */
    val localGeneratedTokens: Long,
    val remoteGeneratedTokens: Long,
    /** Distinct turns or tasks with a successful attempt. */
    val completedRuns: Int,
    /** Distinct turns or tasks that ended without one: failed or cancelled. */
    val unfinishedRuns: Int,
    /** Tool executions; null when every run predates the count. */
    val toolCalls: Int?,
    val completedByOrigin: Map<RunOrigin, Int>,
    /** Completed runs per day of the month, for the chart; days without runs are absent. */
    val runsPerDay: Map<LocalDate, Int>,
    /**
     * False when part of the month predates complete records (or some runs had no usage), so the
     * totals undercount; Home says so instead of presenting them as the whole month.
     */
    val complete: Boolean,
) {
    val isEmpty: Boolean get() = completedRuns == 0 && unfinishedRuns == 0
}

object ActivitySummary {

    /** The `[start, end)` epoch-millisecond bounds of [month] in [zone]. */
    fun bounds(month: YearMonth, zone: ZoneId): Pair<Long, Long> =
        month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Summarises [entries] (any order, any range: those outside [month] are ignored).
     * [recordedSince] is when complete records begin, from the journal.
     */
    fun month(
        entries: List<RunJournalEntry>,
        month: YearMonth,
        zone: ZoneId,
        recordedSince: Long?,
    ): MonthActivity {
        val (start, end) = bounds(month, zone)
        val inMonth = entries.filter { it.startedAtEpochMillis in start until end }
        val measured = inMonth.filter { it.usageMeasured }
        val terminal = inMonth.filter { it.status != RunStatus.RUNNING }
        val byRoot = terminal.groupBy { it.rootRunId ?: it.id }
        val succeededRoots = byRoot.filterValues { attempts -> attempts.any { it.status == RunStatus.SUCCEEDED } }
        val unfinished = byRoot.size - succeededRoots.size
        // A root is dated by its first attempt, so a turn that crossed midnight is counted once.
        val perDay = succeededRoots.values
            .map { attempts -> attempts.minOf { it.startedAtEpochMillis } }
            .groupingBy { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .eachCount()
        val byOrigin = succeededRoots.values
            .map { attempts -> attempts.first().origin }
            .groupingBy { it }
            .eachCount()
        val toolCounts = inMonth.mapNotNull { it.toolCalls }
        val coversMonth = recordedSince != null && recordedSince <= start
        val allMeasured = terminal.all { it.usageMeasured }
        return MonthActivity(
            month = month,
            generatedTokens = measured.takeIf { it.isNotEmpty() }?.sumOf { (it.outputTokens ?: 0).toLong() },
            inputTokens = measured.takeIf { it.isNotEmpty() }?.sumOf { (it.inputTokens ?: 0).toLong() },
            localGeneratedTokens = measured.filter { it.remote != true }.sumOf { (it.outputTokens ?: 0).toLong() },
            remoteGeneratedTokens = measured.filter { it.remote == true }.sumOf { (it.outputTokens ?: 0).toLong() },
            completedRuns = succeededRoots.size,
            unfinishedRuns = unfinished,
            toolCalls = toolCounts.takeIf { it.isNotEmpty() }?.sum(),
            completedByOrigin = byOrigin,
            runsPerDay = perDay,
            complete = coversMonth && allMeasured && inMonth.all { it.toolCalls != null },
        )
    }
}
