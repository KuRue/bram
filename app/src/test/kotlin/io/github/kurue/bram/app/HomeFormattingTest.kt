package io.github.kurue.bram.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HomeFormattingTest {

    private val zone = ZoneId.of("UTC")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `recent times read the way people say them`() {
        val now = at(2026, 10, 17, 15)
        assertEquals("just now", relativeTime(now - 20_000, now, zone))
        assertEquals("12 min ago", relativeTime(now - 12 * 60_000, now, zone))
        assertEquals("3 h ago", relativeTime(at(2026, 10, 17, 12), now, zone))
        assertEquals("Yesterday", relativeTime(at(2026, 10, 16, 23), now, zone))
        assertEquals("Oct 2", relativeTime(at(2026, 10, 2, 9), now, zone))
        assertEquals("Dec 30, 2025", relativeTime(at(2025, 12, 30, 9), now, zone))
    }

    @Test
    fun `monthly totals are compact`() {
        assertEquals("999", compactCount(999))
        assertEquals("1.2k", compactCount(1_234).replace(',', '.'))
        assertEquals("34k", compactCount(34_567))
        assertEquals("1.2M", compactCount(1_234_567).replace(',', '.'))
    }
}
