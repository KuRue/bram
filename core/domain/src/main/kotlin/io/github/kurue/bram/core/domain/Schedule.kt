package io.github.kurue.bram.core.domain

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * The schedules people actually pick, as typed values that compile to the five-field cron the
 * runner already uses. Cron stays available as [Custom] for anything else, and an existing
 * expression that matches a preset's shape reads back as that preset, so nothing is migrated.
 */
sealed interface Schedule {
    val cron: String

    data class Daily(val hour: Int, val minute: Int) : Schedule {
        override val cron get() = "$minute $hour * * *"
    }

    data class Weekdays(val hour: Int, val minute: Int) : Schedule {
        override val cron get() = "$minute $hour * * 1-5"
    }

    /** [day] in cron numbering: Sunday 0 through Saturday 6. */
    data class Weekly(val day: Int, val hour: Int, val minute: Int) : Schedule {
        override val cron get() = "$minute $hour * * $day"
    }

    /** [day] of the month, 1-28 so it fires every month. */
    data class Monthly(val day: Int, val hour: Int, val minute: Int) : Schedule {
        override val cron get() = "$minute $hour $day * *"
    }

    data class Custom(override val cron: String) : Schedule

    companion object {
        private val DAILY = Regex("""^(\d{1,2}) (\d{1,2}) \* \* \*$""")
        private val WEEKDAYS = Regex("""^(\d{1,2}) (\d{1,2}) \* \* 1-5$""")
        private val WEEKLY = Regex("""^(\d{1,2}) (\d{1,2}) \* \* ([0-7])$""")
        private val MONTHLY = Regex("""^(\d{1,2}) (\d{1,2}) (\d{1,2}) \* \*$""")

        /** Reads a stored cron expression back as the simplest schedule that produces it. */
        fun fromCron(expression: String): Schedule {
            val cron = expression.trim().replace(Regex("""\s+"""), " ")
            fun valid(m: Int, h: Int) = m in 0..59 && h in 0..23
            DAILY.matchEntire(cron)?.destructured?.let { (m, h) ->
                if (valid(m.toInt(), h.toInt())) return Daily(h.toInt(), m.toInt())
            }
            WEEKDAYS.matchEntire(cron)?.destructured?.let { (m, h) ->
                if (valid(m.toInt(), h.toInt())) return Weekdays(h.toInt(), m.toInt())
            }
            WEEKLY.matchEntire(cron)?.destructured?.let { (m, h, d) ->
                if (valid(m.toInt(), h.toInt())) return Weekly(d.toInt() % 7, h.toInt(), m.toInt())
            }
            MONTHLY.matchEntire(cron)?.destructured?.let { (m, h, d) ->
                if (valid(m.toInt(), h.toInt()) && d.toInt() in 1..28) return Monthly(d.toInt(), h.toInt(), m.toInt())
            }
            return Custom(expression.trim())
        }

        /** "Every day at 09:00", "Weekdays at 07:30", "Mondays at 18:00", or the raw expression. */
        fun describe(schedule: Schedule, locale: Locale = Locale.getDefault()): String {
            fun time(h: Int, m: Int) = "%02d:%02d".format(h, m)
            return when (schedule) {
                is Daily -> "Every day at ${time(schedule.hour, schedule.minute)}"
                is Weekdays -> "Weekdays at ${time(schedule.hour, schedule.minute)}"
                is Weekly -> "${dayName(schedule.day, locale)}s at ${time(schedule.hour, schedule.minute)}"
                is Monthly -> "Monthly on day ${schedule.day} at ${time(schedule.hour, schedule.minute)}"
                is Custom -> "Custom: ${schedule.cron}"
            }
        }

        /** Cron day number (Sunday 0) to its name. */
        fun dayName(day: Int, locale: Locale = Locale.getDefault()): String {
            val dayOfWeek = if (day % 7 == 0) DayOfWeek.SUNDAY else DayOfWeek.of(day % 7)
            return dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        }

        /**
         * The next [count] fires after [afterEpochMillis], for the editor's preview; empty when
         * the expression is invalid or never fires.
         */
        fun upcoming(cron: String, afterEpochMillis: Long, count: Int = 3, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
            val spec = (Cron.parse(cron) as? Cron.Result.Ok)?.spec ?: return emptyList()
            val out = mutableListOf<Long>()
            var from = afterEpochMillis
            repeat(count) {
                val next = Cron.nextRunAfter(spec, from, zone) ?: return out
                out += next
                from = next
            }
            return out
        }
    }
}
