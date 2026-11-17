package com.brightblu.parc

import com.brightblu.parc.core.data.ledger.LedgerDay
import com.brightblu.parc.core.data.steps.StepMath
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** What colours the past dots of the Year grid. The ids are stored (backup), so they stay as they are. */
internal enum class YearMetric(val id: String, val label: String) {
    None("none", "Days passed"),
    Steps("steps", "Steps"),
    Screen("screen", "Screen time"),
    Data("data", "Data used"),
    Pickups("pickups", "Pickups");

    fun next(): YearMetric = entries[(ordinal + 1) % entries.size]

    /** The day's number for this metric, or null when the ledger has none (a missing day stays plain, never zero). */
    fun value(day: LedgerDay?): Double? = when (this) {
        None -> null
        Steps -> day?.steps?.toDouble()
        Screen -> day?.screenOnMillis?.toDouble()
        Data -> if (day == null || !day.hasNetwork) null else ((day.wifiBytes ?: 0L) + (day.mobileBytes ?: 0L)).toDouble()
        Pickups -> day?.pickups?.toDouble()
    }

    companion object {
        fun fromId(id: String?): YearMetric = entries.firstOrNull { it.id == id } ?: None
    }
}

/** The grid: one row per month (12), one column per day of the month (31). A dot is a day. Pure geometry. */
internal object YearGrid {
    const val Rows = 12
    const val Columns = 31

    fun row(date: LocalDate): Int = date.monthValue - 1
    fun column(date: LocalDate): Int = date.dayOfMonth - 1

    /** The date at a grid cell of [year], or null for a cell that is not a day (31 February, ...). */
    fun dateAt(year: Int, row: Int, column: Int): LocalDate? {
        if (row !in 0 until Rows || column !in 0 until Columns) return null
        val month = java.time.YearMonth.of(year, row + 1)
        return if (column + 1 > month.lengthOfMonth()) null else month.atDay(column + 1)
    }

    /** The date under a touch at ([x], [y]) in a grid drawn [width] by [height] wide, or null outside it or on an empty cell. */
    fun dateAtPoint(year: Int, x: Float, y: Float, width: Float, height: Float): LocalDate? {
        if (width <= 0f || height <= 0f || x < 0f || y < 0f || x >= width || y >= height) return null
        return dateAt(year, (y / (height / Rows)).toInt(), (x / (width / Columns)).toInt())
    }
}

internal object YearMath {
    fun daysInYear(year: Int): Int = if (java.time.Year.isLeap(year.toLong())) 366 else 365

    /** `Day 314 of 365 · 86 %` (the percentage is rounded). */
    fun progressLine(date: LocalDate): String {
        val total = daysInYear(date.year)
        val percent = Math.round(date.dayOfYear * 100.0 / total).toInt()
        return "Day ${date.dayOfYear} of $total · $percent %"
    }

    /**
     * Four quiet steps (1 lightest, 4 strongest) for every date that has a value, relative to the user's own
     * range in [values]: the lowest day gets step 1 and the highest step 4. When every value is the same the
     * range has no width, so every day gets step 2 (a middle step, never a judgement). No data, no entry.
     */
    fun levels(values: Map<LocalDate, Double>): Map<LocalDate, Int> {
        if (values.isEmpty()) return emptyMap()
        val low = values.values.min()
        val high = values.values.max()
        val span = high - low
        return values.mapValues { (_, v) ->
            if (span <= 0.0) 2 else ((v - low) / span * 4.0).toInt().coerceIn(0, 3) + 1
        }
    }

    /** One line of the numbers the ledger holds for [day] (only the ones it has), after the date. */
    fun dayLine(date: LocalDate, day: LedgerDay?): String {
        val head = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + dateText(date, withYear = true)
        val parts = buildList {
            day?.steps?.let { add(StepMath.count(it.toLong()) + " steps") }
            day?.screenOnMillis?.let { add(PowerNetworkFormat.duration(it) + " screen") }
            if (day != null && day.hasNetwork) add(PowerNetworkFormat.bytes((day.wifiBytes ?: 0L) + (day.mobileBytes ?: 0L)) + " data")
            day?.pickups?.let { add("$it pickups") }
        }
        return if (parts.isEmpty()) "$head · Nothing recorded" else "$head · " + parts.joinToString(" · ")
    }

    /** `23 Dec`, or `23 Dec 2027` when [withYear]. */
    fun dateText(date: LocalDate, withYear: Boolean = false): String =
        "${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)}" + if (withYear) " ${date.year}" else ""
}

/**
 * A date the user counts down to. [date] is the date typed; [yearly] repeats it every year starting with [date]'s
 * year. A yearly 29 February falls on 28 February in years that have no 29 February.
 */
internal data class Countdown(val name: String, val date: LocalDate, val yearly: Boolean = false) {
    /** The date this countdown lands on in [year], or null when it does not (a one-time date of another year, or a yearly one that has not started). */
    fun occurrenceIn(year: Int): LocalDate? = when {
        yearly && year >= date.year -> inYear(year)
        !yearly && year == date.year -> date
        else -> null
    }

    private fun inYear(year: Int): LocalDate =
        if (date.monthValue == 2 && date.dayOfMonth == 29 && !java.time.Year.isLeap(year.toLong())) LocalDate.of(year, 2, 28)
        else LocalDate.of(year, date.monthValue, date.dayOfMonth)

    /** The next date on or after [today] (today counts), or null for a one-time date that has passed. */
    fun next(today: LocalDate): LocalDate? {
        if (!yearly) return date.takeIf { !it.isBefore(today) }
        val start = maxOf(date.year, today.year)
        val here = inYear(start)
        return if (!here.isBefore(today)) here else inYear(start + 1)
    }

    fun isPassed(today: LocalDate): Boolean = !yearly && date.isBefore(today)
}

/** One row of the list under the grid. */
internal data class CountdownRow(val countdown: Countdown, val on: LocalDate, val days: Long)

internal object Countdowns {
    const val MaxCount = 50
    const val MaxNameLength = 40

    /** The coming ones, nearest first (ties by name), each with the days to go. Passed one-time dates are not here. */
    fun upcoming(all: List<Countdown>, today: LocalDate): List<CountdownRow> =
        all.mapNotNull { c -> c.next(today)?.let { CountdownRow(c, it, java.time.temporal.ChronoUnit.DAYS.between(today, it)) } }
            .sortedWith(compareBy<CountdownRow> { it.days }.thenBy { it.countdown.name.lowercase() })

    /** One-time dates that are over, most recent first. */
    fun passed(all: List<Countdown>, today: LocalDate): List<Countdown> =
        all.filter { it.isPassed(today) }.sortedByDescending { it.date }

    /** The list without the passed one-time dates. */
    fun withoutPassed(all: List<Countdown>, today: LocalDate): List<Countdown> = all.filterNot { it.isPassed(today) }

    /** The dates to mark in the grid of [year]: every occurrence that falls in it, passed ones included. */
    fun marks(all: List<Countdown>, year: Int): Set<LocalDate> = all.mapNotNull { it.occurrenceIn(year) }.toSet()

    /** `Exam · 43 days · 23 Dec`; `Today`; `1 day`. A date in another year says the year. */
    fun rowText(row: CountdownRow, today: LocalDate): String {
        val when_ = when (row.days) { 0L -> "Today"; 1L -> "1 day"; else -> "${row.days} days" }
        return "${row.countdown.name} · $when_ · " + YearMath.dateText(row.on, withYear = row.on.year != today.year)
    }

    fun passedText(c: Countdown): String = "${c.name} · " + YearMath.dateText(c.date, withYear = true)

    /** A name as it is stored: one line, trimmed, at most [MaxNameLength] characters. */
    fun cleanName(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(MaxNameLength)

    /**
     * A typed date: `2026-12-23` (also with `/` or `.`), or `23/12/2026` (also with `-` or `.`). Day and month may be
     * one digit. Anything else, or a date that does not exist, is null.
     */
    fun parseDate(text: String): LocalDate? {
        val parts = text.trim().split('-', '/', '.', ' ').filter { it.isNotEmpty() }
        if (parts.size != 3 || parts.any { p -> p.any { !it.isDigit() } }) return null
        val (a, b, c) = parts
        val (y, m, d) = when {
            a.length == 4 && b.length <= 2 && c.length <= 2 -> Triple(a, b, c)
            c.length == 4 && a.length <= 2 && b.length <= 2 -> Triple(c, b, a)
            else -> return null
        }
        return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
    }

    /** How many years the year stepper offers after its first year. */
    const val PickerYears = 10

    /** The first year the stepper offers: this year, or the year of a date being changed when that is earlier. */
    fun pickerFirstYear(today: LocalDate, editing: LocalDate?): Int = minOf(today.year, editing?.year ?: today.year)

    /** The date the three steppers (day 1 to 31, month 1 to 12, year) stand for; a day past the month's end is the month's last day. */
    fun pickedDate(year: Int, month: Int, day: Int): LocalDate {
        val first = LocalDate.of(year, month.coerceIn(1, 12), 1)
        return first.withDayOfMonth(day.coerceIn(1, first.lengthOfMonth()))
    }

    /** `23/12/2026`, the form [parseDate] reads back. */
    fun dateField(date: LocalDate): String = String.format(Locale.ROOT, "%02d/%02d/%04d", date.dayOfMonth, date.monthValue, date.year)

    /**
     * The stored text: one line per countdown, `2026-12-23|1|Exam` (date, yearly flag, name). The name is last, so
     * it may hold anything but a line break. At most [MaxCount] entries.
     */
    fun encode(all: List<Countdown>): String =
        all.take(MaxCount).joinToString("\n") { "${it.date}|${if (it.yearly) 1 else 0}|${cleanName(it.name)}" }

    /** Old, empty or garbled text reads as what can be read: bad lines are skipped, never a crash. */
    fun decode(text: String?): List<Countdown> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val p = line.split('|', limit = 3)
            if (p.size != 3) return@mapNotNull null
            val date = runCatching { LocalDate.parse(p[0]) }.getOrNull() ?: return@mapNotNull null
            val name = cleanName(p[2])
            if (name.isEmpty()) null else Countdown(name, date, p[1] == "1")
        }.take(MaxCount).toList()
    }
}
