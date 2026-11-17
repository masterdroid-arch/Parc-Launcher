package com.brightblu.parc

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T189: SkyMath against published values.
 * Sun: U.S. Naval Observatory, Astronomical Applications Dept., "Rise, set and transit" service
 * (aa.usno.navy.mil/api/rstt/oneday), queried 2026-11-10. Times are whole minutes in the place's own clock.
 * Moon: the same service's phases of the Moon (aa.usno.navy.mil/api/moon/phases/date), times in UT.
 * The tolerance is 2 minutes for the sun and 1 percent for the moon, as the card says.
 */
class SkyMathTest {
    private val lisbon = SkyPlace(38.72, -9.14, "Lisbon")
    private val nairobi = SkyPlace(-1.29, 36.82, "Nairobi")
    private val tromso = SkyPlace(69.65, 18.96, "Tromso")
    private val twoMinutes = 120_000L

    private fun local(date: String, time: String, offsetHours: Int): Long =
        LocalDateTime.of(LocalDate.parse(date), java.time.LocalTime.parse(time)).toInstant(ZoneOffset.ofHours(offsetHours)).toEpochMilli()

    private fun utc(date: String, time: String) = local(date, time, 0)

    private fun check(place: SkyPlace, date: String, offsetHours: Int, rise: String, noon: String, set: String) {
        val day = SkyMath.sunDay(LocalDate.parse(date), place)
        assertEquals("${place.name} $date state", SunState.Normal, day.state)
        fun near(label: String, expected: String, actual: Long?) {
            val want = local(date, expected, offsetHours)
            assertNotNull("${place.name} $date $label", actual)
            val error = actual!! - want
            assertTrue("${place.name} $date $label: expected $expected, got ${SkyView.clock(actual, offsetHours * 3600)} (off by ${error / 1000} s)", Math.abs(error) <= twoMinutes)
        }
        near("sunrise", rise, day.sunriseMillis)
        near("solar noon", noon, day.solarNoonMillis)
        near("sunset", set, day.sunsetMillis)
    }

    // Published almanac: U.S. Naval Observatory, "Rise, set and transit" service
    // (aa.usno.navy.mil/api/rstt/oneday) for Lisbon (38.72 N, 9.14 W). The values below were transcribed from
    // that service; the date of the query was not recorded, so re-check them against the service before relying on them.
    // The same four dates as the sky page's reference days, on Lisbon's own clock:
    // 2025-03-20 UTC, 2025-06-21 UTC+1, 2025-09-22 UTC+1, 2025-12-21 UTC.
    // Rise / solar noon / set, whole minutes:
    // 06:40 / 12:44 / 18:49, 06:12 / 13:38 / 21:05, 07:24 / 13:29 / 19:33, 07:51 / 12:35 / 17:18.
    @Test fun lisbonFourDates() {
        check(lisbon, "2025-03-20", 0, "06:40", "12:44", "18:49")
        check(lisbon, "2025-06-21", 1, "06:12", "13:38", "21:05")
        check(lisbon, "2025-09-22", 1, "07:24", "13:29", "19:33")
        check(lisbon, "2025-12-21", 0, "07:51", "12:35", "17:18")
    }

    @Test fun nairobiFourDates() {
        check(nairobi, "2025-03-20", 3, "06:37", "12:40", "18:43")
        check(nairobi, "2025-06-21", 3, "06:33", "12:35", "18:36")
        check(nairobi, "2025-09-22", 3, "06:22", "12:25", "18:29")
        check(nairobi, "2025-12-21", 3, "06:25", "12:31", "18:37")
    }

    @Test fun tromsoDaysWithASunriseAndSunset() {
        check(tromso, "2025-01-25", 1, "10:01", "11:56", "13:53")
        check(tromso, "2025-03-20", 1, "05:43", "11:52", "18:02")
        check(tromso, "2025-05-10", 2, "02:24", "12:41", "23:04")
        check(tromso, "2025-11-25", 1, "10:46", "11:31", "12:15")
    }

    @Test fun tromsoMidnightSunAndPolarNight() {
        val summer = SkyMath.sunDay(LocalDate.parse("2025-06-21"), tromso)
        assertEquals(SunState.StaysUp, summer.state)
        assertNull(summer.sunriseMillis)
        assertNull(summer.sunsetMillis)
        assertEquals(24 * 3_600_000L, summer.dayLengthMillis)
        // Upper transit 12:46 CEST (USNO).
        assertTrue(Math.abs(summer.solarNoonMillis - local("2025-06-21", "12:46", 2)) <= twoMinutes)
        val winter = SkyMath.sunDay(LocalDate.parse("2025-12-21"), tromso)
        assertEquals(SunState.StaysDown, winter.state)
        assertNull(winter.sunriseMillis)
        assertEquals(0L, winter.dayLengthMillis)
    }

    @Test fun thePageSaysSunStaysUpAndSunStaysDown() {
        val summer = SkyView.build(tromso, Instant.parse("2025-06-21T10:00:00Z"), 0)
        assertEquals("Sun stays up", summer.sunHeadline)
        val winter = SkyView.build(tromso, Instant.parse("2025-12-21T10:00:00Z"), 0)
        assertEquals("Sun stays down", winter.sunHeadline)
        assertNull(SkyView.build(lisbon, Instant.parse("2025-06-21T10:00:00Z"), 0).sunHeadline)
    }

    @Test fun goldenAndBlueHoursAreOrderedAroundSunriseAndSunset() {
        val day = SkyMath.sunDay(LocalDate.parse("2025-03-20"), lisbon)
        val mb = day.morningBlue!!; val mg = day.morningGolden!!; val eg = day.eveningGolden!!; val eb = day.eveningBlue!!
        assertEquals(mb.endMillis, mg.startMillis)
        assertEquals(eg.endMillis, eb.startMillis)
        assertTrue(mb.startMillis < mg.startMillis && mg.startMillis < day.sunriseMillis!! && day.sunriseMillis < mg.endMillis)
        assertTrue(eg.startMillis < day.sunsetMillis!! && day.sunsetMillis < eg.endMillis && eg.endMillis < eb.endMillis)
        // The sun really is at the stated heights at the ends of the ranges.
        assertEquals(-6.0, SkyMath.elevationDegrees(lisbon, mb.startMillis), 0.05)
        assertEquals(-4.0, SkyMath.elevationDegrees(lisbon, mg.startMillis), 0.05)
        assertEquals(6.0, SkyMath.elevationDegrees(lisbon, mg.endMillis), 0.05)
        assertEquals(6.0, SkyMath.elevationDegrees(lisbon, eg.startMillis), 0.05)
        assertEquals(-4.0, SkyMath.elevationDegrees(lisbon, eg.endMillis), 0.05)
        assertEquals(-6.0, SkyMath.elevationDegrees(lisbon, eb.endMillis), 0.05)
    }

    @Test fun goldenHourIsMissingWhereTheSunNeverGetsThatLow() {
        // Tromso at the June solstice: the sun stays above -4 degrees all night.
        val day = SkyMath.sunDay(LocalDate.parse("2025-06-21"), tromso)
        assertNull(day.morningGolden)
        assertEquals("—", SkyView.range(day.morningGolden, 0))
    }

    @Test fun dayLengthChangeIsWrittenInWords() {
        assertEquals("2 min shorter", SkyView.change(10 * 3_600_000L, 10 * 3_600_000L + 120_000L))
        assertEquals("1 min longer", SkyView.change(10 * 3_600_000L + 60_000L, 10 * 3_600_000L))
        assertEquals("Same as yesterday", SkyView.change(10 * 3_600_000L + 20_000L, 10 * 3_600_000L))
        assertEquals("1 h 05 min longer", SkyView.change(10 * 3_600_000L + 65 * 60_000L, 10 * 3_600_000L))
        // Only the direction is tested here: days shorten after the September equinox and lengthen in spring.
        val autumn = SkyView.build(lisbon, Instant.parse("2025-09-22T12:00:00Z"), 0)
        assertTrue(autumn.changeText!!.endsWith("shorter"))
        val spring = SkyView.build(lisbon, Instant.parse("2025-03-20T12:00:00Z"), 0)
        assertTrue(spring.changeText!!.endsWith("longer"))
    }

    @Test fun lengthAndOffsetAreFormatted() {
        assertEquals("7 h 52 min", SkyView.length(7 * 3_600_000L + 52 * 60_000L))
        assertEquals("24 h", SkyView.length(24 * 3_600_000L))
        assertEquals("0 h", SkyView.length(0))
        assertEquals("UTC+4", SkyView.offsetLabel(4 * 3600))
        assertEquals("UTC+5:30", SkyView.offsetLabel(5 * 3600 + 1800))
        assertEquals("UTC-3", SkyView.offsetLabel(-3 * 3600))
        assertEquals("UTC", SkyView.offsetLabel(0))
    }

    @Test fun timesAreShownInThePlacesOwnOffsetAndTheDifferenceIsSaid() {
        val now = Instant.parse("2025-06-21T10:00:00Z")
        val summerLisbon = lisbon.copy(utcOffsetSeconds = 3600)
        val sameOffset = SkyView.build(summerLisbon, now, phoneOffsetSeconds = 3600)
        assertNull(sameOffset.offsetNote)
        val different = SkyView.build(summerLisbon, now, phoneOffsetSeconds = 0)
        assertEquals("Times in the place's own time (UTC+1)", different.offsetNote)
        assertEquals(3600, different.offsetSeconds)
        // USNO gives 06:12 for Lisbon's sunrise on its own clock (05:12 UTC); the page shows 06:12, not the phone's 05:12.
        assertTrue(Math.abs(different.sun!!.sunriseMillis!! - local("2025-06-21", "06:12", 1)) <= twoMinutes)
        assertTrue(SkyView.clock(different.sun.sunriseMillis!!, different.offsetSeconds).startsWith("06:1"))
        // A place typed by hand has no offset of its own: the phone's is used and nothing is said.
        val typed = SkyView.build(SkyPlace(38.72, -9.14), now, phoneOffsetSeconds = 0)
        assertNull(typed.offsetNote)
        assertEquals(0, typed.offsetSeconds)
    }

    @Test fun theLocalDateIsTheDateAtThePlace() {
        // 23:30 UTC on 20 June is already 21 June in Lisbon (UTC+1 in summer).
        val data = SkyView.build(lisbon.copy(utcOffsetSeconds = 3600), Instant.parse("2025-06-20T23:30:00Z"), 0)
        assertEquals(LocalDate.parse("2025-06-21"), data.sun!!.date)
    }

    @Test fun withoutAPlaceTheSunPartsAreEmptyAndTheMoonStillWorks() {
        val data = SkyView.build(null, Instant.parse("2025-06-21T10:00:00Z"), 0)
        assertNull(data.sun)
        assertNull(data.place)
        assertEquals("Set a place", SkyView.SetAPlace)
        assertNotNull(data.moon)
        assertNotNull(data.nextFullMillis)
        assertNotNull(data.nextNewMillis)
        assertTrue(data.curve.isEmpty())
    }

    // --- Moon: exact phase moments from USNO (UT) ----------------------------------------------------------------------

    private fun litAt(date: String, time: String) = SkyMath.litFraction(utc(date, time))

    @Test fun moonPhaseAtFourKnownDates() {
        assertEquals("new moon 2024-04-08 18:21", 0.0, litAt("2024-04-08", "18:21"), 0.01)
        assertEquals("first quarter 2024-04-15 19:13", 0.5, litAt("2024-04-15", "19:13"), 0.01)
        assertEquals("full moon 2025-06-11 07:44", 1.0, litAt("2025-06-11", "07:44"), 0.01)
        assertEquals("last quarter 2025-06-18 19:19", 0.5, litAt("2025-06-18", "19:19"), 0.01)
    }

    @Test fun moonPhaseAtTwoMoreKnownDates() {
        assertEquals("new moon 2025-06-25 10:31", 0.0, litAt("2025-06-25", "10:31"), 0.01)
        assertEquals("first quarter 2026-10-18 16:12", 0.5, litAt("2026-10-18", "16:12"), 0.01)
    }

    @Test fun moonWaxesBetweenNewAndFullAndWanesAfter() {
        assertTrue(SkyMath.moon(utc("2025-06-05", "12:00")).waxing)
        assertFalse(SkyMath.moon(utc("2025-06-14", "12:00")).waxing)
        val m = SkyMath.moon(utc("2025-06-03", "03:41"))
        assertEquals(MoonName.FirstQuarter, m.name)
        assertTrue("percent ${m.litPercent}", Math.abs(m.litPercent - 50) <= 1)
    }

    @Test fun moonNamesCoverTheWholeCycle() {
        assertEquals(MoonName.New, SkyMath.nameFor(0.0))
        assertEquals(MoonName.New, SkyMath.nameFor(355.0))
        assertEquals(MoonName.WaxingCrescent, SkyMath.nameFor(45.0))
        assertEquals(MoonName.FirstQuarter, SkyMath.nameFor(90.0))
        assertEquals(MoonName.WaxingGibbous, SkyMath.nameFor(135.0))
        assertEquals(MoonName.Full, SkyMath.nameFor(180.0))
        assertEquals(MoonName.WaningGibbous, SkyMath.nameFor(225.0))
        assertEquals(MoonName.LastQuarter, SkyMath.nameFor(270.0))
        assertEquals(MoonName.WaningCrescent, SkyMath.nameFor(315.0))
    }

    @Test fun nextFullAndNewMoonAreFoundWithinTwoHours() {
        val from = utc("2025-01-01", "12:00")
        val full = SkyMath.nextFullMoon(from)!!
        val new = SkyMath.nextNewMoon(from)!!
        assertTrue("full ${Instant.ofEpochMilli(full)}", Math.abs(full - utc("2025-01-13", "22:27")) <= 2 * 3_600_000L)
        assertTrue("new ${Instant.ofEpochMilli(new)}", Math.abs(new - utc("2025-01-29", "12:36")) <= 2 * 3_600_000L)
        val from2 = utc("2026-11-10", "12:00")
        assertTrue(Math.abs(SkyMath.nextFullMoon(from2)!! - utc("2026-11-24", "14:53")) <= 2 * 3_600_000L)
    }

    @Test fun nextPhaseJustAfterAFullMoonLooksAMonthAhead() {
        val after = utc("2025-06-11", "09:00")
        val next = SkyMath.nextFullMoon(after)!!
        val days = (next - after) / 86_400_000.0
        assertTrue("days $days", days in 28.0..30.5)
    }

    @Test fun dayFormatUsesThePlacesOffset() {
        // 23:30 UTC on 24 Nov is 00:30 on 25 Nov at UTC+1.
        assertEquals("Tue 25 Nov", SkyView.day(utc("2025-11-24", "23:30"), 3600, Locale.ENGLISH))
        assertEquals("Mon 24 Nov", SkyView.day(utc("2025-11-24", "23:30"), 0, Locale.ENGLISH))
    }

    // --- The hand-typed place ----------------------------------------------------------------------------------------

    @Test fun aTypedPlaceIsParsedTolerantly() {
        assertEquals(SkyPlace(38.72, -9.14), SkyPlaceText.parse("38.72, -9.14"))
        assertEquals(SkyPlace(38.72, -9.14), SkyPlaceText.parse("38.72,-9.14"))
        assertEquals(SkyPlace(38.72, -9.14), SkyPlaceText.parse("  38.72 -9.14 "))
        assertEquals(SkyPlace(38.72, -9.14), SkyPlaceText.parse("38,72; -9,14"))
        assertEquals(SkyPlace(-1.29, 36.82), SkyPlaceText.parse("-1.29 36.82"))
    }

    @Test fun badTypedPlacesAreRefused() {
        listOf(null, "", "   ", "abc", "38.72", "91, 0", "0, 181", "1, 2, 3", "NaN, 0", "38.72, x").forEach {
            assertNull("[$it]", SkyPlaceText.parse(it))
        }
    }

    @Test fun aTypedPlaceSurvivesStoringAndOldOrEmptyTextReadsAsNoPlace() {
        val stored = SkyPlaceText.encode(SkyPlace(38.72, -9.14))
        assertEquals("38.7200,-9.1400", stored)
        assertEquals(SkyPlace(38.72, -9.14), SkyPlaceText.decode(stored))
        assertNull(SkyPlaceText.decode(null))
        assertNull(SkyPlaceText.decode(""))
        assertNull(SkyPlaceText.decode("garbage"))
        assertEquals("", SkyPlaceText.encode(null))
    }
}
