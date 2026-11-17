package com.brightblu.parc

import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.LedgerDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsMathTest {
    private val today = LocalDate.of(2026, 11, 10)
    private fun d(back: Int) = today.minusDays(back.toLong())
    private fun ledger(vararg days: LedgerDay) = days.fold(DayLedger.Empty) { l, day -> l.withDay(day) }
    private fun week(steps: (Int) -> Int? = { 1000 }, screen: (Int) -> Long? = { 100L * 60_000L }) =
        (1..14).map { LedgerDay(d(it), steps = steps(it), screenOnMillis = screen(it), pickups = 20 + it, phoneFreeMillis = 60_000L * (30 + it),
            wifiBytes = 1_000_000L * it) }.toTypedArray()
    private fun ready(l: DayLedger, goal: Int? = 8000, steps: Int? = null) = RecordsMath.compute(l, today, goal, steps) as RecordsState.Ready
    private fun RecordsState.Ready.of(kind: RecordKind) = records.first { it.kind == kind }

    @Test fun firstWeekSaysHowManyMoreDaysItNeeds() {
        val six = ledger(*(1..6).map { LedgerDay(d(it), steps = 1000) }.toTypedArray())
        val state = RecordsMath.compute(six, today, 8000) as RecordsState.NotEnough
        assertEquals(6, state.have)
        assertEquals(1, state.remaining)
        assertEquals("1 day to go", RecordsMath.notEnoughText(state).first)
        assertTrue(RecordsMath.compute(DayLedger.Empty, today, null) is RecordsState.NotEnough)
        assertTrue(RecordsMath.compute(ledger(*(1..7).map { LedgerDay(d(it), steps = 1000) }.toTypedArray()), today, null) is RecordsState.Ready)
    }

    @Test fun todayAndEmptyDaysDoNotCountTowardTheMinimum() {
        val l = ledger(*(1..6).map { LedgerDay(d(it), steps = 1000) }.toTypedArray(), LedgerDay(today, steps = 5), LedgerDay(d(8)))
        assertEquals(6, RecordsMath.daysAvailable(l, today))
    }

    @Test fun bestValuesAndDatesAndTodayBesideThem() {
        val l = ledger(*week(steps = { if (it == 5) 12_000 else 1000 }, screen = { if (it == 3) 20L * 60_000L else 100L * 60_000L }),
            LedgerDay(today, steps = 300, screenOnMillis = 5L * 60_000L, pickups = 3, phoneFreeMillis = 600_000L))
        val s = ready(l)
        assertEquals(12_000L, s.of(RecordKind.MostSteps).value); assertEquals(d(5), s.of(RecordKind.MostSteps).date); assertEquals(300L, s.of(RecordKind.MostSteps).today)
        assertEquals(20L * 60_000L, s.of(RecordKind.LowestScreen).value); assertEquals(d(3), s.of(RecordKind.LowestScreen).date)
        assertEquals(5L * 60_000L, s.of(RecordKind.LowestScreen).today)
        assertEquals(21L, s.of(RecordKind.FewestPickups).value); assertEquals(d(1), s.of(RecordKind.FewestPickups).date); assertEquals(3L, s.of(RecordKind.FewestPickups).today)
        assertEquals(1_000_000L, s.of(RecordKind.LowestData).value); assertEquals(d(1), s.of(RecordKind.LowestData).date)
        assertEquals(44L * 60_000L, s.of(RecordKind.LongestFree).value); assertEquals(d(14), s.of(RecordKind.LongestFree).date)
        assertEquals(600_000L, s.of(RecordKind.LongestFree).today)
    }

    @Test fun tiesGoToTheMostRecentDay() {
        val l = ledger(*week(steps = { if (it == 4 || it == 9) 5000 else 1000 }, screen = { if (it == 4 || it == 9) 10L * 60_000L else 100L * 60_000L }))
        val s = ready(l)
        assertEquals(d(4), s.of(RecordKind.MostSteps).date)
        assertEquals(d(4), s.of(RecordKind.LowestScreen).date)
    }

    @Test fun daysWithoutDataOrWithZeroNeverBecomeARecord() {
        // A zero screen time, zero pickups or zero data is Android answering nothing, not a record low.
        val l = ledger(*week(screen = { if (it == 2) 0L else if (it == 6) null else 100L * 60_000L }),
            LedgerDay(d(20), pickups = 0, wifiBytes = 0L, mobileBytes = 0L))
        val s = ready(l)
        assertEquals(100L * 60_000L, s.of(RecordKind.LowestScreen).value)
        assertEquals(21L, s.of(RecordKind.FewestPickups).value)
        assertEquals(1_000_000L, s.of(RecordKind.LowestData).value)
    }

    @Test fun aMetricNobodyRecordedHasNoRecordInsteadOfZero() {
        val l = ledger(*(1..8).map { LedgerDay(d(it), steps = 2000) }.toTypedArray())
        val s = ready(l)
        assertNull(s.of(RecordKind.FewestPickups).value)
        assertEquals("Not recorded yet", RecordsMath.rowText(s.of(RecordKind.FewestPickups)).value)
    }

    @Test fun closeIsMarkedOnlyForRecordsThatRiseDuringTheDay() {
        val l = ledger(*week(steps = { if (it == 2) 10_000 else 1000 }), LedgerDay(today, steps = 9_100, screenOnMillis = 99L * 60_000L))
        val s = ready(l, steps = 9_100)
        assertEquals(RecordStatus.Close, s.of(RecordKind.MostSteps).status)
        assertEquals(RecordStatus.None, s.of(RecordKind.LowestScreen).status)
        assertEquals(RecordStatus.None, RecordsMath.status(RecordKind.MostSteps, 10_000, 8_999))
        assertEquals(RecordStatus.Close, RecordsMath.status(RecordKind.MostSteps, 10_000, 9_000))
        assertEquals(RecordStatus.Close, RecordsMath.status(RecordKind.LongestFree, 1000, 999))
        assertEquals(RecordStatus.Passed, RecordsMath.status(RecordKind.MostSteps, 10_000, 10_000))
        assertEquals(RecordStatus.None, RecordsMath.status(RecordKind.MostSteps, null, 5))
        assertEquals(RecordStatus.None, RecordsMath.status(RecordKind.MostSteps, 10_000, null))
    }

    @Test fun theLiveStepTotalReplacesTheLedgerNumberForToday() {
        val l = ledger(*week(steps = { if (it == 2) 10_000 else 1000 }), LedgerDay(today, steps = 100))
        assertEquals(9_500L, ready(l, steps = 9_500).of(RecordKind.MostSteps).today)
        assertEquals(100L, ready(l).of(RecordKind.MostSteps).today)
    }

    @Test fun stepGoalStreakCountsDaysInARowAndAMissingDayBreaksIt() {
        // Days 1-3 met the goal, day 4 has no ledger entry at all, days 5-7 met again, the rest missed.
        val met = setOf(1, 2, 3, 5, 6, 7)
        val days = (1..14).filter { it != 4 }.map { LedgerDay(d(it), steps = if (it in met) 9000 else 3000, screenOnMillis = 6_000_000L) }
        val streak = ready(ledger(*days.toTypedArray())).streaks.first { it.kind == StreakKind.StepGoal }
        assertEquals(3, streak.current)
        assertEquals(3, streak.best)
        assertEquals(d(1), streak.bestEnd) // the tie goes to the later run
    }

    @Test fun aDayWithNoStepsRecordedBreaksTheStreakLikeAMiss() {
        val days = (1..10).map { LedgerDay(d(it), steps = if (it == 3) null else 9000, pickups = 5) }
        val streak = RecordsMath.streaks(ledger(*days.toTypedArray()), today, 8000).first()
        assertEquals(2, streak.current)
        assertEquals(7, streak.best)
        assertEquals(d(4), streak.bestEnd)
    }

    @Test fun todayCountsForStepsOnlyOnceTheGoalIsReached() {
        val days = (1..10).map { LedgerDay(d(it), steps = 9000) }
        val l = ledger(*days.toTypedArray(), LedgerDay(today, steps = 2000))
        assertEquals(10, RecordsMath.streaks(l, today, 8000, 2000).first().current)
        assertEquals(11, RecordsMath.streaks(l, today, 8000, 8000).first().current)
    }

    @Test fun noStepGoalMeansNoStepStreak() {
        assertTrue(RecordsMath.streaks(ledger(*week()), today, null).none { it.kind == StreakKind.StepGoal })
    }

    @Test fun screenStreakIsDaysStrictlyUnderTheFourteenDayAverage() {
        // The previous 14 days: seven at 60 min and seven at 140 min, so the average is 100 min.
        val l = ledger(*(1..14).map { LedgerDay(d(it), screenOnMillis = (if (it <= 3 || it in 8..11) 60L else 140L) * 60_000L) }.toTypedArray(),
            LedgerDay(today, screenOnMillis = 500L * 60_000L))
        val streak = RecordsMath.streaks(l, today, null).single()
        assertEquals(StreakKind.UnderAverageScreen, streak.kind)
        assertEquals(3, streak.current) // today is unfinished and ignored
        assertEquals(4, streak.best)
        assertEquals(d(8), streak.bestEnd)
        assertEquals(500L * 60_000L, streak.today)
    }

    @Test fun screenStreakBreaksAcrossAMissingDayAndEqualToAverageIsNotUnder() {
        val l = ledger(*(1..14).filter { it != 2 }.map { LedgerDay(d(it), screenOnMillis = (if (it == 1) 10L else 100L) * 60_000L) }.toTypedArray())
        assertEquals(1, RecordsMath.streaks(l, today, null).single().current)
        val flat = ledger(*(1..14).map { LedgerDay(d(it), screenOnMillis = 6_000_000L) }.toTypedArray())
        assertEquals(0, RecordsMath.streaks(flat, today, null).single().best)
    }

    @Test fun screenStreakNeedsSevenDaysOfScreenTime() {
        assertTrue(RecordsMath.streaks(ledger(*(1..6).map { LedgerDay(d(it), screenOnMillis = 1000) }.toTypedArray()), today, null).isEmpty())
    }

    @Test fun todayLineOnlyWhenAHighRecordIsCloseAndNeverInTheFirstWeek() {
        val base = week(steps = { if (it == 2) 10_000 else 1000 })
        assertNull(RecordsMath.todayLine(ledger(*base), today, 8000, 5000))
        assertEquals("Close to your step record · 9,500 of 10,000", RecordsMath.todayLine(ledger(*base), today, 8000, 9_500))
        assertNull(RecordsMath.todayLine(ledger(*base), today, 8000, 10_500))
        val first = ledger(*(1..3).map { LedgerDay(d(it), steps = 10_000) }.toTypedArray())
        assertNull(RecordsMath.todayLine(first, today, 8000, 9_900))
    }

    @Test fun phoneFreeCloseLineWhenStepsAreFar() {
        val l = ledger(*week(), LedgerDay(today, phoneFreeMillis = 60_000L * 40))
        // The best phone-free day is 44 min (30 + 14); today's 40 min is within 10 percent.
        assertEquals("Close to your phone-free record · 40 min of 44 min", RecordsMath.todayLine(l, today, 8000, null))
    }

    @Test fun todayLineComesAfterStepsAndOnlyWhenTheRecordsPageIsVisible() {
        val l = ledger(*week(steps = { if (it == 2) 10_000 else 1000 }))
        val base = TodayInput(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC, setOf("steps", "records", "sky"), stepsAccess = true,
            steps = 9_500, stepGoal = 8000, ledger = l)
        assertEquals(listOf("steps", "records"), TodayLines.ordered(base).map { it.page })
        assertEquals(listOf("steps"), TodayLines.ordered(base.copy(visible = setOf("steps", "sky"))).map { it.page })
    }

    @Test fun wordsLabelEveryValue() {
        val row = RecordRow(RecordKind.MostSteps, 21_430, d(5), 4_250, RecordStatus.Close)
        val t = RecordsMath.rowText(row)
        assertEquals("Most steps in a day", t.label)
        assertEquals("21,430 steps", t.value)
        assertEquals("Today 4,250", t.beside)
        assertEquals("close", t.mark)
        assertTrue(t.description.startsWith("Most steps in a day: 21,430 steps on "))
        assertEquals("Today: no data yet", RecordsMath.rowText(row.copy(today = null)).beside)
        assertEquals("1 pickup", RecordsMath.format(RecordKind.FewestPickups, 1))
        val s = RecordsMath.streakText(StreakRow(StreakKind.StepGoal, 2, 9, d(3), 8000, null))
        assertEquals("Days reaching your step goal (8,000)", s.label)
        assertEquals("Best 9 days", s.value)
        assertEquals("Now 2 days", s.beside)
        assertNotNull(s.date)
    }

    @Test fun rowGapIsSharedOutOfThePanelAndClamped() {
        assertEquals(12f, RecordsLayout.gapDp(400f, 5, 40f, 32f, 2f, 12f), 0f)
        assertEquals(2f, RecordsLayout.gapDp(150f, 5, 40f, 32f, 2f, 12f), 0f)
        assertEquals(7f, RecordsLayout.gapDp(260f, 5, 40f, 32f, 2f, 12f), 0f)
    }
}
