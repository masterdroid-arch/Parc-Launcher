package com.brightblu.parc.core.data.steps

import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.LedgerDay
import com.brightblu.parc.core.data.ledger.LedgerPeriod
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T187: the pure step maths. */
class StepMathTest {
    private fun r(counter: Long, elapsed: Long = 1_000L) = StepReading(counter, elapsed)
    private val today = LocalDate.of(2026, 11, 10)

    private fun ledger(vararg days: Pair<Int, Int?>): DayLedger = days.fold(DayLedger.Empty) { l, (back, steps) ->
        l.withDay(LedgerDay(today.minusDays(back.toLong()), steps = steps, screenOnMillis = if (steps == null) 5L else null))
    }

    @Test fun firstReadingCreditsNothing() {
        assertEquals(0L, StepMath.delta(null, r(52_000)))
    }

    @Test fun normalReadingCreditsTheDifference() {
        assertEquals(120L, StepMath.delta(r(1_000, 100), r(1_120, 500)))
        assertEquals(0L, StepMath.delta(r(1_000, 100), r(1_000, 500)))
    }

    @Test fun lowerCounterIsARebootAndCreditsTheWholeNewValue() {
        assertEquals(35L, StepMath.delta(r(9_000, 5_000_000), r(35, 60_000)))
    }

    @Test fun rebootIsAlsoSeenWhenTheCounterIsHigherButTheUptimeRestarted() {
        // Walked more since boot than before the reboot: the counter alone would hide it.
        assertEquals(12_000L, StepMath.delta(r(9_000, 5_000_000), r(12_000, 3_600_000)))
    }

    @Test fun deltaIsNeverNegative() {
        assertTrue(StepMath.delta(r(10, 5), r(0, 1)) >= 0L)
    }

    @Test fun readingEncodingRoundTripsAndRejectsGarbage() {
        assertEquals(r(42, 99), StepReading.decode(r(42, 99).encode()))
        assertNull(StepReading.decode(null))
        assertNull(StepReading.decode(""))
        assertNull(StepReading.decode("abc;1"))
        assertNull(StepReading.decode("-1;5"))
        assertNull(StepReading.decode("1;2;3"))
    }

    @Test fun distanceUsesTheStride() {
        assertEquals(7_500L, StepMath.distanceMeters(10_000, 75))
        assertEquals(0L, StepMath.distanceMeters(0, 75))
        assertEquals(8_000L, StepMath.distanceMeters(10_000, 80))
        assertEquals("850 m", StepMath.distanceText(850))
        assertEquals("7.5 km", StepMath.distanceText(7_500))
        assertEquals("1.0 km", StepMath.distanceText(1_000))
    }

    @Test fun countsHaveThousandsSeparators() {
        assertEquals("12,345", StepMath.count(12_345))
        assertEquals("999", StepMath.count(999))
    }

    @Test fun goalFraction() {
        assertNull(StepMath.goalFraction(100, null))
        assertEquals(0.5f, StepMath.goalFraction(4_000, 8_000)!!, 0.0001f)
        assertEquals(1f, StepMath.goalFraction(20_000, 8_000)!!, 0.0001f)
        assertEquals(0f, StepMath.goalFraction(null, 8_000)!!, 0.0001f)
    }

    @Test fun optionsDecodeOldEmptyAndBrokenText() {
        assertEquals(StepOptions(), StepOptions.decode(null))
        assertEquals(StepOptions(), StepOptions.decode(""))
        assertEquals(StepOptions(), StepOptions.decode("nonsense"))
        assertEquals(StepOptions(80, 10_000), StepOptions.decode("80,10000"))
        assertEquals(StepOptions(80, null), StepOptions.decode("80,0"))
        assertEquals(StepOptions(StepOptions.DefaultStrideCm, 6_000), StepOptions.decode("5,6000"))
        assertEquals(StepOptions(70, StepOptions.DefaultGoal), StepOptions.decode("70,12"))
        assertEquals(StepOptions(75, 8_000), StepOptions.decode("75"))
    }

    @Test fun optionsRoundTripAndClamp() {
        val o = StepOptions(90, null)
        assertEquals(o, StepOptions.decode(o.encode()))
        assertEquals(StepOptions.MaxStrideCm, StepOptions().withStride(500).strideCm)
        assertEquals(StepOptions.MinGoal, StepOptions().withGoal(1).goal)
        assertNull(StepOptions().withGoal(null).goal)
    }

    @Test fun todayTotalAddsPendingOnlyForToday() {
        assertNull(StepMath.todayTotal(null, null, today))
        assertEquals(100, StepMath.todayTotal(100, null, today))
        assertEquals(130, StepMath.todayTotal(100, StepPending(today, 30), today))
        assertEquals(30, StepMath.todayTotal(null, StepPending(today, 30), today))
        assertEquals(100, StepMath.todayTotal(100, StepPending(today.minusDays(1), 30), today))
    }

    @Test fun weekHasSevenDaysOldestFirstWithGaps() {
        val week = StepMath.week(ledger(0 to 300, 1 to 8_000, 3 to 5_000), today)
        assertEquals(7, week.size)
        assertEquals(today.minusDays(6), week.first().date)
        assertEquals(today, week.last().date)
        assertTrue(week.last().isToday)
        assertEquals(listOf(null, null, null, 5_000, null, 8_000, 300), week.map { it.steps })
    }

    @Test fun weekCountsPendingStepsOnToday() {
        val week = StepMath.week(ledger(0 to 300), today, StepPending(today, 50))
        assertEquals(350, week.last().steps)
    }

    @Test fun summaryOfTodayAndSevenDays() {
        val l = ledger(0 to 1_000, 1 to 2_000, 2 to 3_000, 9 to 100)
        val one = StepMath.summary(l, LedgerPeriod.Today, today)
        assertEquals(1_000L, one.total)
        assertEquals("Today", one.label)
        val week = StepMath.summary(l, LedgerPeriod.Week, today)
        assertEquals(6_000L, week.total)
        assertEquals(3, week.daysWithData)
        assertEquals(2_000L, week.average)
        assertEquals("7 days · 3 of 7 days", week.label)
    }

    @Test fun summaryIgnoresDaysWithoutStepsAndStartsAtTheFirstStepDay() {
        // A network-only day long ago must not stretch "All time" back before steps existed.
        val l = ledger(60 to null, 4 to 4_000, 3 to 6_000)
        val all = StepMath.summary(l, LedgerPeriod.All, today)
        assertEquals(10_000L, all.total)
        assertEquals(2, all.daysWithData)
        assertEquals(5_000L, all.average)
        assertEquals(today.minusDays(4).let { "All time · since ${it.dayOfMonth} Nov 2026 · 2 of 5 days" }, all.label)
        val year = StepMath.summary(l, LedgerPeriod.Year, today)
        assertEquals(10_000L, year.total)
    }

    @Test fun summaryWithNoStepsAtAllIsEmptyAndHasNoAverage() {
        val s = StepMath.summary(DayLedger.Empty, LedgerPeriod.All, today)
        assertEquals(0L, s.total)
        assertEquals(0, s.daysWithData)
        assertNull(s.average)
    }

    @Test fun summaryIncludesPendingStepsWithoutDoubleCounting() {
        val l = ledger(0 to 1_000)
        assertEquals(1_040L, StepMath.summary(l, LedgerPeriod.Today, today, StepPending(today, 40)).total)
        val fresh = StepMath.summary(DayLedger.Empty, LedgerPeriod.Week, today, StepPending(today, 40))
        assertEquals(40L, fresh.total)
        assertEquals(1, fresh.daysWithData)
    }
}
