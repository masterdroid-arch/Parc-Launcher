package com.brightblu.parc.core.data.steps

import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.LedgerMath
import com.brightblu.parc.core.data.ledger.LedgerPeriod
import com.brightblu.parc.core.data.ledger.PeriodLabels
import com.brightblu.parc.core.data.ledger.PeriodSummary
import java.time.LocalDate
import java.util.Locale

/**
 * One reading of the phone's step counter (T187). [counter] counts up since boot; [elapsedMillis] is
 * `SystemClock.elapsedRealtime()` at the moment of the reading. Both reset at a reboot, which is how
 * a reboot is told apart from a pause.
 */
data class StepReading(val counter: Long, val elapsedMillis: Long) {
    fun encode(): String = "$counter;$elapsedMillis"

    companion object {
        /** A stored baseline; anything unreadable, negative or empty is no baseline. */
        fun decode(raw: String?): StepReading? {
            val parts = raw?.split(';') ?: return null
            if (parts.size != 2) return null
            val counter = parts[0].toLongOrNull() ?: return null
            val elapsed = parts[1].toLongOrNull() ?: return null
            return if (counter < 0L || elapsed < 0L) null else StepReading(counter, elapsed)
        }
    }
}

/** The two things the user sets: stride length and the optional daily goal ([goal] null means off). */
data class StepOptions(val strideCm: Int = DefaultStrideCm, val goal: Int? = DefaultGoal) {
    fun encode(): String = "$strideCm,${goal ?: 0}"

    fun withStride(cm: Int) = copy(strideCm = cm.coerceIn(MinStrideCm, MaxStrideCm))
    fun withGoal(steps: Int?) = copy(goal = steps?.coerceIn(MinGoal, MaxGoal))

    companion object {
        const val DefaultStrideCm = 75
        const val MinStrideCm = 30
        const val MaxStrideCm = 150
        const val StrideStepCm = 5
        const val DefaultGoal = 8_000
        const val MinGoal = 1_000
        const val MaxGoal = 50_000
        const val GoalStep = 500

        /** Old, empty, garbled or out-of-range text reads as the defaults for that part. */
        fun decode(raw: String?): StepOptions {
            val parts = raw?.split(',') ?: return StepOptions()
            val stride = parts.getOrNull(0)?.trim()?.toIntOrNull()
                ?.takeIf { it in MinStrideCm..MaxStrideCm } ?: DefaultStrideCm
            val goal = when (val g = parts.getOrNull(1)?.trim()?.toIntOrNull()) {
                null -> DefaultGoal
                0 -> null
                else -> if (g in MinGoal..MaxGoal) g else DefaultGoal
            }
            return StepOptions(stride, goal)
        }
    }
}

/** Steps not yet written to the ledger, for the date they were walked on. */
data class StepPending(val date: LocalDate, val steps: Int)

/** What a period of the ledger adds up to for steps. [average] is per recorded day. */
data class StepSummary(
    val period: LedgerPeriod,
    val label: String,
    val days: Int,
    val daysWithData: Int,
    val total: Long,
) {
    val average: Long? get() = if (daysWithData > 0) Math.round(total.toDouble() / daysWithData) else null
}

/** One bar of the 7-day row. [steps] is null for a day nobody recorded. */
data class StepBar(val date: LocalDate, val steps: Int?, val isToday: Boolean)

/** Framework-free step maths: counting from the counter, distance, sums and labels. */
object StepMath {
    /**
     * Steps to add for [now] given the last reading [last]. No last reading yet means a first
     * reading: nothing is credited (the counter holds weeks of walking from before Parc looked). A
     * counter that went down, or an elapsed time that went down, is a reboot: the counter restarted
     * at zero after [last], so everything it now shows was walked since then and is credited whole.
     * Steps walked between [last] and the shutdown cannot be known and are lost. Never negative.
     */
    fun delta(last: StepReading?, now: StepReading): Long {
        if (last == null) return 0L
        val rebooted = now.counter < last.counter || now.elapsedMillis < last.elapsedMillis
        return if (rebooted) now.counter.coerceAtLeast(0L) else now.counter - last.counter
    }

    /** What the page shows for today: the saved steps plus those still waiting to be saved today. */
    fun todayTotal(saved: Int?, pending: StepPending?, today: LocalDate): Int? {
        val extra = pending?.takeIf { it.date == today }?.steps
        return if (saved == null && extra == null) null else (saved ?: 0) + (extra ?: 0)
    }

    /** Metres walked, rounded to whole metres. */
    fun distanceMeters(steps: Long, strideCm: Int): Long = Math.round(steps * strideCm / 100.0)

    /** `850 m` below one kilometre, otherwise `3.7 km` (one decimal, `.` always). */
    fun distanceText(meters: Long): String =
        if (meters < 1_000L) "$meters m" else String.format(Locale.ROOT, "%.1f km", meters / 1_000.0)

    /** `12,345` with a comma between thousands, whatever the phone's locale. */
    fun count(steps: Long): String = String.format(Locale.ROOT, "%,d", steps)

    /** How far along the goal is, 0..1; null when there is no goal. */
    fun goalFraction(steps: Int?, goal: Int?): Float? =
        if (goal == null || goal <= 0) null else ((steps ?: 0).toFloat() / goal).coerceIn(0f, 1f)

    /** The seven days ending [today], oldest first; a day the ledger does not have has null steps. */
    fun week(ledger: DayLedger, today: LocalDate, pending: StepPending? = null): List<StepBar> =
        (6 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val saved = ledger.day(date)?.steps
            val steps = if (date == today) todayTotal(saved, pending, today) else saved
            StepBar(date, steps, back == 0)
        }

    /**
     * Total and daily average of [period] ending [today]. Year and All start at the first day with
     * steps (not at the first ledger day, which usually predates the step counter), so days before
     * steps were recorded are not called missing. The label is the shared honest one, for example
     * `All time · since 12 Aug 2026` or `7 days · 5 of 7 days`.
     */
    fun summary(ledger: DayLedger, period: LedgerPeriod, today: LocalDate, pending: StepPending? = null): StepSummary {
        val stepDays = ledger.days.values.filter { it.steps != null }.map { it.date }
        val firstSteps = (stepDays + listOfNotNull(pending?.date)).minOrNull()
        var (from, to) = LedgerMath.window(period, today)
        var sinceFirst = false
        if (period == LedgerPeriod.Year || period == LedgerPeriod.All) {
            val start = firstSteps ?: today
            if (firstSteps == null || start.isAfter(from)) {
                from = start
                sinceFirst = true
            }
            if (period == LedgerPeriod.All) sinceFirst = true
        }
        if (to.isBefore(from)) from = to
        val counted = ledger.range(from, to).filter { it.steps != null }
        val extra = pending?.takeIf { it.date in from..to }
        val savedOnPendingDate = extra?.let { p -> counted.any { it.date == p.date } } ?: false
        var total = counted.sumOf { (it.steps ?: 0).toLong() }
        var withData = counted.size
        if (extra != null) {
            total += extra.steps
            if (!savedOnPendingDate) withData += 1
        }
        val length = (to.toEpochDay() - from.toEpochDay() + 1).toInt()
        val labelBase = PeriodSummary(
            period = period, from = from, to = to, days = length, wifiBytes = 0L, mobileBytes = 0L,
            screenOnMillis = 0L, networkDays = 0, screenDays = 0, firstDate = firstSteps, sinceFirst = sinceFirst,
        )
        return StepSummary(period, PeriodLabels.of(labelBase, withData), length, withData, total)
    }
}
