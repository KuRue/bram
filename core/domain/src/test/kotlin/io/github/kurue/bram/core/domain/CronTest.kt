package io.github.kurue.bram.core.domain

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CronTest {

    private val utc = ZoneId.of("UTC")

    private fun next(cron: String, after: String): Long? {
        val spec = when (val result = Cron.parse(cron)) {
            is Cron.Result.Ok -> result.spec
            is Cron.Result.Rejected -> throw AssertionError("unexpected rejection: ${result.reason}")
        }
        val afterMillis = java.time.Instant.parse(after).toEpochMilli()
        return Cron.nextRunAfter(spec, afterMillis, utc)
    }

    private fun assertNext(expected: String, cron: String, after: String) {
        assertEquals(java.time.Instant.parse(expected).toEpochMilli(), next(cron, after))
    }

    @Test
    fun `daily at 9am from later the same day is tomorrow 9am`() {
        assertNext("2026-08-09T09:00:00Z", "0 9 * * *", "2026-08-08T10:00:00Z")
    }

    @Test
    fun `daily at 8am from earlier the same day is today 8am`() {
        assertNext("2026-08-08T08:00:00Z", "0 8 * * *", "2026-08-08T07:30:00Z")
    }

    @Test
    fun `strictly after - a fire exactly on the minute is not repeated`() {
        assertNext("2026-08-10T09:00:00Z", "0 9 * * *", "2026-08-09T09:00:00Z")
    }

    @Test
    fun `weekdays only skips the weekend`() {
        // 2026-08-08 is a Saturday.
        assertNext("2026-08-10T09:00:00Z", "0 9 * * 1-5", "2026-08-08T12:00:00Z")
    }

    @Test
    fun `every 15 minutes rolls to the next quarter`() {
        assertNext("2026-08-08T10:15:00Z", "*/15 * * * *", "2026-08-08T10:07:00Z")
        assertNext("2026-08-08T10:30:00Z", "*/15 * * * *", "2026-08-08T10:15:00Z")
    }

    @Test
    fun `a range with a step`() {
        assertNext("2026-08-08T10:10:00Z", "0-30/10 * * * *", "2026-08-08T10:05:00Z")
    }

    @Test
    fun `a comma list`() {
        assertNext("2026-08-08T11:00:00Z", "0 9,11 * * *", "2026-08-08T10:30:00Z")
    }

    @Test
    fun `february 31st never fires`() {
        assertNull(next("0 0 31 2 *", "2026-08-08T10:00:00Z"))
    }

    @Test
    fun `a fixed date in the year`() {
        assertNext("2026-08-15T14:30:00Z", "30 14 15 8 *", "2026-08-08T10:00:00Z")
    }

    @Test
    fun `restricted day-of-month and day-of-week match either`() {
        // Friday the 13th: from the 8th, the next match is the 13th (a Thursday, matching day-of-month).
        assertNext("2026-08-13T09:00:00Z", "0 9 13 * 5", "2026-08-08T10:00:00Z")
        // After the 13th, the next match is the next Friday.
        assertNext("2026-08-14T09:00:00Z", "0 9 13 * 5", "2026-08-13T12:00:00Z")
    }

    @Test
    fun `day of week 7 is Sunday`() {
        // 2026-08-09 is a Sunday.
        assertNext("2026-08-09T09:00:00Z", "0 9 * * 7", "2026-08-08T10:00:00Z")
        assertNext("2026-08-09T09:00:00Z", "0 9 * * 0", "2026-08-08T10:00:00Z")
    }

    @Test
    fun `a month restriction`() {
        // September 1st is the next first-of-month after August 8th.
        assertNext("2026-09-01T00:00:00Z", "0 0 1 9 *", "2026-08-08T10:00:00Z")
    }

    @Test
    fun `rejections name the offending field`() {
        fun rejected(cron: String): String? {
            return when (val result = Cron.parse(cron)) {
                is Cron.Result.Rejected -> result.reason
                is Cron.Result.Ok -> null
            }
        }

        assertTrue(rejected("0 9 * *")!!.contains("five fields"))
        assertTrue(rejected("60 9 * * *")!!.contains("minute"))
        assertTrue(rejected("0 25 * * *")!!.contains("hour"))
        assertTrue(rejected("0 9 32 * *")!!.contains("day of month"))
        assertTrue(rejected("0 9 * 13 *")!!.contains("month"))
        assertTrue(rejected("0 9 * * 8")!!.contains("day of week"))
        assertTrue(rejected("0 9 * * jan")!!.contains("day of week"))
        assertTrue(rejected("0 5-2 * * *")!!.contains("day of month") || rejected("0 5-2 * * *")!!.contains("hour"))
        assertTrue(rejected("0 */0 * * *")!!.contains("hour"))
        assertTrue(rejected("")!!.contains("five fields"))
    }

    @Test
    fun `a step over the whole range includes both ends`() {
        // 0,10,...,50 then the top of the hour 0 again: from 10:58 the next is 11:00.
        assertNext("2026-08-08T11:00:00Z", "*/10 * * * *", "2026-08-08T10:58:00Z")
    }
}
