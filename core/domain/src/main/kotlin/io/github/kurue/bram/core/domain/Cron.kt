package io.github.kurue.bram.core.domain

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * An automation: a recurring schedule that enqueues a task — a named prompt that runs in its own
 * conversation, like any queued task — when its cron time comes. The schedule is the unit of work
 * here; [Cron] decides when it fires next, and the runner turns that into an alarm and a task.
 */
data class Automation(
    val id: String,
    val name: String,
    /** A five-field cron schedule: minute hour day-of-month month day-of-week. */
    val cron: String,
    val prompt: String,
    val enabled: Boolean = true,
    val lastRunAtEpochMillis: Long? = null,
    /** The next scheduled fire, computed and kept so the UI can show it without re-parsing. */
    val nextRunAtEpochMillis: Long? = null,
    val updatedAtEpochMillis: Long,
)

interface AutomationStore {
    suspend fun automations(): List<Automation>
    suspend fun save(automation: Automation)
    suspend fun remove(automationId: String)
}

/**
 * Five-field cron, just the shapes a phone schedule actually needs: a bare star, single values,
 * ranges (`1-5`), steps (`star/15`, `0-30/5`), and comma lists. Fields are minute (0-59), hour
 * (0-23), day of month (1-31), month (1-12), and day of week (0-6, Sunday 0; 7 is accepted as
 * Sunday). Names like `jan` or `mon` are deliberately not supported — the UI holds the user's hand
 * with the shape hint, and names are where parse surprises live.
 *
 * Day-of-month and day-of-week follow standard cron semantics: when both are restricted, a day
 * matches if either matches (Friday the 13th); when only one is restricted, that one must match.
 */
object Cron {
    class Spec internal constructor(
        val minutes: IntArray,
        val hours: IntArray,
        val daysOfMonth: IntArray,
        val months: IntArray,
        val daysOfWeek: IntArray,
        val dayOfMonthWildcard: Boolean,
        val dayOfWeekWildcard: Boolean,
        val expression: String,
    )

    sealed interface Result {
        data class Ok(val spec: Spec) : Result
        data class Rejected(val reason: String) : Result
    }

    fun parse(expression: String): Result {
        val normalized = expression.trim()
        if (normalized.isEmpty() || normalized.length > MAX_EXPRESSION_CHARS) {
            return Result.Rejected("A cron schedule is five fields, not empty or huge")
        }
        val fields = normalized.split(Regex("""\s+"""))
        if (fields.size != 5) {
            return Result.Rejected(
                "A cron schedule has five fields: minute hour day-of-month month day-of-week",
            )
        }
        val minute = parseField(fields[0], 0, 59, "minute")
            ?: return Result.Rejected(fieldRejected(fields[0], "minute", 0, 59))
        val hour = parseField(fields[1], 0, 23, "hour")
            ?: return Result.Rejected(fieldRejected(fields[1], "hour", 0, 23))
        val dayOfMonth = parseField(fields[2], 1, 31, "day of month")
            ?: return Result.Rejected(fieldRejected(fields[2], "day of month", 1, 31))
        val month = parseField(fields[3], 1, 12, "month")
            ?: return Result.Rejected(fieldRejected(fields[3], "month", 1, 12))
        val dayOfWeek = parseField(fields[4], 0, 7, "day of week", sundayIsSeven = true)
            ?: return Result.Rejected(fieldRejected(fields[4], "day of week", 0, 7))
        return Result.Ok(
            Spec(
                minutes = minute.first,
                hours = hour.first,
                daysOfMonth = dayOfMonth.first,
                months = month.first,
                daysOfWeek = dayOfWeek.first,
                dayOfMonthWildcard = dayOfMonth.second,
                dayOfWeekWildcard = dayOfWeek.second,
                expression = normalized,
            ),
        )
    }

    /**
     * The first scheduled fire strictly after [afterEpochMillis], in [zone], or null when the
     * schedule can never fire (31 February) or not within the lookahead (roughly two years).
     */
    fun nextRunAfter(spec: Spec, afterEpochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        var current = ZonedDateTime.ofInstant(Instant.ofEpochMilli(afterEpochMillis), zone)
            .withSecond(0)
            .withNano(0)
            .plusMinutes(1)
        repeat(MAX_LOOKAHEAD_DAYS) {
            if (dayMatches(spec, current)) {
                val hour = findHour(spec, current)
                if (hour != null) return hour.toInstant().toEpochMilli()
            }
            current = current.plusDays(1).withHour(0).withMinute(0)
        }
        return null
    }

    private fun dayMatches(spec: Spec, day: ZonedDateTime): Boolean {
        if (!spec.months.contains(day.monthValue)) return false
        val dom = day.dayOfMonth
        val dow = day.dayOfWeek.value % 7 // Monday 0 .. Sunday 6, so Sunday == 0 like cron
        return when {
            !spec.dayOfMonthWildcard && !spec.dayOfWeekWildcard ->
                spec.daysOfMonth.contains(dom) || spec.daysOfWeek.contains(dow)
            !spec.dayOfMonthWildcard -> spec.daysOfMonth.contains(dom)
            !spec.dayOfWeekWildcard -> spec.daysOfWeek.contains(dow)
            else -> true
        }
    }

    private fun findHour(spec: Spec, day: ZonedDateTime): ZonedDateTime? {
        for (hour in day.hour..23) {
            if (!spec.hours.contains(hour)) continue
            val minutesInHour = spec.minutes.filter { minute ->
                if (hour == day.hour) minute >= day.minute else true
            }
            minutesInHour.firstOrNull()?.let { minute ->
                return day.withHour(hour).withMinute(minute)
            }
        }
        return null
    }

    /** Returns (values, wildcard); null when the field is not a valid cron field. */
    private fun parseField(
        field: String,
        min: Int,
        max: Int,
        name: String,
        sundayIsSeven: Boolean = false,
    ): Pair<IntArray, Boolean>? {
        if (field == "*") return (min..max).toList().toIntArray() to true
        val values = LinkedHashSet<Int>()
        for (item in field.split(',')) {
            val step = if (item.contains('/')) item.substringAfter('/').toIntOrNull() else null
            if (item.contains('/') && (step == null || step < 1)) return null
            val (from, to) = if (step == null) {
                val range = item.split('-')
                when (range.size) {
                    1 -> {
                        val single = item.toIntOrNull() ?: return null
                        single to single
                    }
                    2 -> {
                        val from = range[0].toIntOrNull() ?: return null
                        val to = range[1].toIntOrNull() ?: return null
                        if (from > to) return null
                        from to to
                    }
                    else -> return null
                }
            } else {
                val left = item.substringBefore('/')
                if (left == "*") {
                    min to max
                } else {
                    val range = left.split('-')
                    if (range.size != 2) return null
                    val from = range[0].toIntOrNull() ?: return null
                    val to = range[1].toIntOrNull() ?: return null
                    if (from > to) return null
                    from to to
                }
            }
            if (from < min || to > max) return null
            var value = from
            while (value <= to) {
                val normalized = if (sundayIsSeven && value == 7) 0 else value
                values += normalized
                value += step ?: 1
            }
        }
        return values.toIntArray() to false
    }

    private fun fieldRejected(field: String, name: String, min: Int, max: Int) =
        "$name field \"$field\" is not valid (use *, a value, a range like 1-5, or a step like */15; values $min to $max)"

    private const val MAX_EXPRESSION_CHARS = 100
    private const val MAX_LOOKAHEAD_DAYS = 366 * 2
}
