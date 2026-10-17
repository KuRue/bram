package io.github.kurue.bram.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class ScheduleTest {

    private val zone = ZoneId.of("America/New_York")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun local(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    @Test
    fun `presets compile to cron and read back as themselves`() {
        val presets = listOf(
            Schedule.Daily(9, 0),
            Schedule.Weekdays(7, 30),
            Schedule.Weekly(1, 18, 5),
            Schedule.Monthly(1, 8, 0),
        )
        presets.forEach { assertEquals(it, Schedule.fromCron(it.cron)) }
        assertEquals("0 9 * * *", Schedule.Daily(9, 0).cron)
        assertEquals("30 7 * * 1-5", Schedule.Weekdays(7, 30).cron)
    }

    @Test
    fun `anything else stays custom, untouched`() {
        assertEquals(Schedule.Custom("*/15 * * * *"), Schedule.fromCron("*/15 * * * *"))
        assertEquals(Schedule.Custom("0 9 31 * *"), Schedule.fromCron("0 9 31 * *"))
        assertEquals("Custom: */15 * * * *", Schedule.describe(Schedule.fromCron("*/15 * * * *")))
    }

    @Test
    fun `schedules read in plain words`() {
        assertEquals("Every day at 09:00", Schedule.describe(Schedule.Daily(9, 0), Locale.US))
        assertEquals("Weekdays at 07:30", Schedule.describe(Schedule.Weekdays(7, 30), Locale.US))
        assertEquals("Mondays at 18:05", Schedule.describe(Schedule.Weekly(1, 18, 5), Locale.US))
        assertEquals("Sundays at 10:00", Schedule.describe(Schedule.fromCron("0 10 * * 7"), Locale.US))
    }

    @Test
    fun `the preview lists the next runs`() {
        val runs = Schedule.upcoming("0 9 * * 1-5", at(2026, 10, 16, 12), count = 3, zone = zone)
        // Friday afternoon: the next weekday mornings are Monday, Tuesday, Wednesday.
        assertEquals(listOf(19, 20, 21), runs.map { local(it).dayOfMonth })
        assertTrue(runs.all { local(it).hour == 9 })
        assertEquals(emptyList<Long>(), Schedule.upcoming("not cron", at(2026, 10, 16, 12), zone = zone))
    }

    @Test
    fun `a time skipped by daylight saving does not run that day`() {
        // 8 March 2026: New York clocks jump from 02:00 to 03:00, so 02:30 never happens.
        val runs = Schedule.upcoming("30 2 * * *", at(2026, 3, 7, 12), count = 2, zone = zone)
        assertEquals(listOf(9, 10), runs.map { local(it).dayOfMonth })
        assertTrue(runs.all { local(it).hour == 2 && local(it).minute == 30 })
    }

    @Test
    fun `an hour repeated by daylight saving runs once`() {
        // 1 November 2026: 01:30 happens twice in New York; the schedule fires once that day.
        val runs = Schedule.upcoming("30 1 * * *", at(2026, 10, 31, 12), count = 2, zone = zone)
        assertEquals(listOf(1, 2), runs.map { local(it).dayOfMonth })
    }
}
