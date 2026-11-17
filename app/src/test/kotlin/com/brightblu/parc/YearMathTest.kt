package com.brightblu.parc

import com.brightblu.parc.core.data.ledger.LedgerDay
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T190: the pure parts of the Year page: grid, day counts, intensity steps, countdowns, stored text. */
class YearMathTest {
    private val today = LocalDate.of(2026, 11, 10)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test fun gridCellsMapToDatesAndBack() {
        assertEquals(d(2026, 1, 1), YearGrid.dateAt(2026, 0, 0))
        assertEquals(d(2026, 12, 31), YearGrid.dateAt(2026, 11, 30))
        assertNull(YearGrid.dateAt(2026, 1, 28))      // 29 Feb 2026 does not exist
        assertEquals(d(2028, 2, 29), YearGrid.dateAt(2028, 1, 28))
        assertNull(YearGrid.dateAt(2026, 3, 30))      // 31 April
        assertNull(YearGrid.dateAt(2026, 12, 0))
        assertNull(YearGrid.dateAt(2026, 0, 31))
        assertEquals(10, YearGrid.row(d(2026, 11, 10)) - 0)
        assertEquals(9, YearGrid.column(d(2026, 11, 10)))
        var count = 0
        for (r in 0 until YearGrid.Rows) for (c in 0 until YearGrid.Columns) YearGrid.dateAt(2026, r, c)?.let { count++ }
        assertEquals(365, count)
    }

    @Test fun aTouchFindsTheDayUnderIt() {
        // A 310 x 120 grid: each cell is 10 x 10.
        assertEquals(d(2026, 1, 1), YearGrid.dateAtPoint(2026, 3f, 3f, 310f, 120f))
        assertEquals(d(2026, 11, 10), YearGrid.dateAtPoint(2026, 95f, 105f, 310f, 120f))
        assertNull(YearGrid.dateAtPoint(2026, 95f, 125f, 310f, 120f))
        assertNull(YearGrid.dateAtPoint(2026, -1f, 5f, 310f, 120f))
        assertNull(YearGrid.dateAtPoint(2026, 295f, 15f, 310f, 120f))  // 30 Feb
    }

    @Test fun dayCountsAndTheProgressLine() {
        assertEquals(365, YearMath.daysInYear(2026))
        assertEquals(366, YearMath.daysInYear(2028))
        assertEquals(365, YearMath.daysInYear(2100))
        assertEquals(366, YearMath.daysInYear(2000))
        assertEquals("Day 314 of 365 · 86 %", YearMath.progressLine(today))
        assertEquals("Day 1 of 365 · 0 %", YearMath.progressLine(d(2026, 1, 1)))
        assertEquals("Day 366 of 366 · 100 %", YearMath.progressLine(d(2028, 12, 31)))
        assertEquals("Day 60 of 366 · 16 %", YearMath.progressLine(d(2028, 2, 29)))
    }

    @Test fun intensityHasFourStepsRelativeToTheOwnRange() {
        val v = mapOf(d(2026, 1, 1) to 0.0, d(2026, 1, 2) to 24.0, d(2026, 1, 3) to 26.0, d(2026, 1, 4) to 51.0, d(2026, 1, 5) to 76.0, d(2026, 1, 6) to 100.0)
        val l = YearMath.levels(v)
        assertEquals(listOf(1, 1, 2, 3, 4, 4), v.keys.map { l.getValue(it) })
        assertTrue(l.values.all { it in 1..4 })
    }

    @Test fun intensityHandlesEmptyAndFlatData() {
        assertTrue(YearMath.levels(emptyMap()).isEmpty())
        assertEquals(mapOf(today to 2), YearMath.levels(mapOf(today to 5.0)))
        assertEquals(setOf(2), YearMath.levels(mapOf(today to 7.0, today.minusDays(1) to 7.0)).values.toSet())
    }

    @Test fun aMetricReadsOnlyWhatTheLedgerHas() {
        val day = LedgerDay(today, wifiBytes = 100, mobileBytes = 50, screenOnMillis = 60_000, steps = 5, pickups = 3)
        assertEquals(5.0, YearMetric.Steps.value(day)!!, 0.0)
        assertEquals(60_000.0, YearMetric.Screen.value(day)!!, 0.0)
        assertEquals(150.0, YearMetric.Data.value(day)!!, 0.0)
        assertEquals(3.0, YearMetric.Pickups.value(day)!!, 0.0)
        assertNull(YearMetric.None.value(day))
        assertNull(YearMetric.Steps.value(null))
        assertNull(YearMetric.Steps.value(LedgerDay(today, wifiBytes = 1)))
        assertNull(YearMetric.Data.value(LedgerDay(today, steps = 4)))
        assertEquals(100.0, YearMetric.Data.value(LedgerDay(today, wifiBytes = 100))!!, 0.0)
        assertEquals(0.0, YearMetric.Steps.value(LedgerDay(today, steps = 0))!!, 0.0)
    }

    @Test fun theMetricLabelCyclesThroughAllFive() {
        var m = YearMetric.None
        val seen = mutableListOf(m)
        repeat(5) { m = m.next(); seen += m }
        assertEquals(listOf(YearMetric.None, YearMetric.Steps, YearMetric.Screen, YearMetric.Data, YearMetric.Pickups, YearMetric.None), seen)
        assertEquals(YearMetric.Data, YearMetric.fromId("data"))
        assertEquals(YearMetric.None, YearMetric.fromId("junk"))
        assertEquals(YearMetric.None, YearMetric.fromId(null))
    }

    @Test fun theDayLineShowsOnlyWhatExists() {
        assertEquals("Tue 10 Nov 2026 · 4,250 steps · 2h 5m screen · 1.5 GB data · 12 pickups",
            YearMath.dayLine(today, LedgerDay(today, wifiBytes = 1_400_000_000, mobileBytes = 100_000_000, screenOnMillis = 125 * 60_000L, steps = 4250, pickups = 12)))
        assertEquals("Tue 10 Nov 2026 · 900 steps", YearMath.dayLine(today, LedgerDay(today, steps = 900)))
        assertEquals("Tue 10 Nov 2026 · Nothing recorded", YearMath.dayLine(today, null))
    }

    @Test fun daysToADate() {
        val exam = Countdown("Exam", d(2026, 12, 23))
        val rows = Countdowns.upcoming(listOf(exam), today)
        assertEquals(43L, rows.single().days)
        assertEquals("Exam · 43 days · 23 Dec", Countdowns.rowText(rows.single(), today))
    }

    @Test fun todayTomorrowAndAnotherYear() {
        assertEquals("Party · Today · 10 Nov", Countdowns.rowText(Countdowns.upcoming(listOf(Countdown("Party", today)), today).single(), today))
        assertEquals("Party · 1 day · 11 Nov", Countdowns.rowText(Countdowns.upcoming(listOf(Countdown("Party", d(2026, 11, 11))), today).single(), today))
        assertEquals("Trip · 60 days · 9 Jan 2027", Countdowns.rowText(Countdowns.upcoming(listOf(Countdown("Trip", d(2027, 1, 9))), today).single(), today))
    }

    @Test fun aPassedOneTimeDateLeavesTheListAndCanBeCleared() {
        val old = Countdown("Old", d(2026, 3, 1))
        val soon = Countdown("Soon", d(2026, 11, 12))
        val all = listOf(old, soon)
        assertEquals(listOf(soon), Countdowns.upcoming(all, today).map { it.countdown })
        assertEquals(listOf(old), Countdowns.passed(all, today))
        assertEquals(listOf(soon), Countdowns.withoutPassed(all, today))
        assertEquals("Old · 1 Mar 2026", Countdowns.passedText(old))
        // a date that is today has not passed
        assertTrue(Countdowns.passed(listOf(Countdown("Now", today)), today).isEmpty())
    }

    @Test fun aYearlyDateRepeatsAndNeverPasses() {
        val birthday = Countdown("Birthday", d(2000, 3, 4), yearly = true)
        assertEquals(d(2027, 3, 4), birthday.next(today))
        assertEquals(114L, Countdowns.upcoming(listOf(birthday), today).single().days)
        assertTrue(Countdowns.passed(listOf(birthday), today).isEmpty())
        assertEquals(d(2026, 11, 10), Countdown("Today", d(2020, 11, 10), true).next(today))
        // a yearly date that has not started yet first lands on its own date
        assertEquals(d(2028, 5, 5), Countdown("Later", d(2028, 5, 5), true).next(today))
    }

    @Test fun aYearlyTwentyNinthOfFebruaryFallsOnTheTwentyEighthInOrdinaryYears() {
        val leap = Countdown("Leap", d(2024, 2, 29), yearly = true)
        assertEquals(d(2027, 2, 28), leap.next(today))
        assertEquals(d(2026, 2, 28), leap.occurrenceIn(2026))
        assertEquals(d(2028, 2, 29), leap.occurrenceIn(2028))
        assertEquals(d(2028, 2, 29), leap.next(d(2028, 1, 1)))
        assertEquals(d(2028, 2, 29), leap.next(d(2028, 2, 29)))
        assertNull(leap.occurrenceIn(2023))
    }

    @Test fun datesAreMarkedInTheirYear() {
        val all = listOf(Countdown("Exam", d(2026, 12, 23)), Countdown("Old", d(2026, 3, 1)), Countdown("Next", d(2027, 1, 9)),
            Countdown("Birthday", d(2000, 3, 4), true))
        assertEquals(setOf(d(2026, 12, 23), d(2026, 3, 1), d(2026, 3, 4)), Countdowns.marks(all, 2026))
        assertEquals(setOf(d(2027, 1, 9), d(2027, 3, 4)), Countdowns.marks(all, 2027))
        assertEquals(emptySet<LocalDate>(), Countdowns.marks(emptyList(), 2026))
    }

    @Test fun theNearestComesFirstAndTiesGoByName() {
        val all = listOf(Countdown("b", d(2026, 12, 1)), Countdown("a", d(2026, 12, 1)), Countdown("c", d(2026, 11, 20)))
        assertEquals(listOf("c", "a", "b"), Countdowns.upcoming(all, today).map { it.countdown.name })
    }

    @Test fun typedDates() {
        assertEquals(d(2026, 12, 23), Countdowns.parseDate("23/12/2026"))
        assertEquals(d(2026, 12, 23), Countdowns.parseDate("2026-12-23"))
        assertEquals(d(2026, 1, 5), Countdowns.parseDate(" 5.1.2026 "))
        assertEquals(d(2026, 1, 5), Countdowns.parseDate("2026/1/5"))
        assertNull(Countdowns.parseDate("31/02/2026"))
        assertNull(Countdowns.parseDate("23/12/26"))
        assertNull(Countdowns.parseDate("tomorrow"))
        assertNull(Countdowns.parseDate(""))
        assertNull(Countdowns.parseDate("1/2"))
        assertEquals("05/01/2026", Countdowns.dateField(d(2026, 1, 5)))
        assertEquals(d(2026, 1, 5), Countdowns.parseDate(Countdowns.dateField(d(2026, 1, 5))))
    }

    @Test fun storedTextRoundTripsAndOldOrBrokenTextIsSafe() {
        val all = listOf(Countdown("Exam", d(2026, 12, 23)), Countdown("Mum | dad", d(2000, 3, 4), true))
        assertEquals(all, Countdowns.decode(Countdowns.encode(all)))
        assertTrue(Countdowns.decode(null).isEmpty())
        assertTrue(Countdowns.decode("").isEmpty())
        assertTrue(Countdowns.decode("garbage\n|||\n2026-13-40|0|x\n2026-01-01|0|").isEmpty())
        assertEquals(listOf(Countdown("Ok", d(2026, 1, 1))), Countdowns.decode("junk\n2026-01-01|0|Ok\n2026-02-30|1|Bad"))
        assertEquals("a b", Countdowns.cleanName("  a \n  b  "))
        assertEquals(Countdowns.MaxNameLength, Countdowns.cleanName("x".repeat(100)).length)
        val many = (1..80).map { Countdown("n$it", d(2026, 1, 1)) }
        assertEquals(Countdowns.MaxCount, Countdowns.decode(Countdowns.encode(many)).size)
        assertTrue(Countdowns.encode(many).length < 8_192)
    }
}
