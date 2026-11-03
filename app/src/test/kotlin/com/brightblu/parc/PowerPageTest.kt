package com.brightblu.parc

import com.brightblu.parc.core.data.stats.StatsArchivePoint
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class PowerPageTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private fun at(hour: Int, minute: Int = 0, day: Int = 10) =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, utc).toInstant().toEpochMilli()
    private fun point(hour: Int, minute: Int, value: Int, day: Int = 10) = StatsArchivePoint(at(hour, minute, day), value.toDouble())

    @Test fun dotCountIsPercentOverFiveRounded() {
        assertEquals(0, PowerPage.filledDots(0))
        assertEquals(0, PowerPage.filledDots(1))
        assertEquals(0, PowerPage.filledDots(2))
        assertEquals(1, PowerPage.filledDots(3))
        assertEquals(10, PowerPage.filledDots(50))
        assertEquals(20, PowerPage.filledDots(99))
        assertEquals(20, PowerPage.filledDots(100))
        assertEquals(0, PowerPage.filledDots(null))
        assertEquals(20, PowerPage.filledDots(250))
        assertEquals(0, PowerPage.filledDots(-4))
    }

    @Test fun accentFromFifteenPercentDown() {
        assertTrue(PowerPage.isLow(15)); assertTrue(PowerPage.isLow(8)); assertTrue(PowerPage.isLow(0))
        assertFalse(PowerPage.isLow(16)); assertFalse(PowerPage.isLow(100)); assertFalse(PowerPage.isLow(null))
    }

    @Test fun barsAreTheLastEightLocalHoursWithTheCurrentOneLast() {
        val bars = PowerPage.hourBars(emptyList(), at(14, 20), utc)
        assertEquals(8, bars.size)
        assertEquals((7..14).toList(), bars.map { it.hourOfDay })
        assertEquals(listOf(false, false, false, false, false, false, false, true), bars.map { it.current })
    }

    @Test fun barIsTheNewestLevelInsideItsHour() {
        val points = listOf(point(12, 5, 80), point(12, 55, 78), point(13, 30, 74), point(14, 10, 70), point(14, 15, 69))
        val bars = PowerPage.hourBars(points, at(14, 20), utc)
        assertEquals(78, bars[5].percent)  // hour 12
        assertEquals(74, bars[6].percent)  // hour 13
        assertEquals(69, bars[7].percent)  // hour 14, now
        assertNull(bars[4].percent)        // hour 11 has no sample: a gap
    }

    @Test fun gapsStayGapsAndFewerThanEightHoursLeavesLeadingGaps() {
        val points = listOf(point(13, 40, 60), point(14, 0, 58))
        val bars = PowerPage.hourBars(points, at(14, 5), utc)
        assertEquals(List(6) { null } + listOf(60, 58), bars.map { it.percent })
        assertTrue(PowerPage.hasData(bars))
    }

    @Test fun samplesOlderThanEightHoursOrInTheFutureAreIgnored() {
        val points = listOf(point(5, 0, 90), point(15, 0, 10))
        val bars = PowerPage.hourBars(points, at(14, 20), utc)
        assertTrue(bars.all { it.percent == null })
        assertFalse(PowerPage.hasData(bars))
    }

    @Test fun noPointsIsTheEmptyState() {
        assertFalse(PowerPage.hasData(PowerPage.hourBars(emptyList(), at(9), utc)))
        assertEquals("Collecting data, come back in an hour", PowerPage.EmptyHistory)
    }

    @Test fun hoursWrapPastMidnight() {
        val bars = PowerPage.hourBars(listOf(point(23, 30, 50, day = 9), point(1, 10, 45)), at(2, 0), utc)
        assertEquals(listOf(19, 20, 21, 22, 23, 0, 1, 2), bars.map { it.hourOfDay })
        assertEquals(50, bars[4].percent)
        assertEquals(45, bars[6].percent)
    }

    @Test fun barHeightIsTheLevelAndLabelsMarkNow() {
        val bars = PowerPage.hourBars(listOf(point(14, 0, 8)), at(14, 5), utc)
        assertEquals(0.08f, PowerPage.barFraction(bars[7]), 0.0001f)
        assertEquals(0f, PowerPage.barFraction(bars[0]), 0f)
        assertEquals("now", PowerPage.hourLabel(bars[7]))
        assertEquals("07", PowerPage.hourLabel(bars[0]))
    }

    @Test fun chargingWords() {
        assertEquals("Charging", PowerPage.chargingState(true))
        assertEquals("Not charging", PowerPage.chargingState(false))
        assertEquals("Full in 1h 22m", PowerPage.fullIn(true, 4_920_000))
        assertEquals("Full in 42m", PowerPage.fullIn(true, 2_520_000))
        assertNull(PowerPage.fullIn(true, null))
        assertNull(PowerPage.fullIn(true, 0))
        assertNull(PowerPage.fullIn(false, 4_920_000))
    }

    @Test fun screenOnIsHoursAndMinutes() {
        assertEquals(2 to 22, PowerPage.hoursMinutes(8_520_000))
        assertEquals(0 to 0, PowerPage.hoursMinutes(0))
        assertEquals(0 to 59, PowerPage.hoursMinutes(3_599_999))
        assertNull(PowerPage.hoursMinutes(null))
        assertNull(PowerPage.hoursMinutes(-1))
    }

    @Test fun meterConstantsMatchTheCard() {
        assertEquals(20, PowerPage.MeterDots)
        assertEquals(8, PowerPage.BarCount)
        assertEquals(15, PowerPage.LowPercent)
    }
}
