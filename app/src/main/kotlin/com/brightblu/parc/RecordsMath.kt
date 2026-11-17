package com.brightblu.parc

import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.LedgerDay
import com.brightblu.parc.core.data.rhythm.RhythmMath
import com.brightblu.parc.core.data.rhythm.RhythmView
import com.brightblu.parc.core.data.steps.StepMath
import java.time.LocalDate

/** T197: the Records page id. Lowercase and stable, because it is part of the saved hub format. */
internal object RecordsPage {
    const val Id = "records"
}

/** The five personal bests. [high] means a bigger number is the record (and is therefore good to beat during the day). */
internal enum class RecordKind(val label: String, val high: Boolean) {
    MostSteps("Most steps in a day", true),
    LowestScreen("Lowest screen time", false),
    LongestFree("Longest phone-free stretch", true),
    FewestPickups("Fewest pickups in a day", false),
    LowestData("Lowest data use in a day", false),
}

internal enum class RecordStatus { None, Close, Passed }

/** [value] and [date] are null when no past day has that measurement. [today] is today's value so far (null when none). */
internal data class RecordRow(val kind: RecordKind, val value: Long?, val date: LocalDate?, val today: Long?, val status: RecordStatus)

internal enum class StreakKind { UnderAverageScreen, StepGoal }

/**
 * A run of days in a row. [current] ends yesterday (or today, once today has met a goal); [best] is the longest run
 * ever recorded and [bestEnd] its last day. [threshold] is the screen-time average in milliseconds or the step goal.
 */
internal data class StreakRow(val kind: StreakKind, val current: Int, val best: Int, val bestEnd: LocalDate?, val threshold: Long, val today: Long?)

internal sealed interface RecordsState {
    /** [have] ledger days with data before today; [need] are required. */
    data class NotEnough(val have: Int, val need: Int) : RecordsState {
        val remaining: Int get() = (need - have).coerceAtLeast(0)
    }
    /** [streaks] holds only the streaks that can be worked out (a step goal must be set, and 7 days of screen time must exist). */
    data class Ready(val records: List<RecordRow>, val streaks: List<StreakRow>) : RecordsState
}

/** Pure maths for the Records page. Everything comes from the day ledger; nothing is stored. */
internal object RecordsMath {
    const val MinDays = 7

    /** Within this share of a record counts as close. */
    const val CloseFraction = 0.9

    /** A measurement of 0 means Android answered with nothing (phone off, no counter), so it never counts as a day. */
    fun value(kind: RecordKind, day: LedgerDay): Long? = when (kind) {
        RecordKind.MostSteps -> day.steps?.toLong()
        RecordKind.LowestScreen -> day.screenOnMillis
        RecordKind.LongestFree -> day.phoneFreeMillis
        RecordKind.FewestPickups -> day.pickups?.toLong()
        RecordKind.LowestData -> if (day.hasNetwork) (day.wifiBytes ?: 0L) + (day.mobileBytes ?: 0L) else null
    }?.takeIf { it > 0L }

    /** Days before [today] that hold any measurement. Today is unfinished, so it never counts toward the minimum. */
    fun daysAvailable(ledger: DayLedger, today: LocalDate): Int =
        ledger.days.values.count { it.date.isBefore(today) && it.hasData }

    /**
     * Records come from finished days only (before [today]). Today's value sits beside each record. [todaySteps] is the
     * live step total, which can be ahead of the ledger; it replaces the ledger's value for today when given.
     */
    fun compute(ledger: DayLedger, today: LocalDate, stepGoal: Int?, todaySteps: Int? = null): RecordsState {
        val have = daysAvailable(ledger, today)
        if (have < MinDays) return RecordsState.NotEnough(have, MinDays)
        val todayDay = ledger.day(today)
        val past = ledger.days.values.filter { it.date.isBefore(today) }
        val records = RecordKind.entries.map { kind ->
            val best = past.mapNotNull { day -> value(kind, day)?.let { day.date to it } }
                .let { list ->
                    // A tie goes to the most recent day: the record was last matched then.
                    if (kind.high) list.maxWithOrNull(compareBy({ it.second }, { it.first }))
                    else list.minWithOrNull(compareBy<Pair<LocalDate, Long>>({ it.second }).thenByDescending { it.first })
                }
            val now = if (kind == RecordKind.MostSteps && todaySteps != null) todaySteps.toLong().takeIf { it > 0 } else todayDay?.let { value(kind, it) }
            RecordRow(kind, best?.second, best?.first, now, status(kind, best?.second, now))
        }
        return RecordsState.Ready(records, streaks(ledger, today, stepGoal, todaySteps))
    }

    /**
     * Only records that can still be beaten as the day goes on are marked. For the lowest ones today's number only
     * grows, so early in the day it is always "close"; marking it would say nothing.
     */
    fun status(kind: RecordKind, best: Long?, today: Long?): RecordStatus {
        if (!kind.high || best == null || today == null) return RecordStatus.None
        return when {
            today >= best -> RecordStatus.Passed
            today >= best * CloseFraction -> RecordStatus.Close
            else -> RecordStatus.None
        }
    }

    fun streaks(ledger: DayLedger, today: LocalDate, stepGoal: Int?, todaySteps: Int? = null): List<StreakRow> = buildList {
        val average = TodayLines.average(ledger, today) { it.screenOnMillis?.takeIf { v -> v > 0 } }
        val todayDay = ledger.day(today)
        if (average != null) {
            // Today is unfinished and can only go up, so the screen streak is counted over finished days.
            val met = { date: LocalDate -> ledger.day(date)?.screenOnMillis?.takeIf { it > 0 }?.let { it < average } }
            val run = run(ledger, today.minusDays(1), met)
            add(StreakRow(StreakKind.UnderAverageScreen, run.current, run.best, run.bestEnd, Math.round(average), todayDay?.screenOnMillis?.takeIf { it > 0 }))
        }
        if (stepGoal != null) {
            val steps = { date: LocalDate ->
                (if (date == today && todaySteps != null) todaySteps else ledger.day(date)?.steps)?.takeIf { it > 0 }?.let { it >= stepGoal }
            }
            // Steps only go up, so a goal reached today counts now; a goal not yet reached does not break the run.
            val last = if (steps(today) == true) today else today.minusDays(1)
            val run = run(ledger, last, steps)
            add(StreakRow(StreakKind.StepGoal, run.current, run.best, run.bestEnd, stepGoal.toLong(), todaySteps?.toLong()?.takeIf { it > 0 } ?: todayDay?.steps?.toLong()?.takeIf { it > 0 }))
        }
    }

    internal data class Run(val current: Int, val best: Int, val bestEnd: LocalDate?)

    /** Walks every calendar date up to [last]. A date with no answer (null) or a miss (false) ends the run, so a missing day breaks a streak. */
    internal fun run(ledger: DayLedger, last: LocalDate, met: (LocalDate) -> Boolean?): Run {
        val first = ledger.firstDate ?: return Run(0, 0, null)
        var count = 0
        var best = 0
        var bestEnd: LocalDate? = null
        var date = first
        while (!date.isAfter(last)) {
            if (met(date) == true) {
                count++
                // A tie goes to the later run, like the records.
                if (count >= best) { best = count; bestEnd = date }
            } else count = 0
            date = date.plusDays(1)
        }
        return Run(count, best, bestEnd)
    }

    /** One line for the Today page when a high record is close (the first such record in page order), else null. */
    fun todayLine(ledger: DayLedger, today: LocalDate, stepGoal: Int?, todaySteps: Int?): String? {
        val state = compute(ledger, today, stepGoal, todaySteps) as? RecordsState.Ready ?: return null
        val row = state.records.firstOrNull { it.status == RecordStatus.Close } ?: return null
        val what = if (row.kind == RecordKind.MostSteps) "step record" else "phone-free record"
        return "Close to your $what · ${format(row.kind, row.today!!, short = true)} of ${format(row.kind, row.value!!, short = true)}"
    }

    // --- Words ------------------------------------------------------------------------------------------------

    /** `21,430 steps`, `2 h 05 min`, `12 pickups`, `380.0 MB`. With [short] the steps and pickups lose their unit. */
    fun format(kind: RecordKind, value: Long, short: Boolean = false): String = when (kind) {
        RecordKind.MostSteps -> StepMath.count(value) + if (short) "" else " steps"
        RecordKind.LowestScreen, RecordKind.LongestFree -> RhythmMath.duration(value)
        RecordKind.FewestPickups -> value.toString() + if (short) "" else if (value == 1L) " pickup" else " pickups"
        RecordKind.LowestData -> PowerNetworkFormat.bytes(value)
    }

    fun dateText(date: LocalDate): String = RhythmView.dateLabelPlain(date)

    fun days(n: Int): String = if (n == 1) "1 day" else "$n days"

    fun streakLabel(row: StreakRow): String = when (row.kind) {
        StreakKind.UnderAverageScreen -> "Days under your 14-day average (${RhythmMath.duration(row.threshold)})"
        StreakKind.StepGoal -> "Days reaching your step goal (${StepMath.count(row.threshold)})"
    }

    /** What a record row shows, in one place for the page and TalkBack. */
    data class RowText(val label: String, val value: String, val date: String?, val beside: String, val mark: String?, val description: String)

    fun rowText(row: RecordRow): RowText {
        val value = row.value?.let { format(row.kind, it) } ?: "Not recorded yet"
        val date = row.date?.let(::dateText)
        val beside = row.today?.let { "Today " + format(row.kind, it, short = true) } ?: "Today: no data yet"
        val mark = when (row.status) {
            RecordStatus.Close -> "close"
            RecordStatus.Passed -> "passed today"
            RecordStatus.None -> null
        }
        val description = buildString {
            append(row.kind.label).append(": ").append(value)
            if (date != null) append(" on ").append(date)
            append(". ").append(beside)
            if (mark != null) append(". ").append(mark.replaceFirstChar { it.uppercase() })
        }
        return RowText(row.kind.label, value, date, beside, mark, description)
    }

    fun streakText(row: StreakRow): RowText {
        val label = streakLabel(row)
        val value = "Best " + days(row.best)
        val date = row.bestEnd?.takeIf { row.best > 0 }?.let { "ended " + dateText(it) }
        val beside = "Now " + days(row.current)
        val description = buildString {
            append(label).append(": ").append(value)
            if (date != null) append(", ").append(date)
            append(". ").append(beside)
        }
        return RowText(label, value, date, beside, null, description)
    }

    fun notEnoughText(state: RecordsState.NotEnough): Pair<String, String> =
        "${days(state.remaining)} to go" to "Records need ${state.need} days of history. ${state.have} so far."
}

/** Row height shared out of the panel: the five record rows fit without scrolling when the panel allows it. */
internal object RecordsLayout {
    /** The gap between rows: what is left of [availableDp] after the rows and cell insets, shared out and kept between [minGapDp] and [maxGapDp]. */
    fun gapDp(availableDp: Float, rows: Int, rowDp: Float, insetsDp: Float, minGapDp: Float, maxGapDp: Float): Float {
        if (rows <= 1) return maxGapDp
        val free = availableDp - insetsDp - rows * rowDp
        return (free / (rows - 1)).coerceIn(minGapDp, maxGapDp)
    }
}
