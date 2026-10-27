package com.brightblu.parc.core.data.agenda

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class AgendaRulesTest {
    private val lisbon = ZoneId.of("Europe/Lisbon")
    private val auckland = ZoneId.of("Pacific/Auckland")
    private val la = ZoneId.of("America/Los_Angeles")

    private fun ms(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private fun utcDay(y: Int, mo: Int, d: Int): Long = ms(ZoneId.of("UTC"), y, mo, d)

    private fun timed(id: Long, begin: Long, end: Long, title: String = "T$id", cal: Long = 1) =
        AgendaEvent(id, cal, title, begin, end, false)

    private fun allDay(id: Long, beginUtc: Long, endUtc: Long, title: String = "A$id", cal: Long = 1) =
        AgendaEvent(id, cal, title, beginUtc, endUtc, true)

    // --- grouping ---

    @Test fun groupsByLocalDayWithAllDayFirstAndTimedSorted() {
        val now = ms(lisbon, 2026, 10, 19, 8)
        val events = listOf(
            timed(1, ms(lisbon, 2026, 10, 19, 14), ms(lisbon, 2026, 10, 19, 15)),
            timed(2, ms(lisbon, 2026, 10, 19, 9), ms(lisbon, 2026, 10, 19, 10)),
            allDay(3, utcDay(2026, 10, 19), utcDay(2026, 10, 20), "Holiday"),
            timed(4, ms(lisbon, 2026, 10, 20, 11), ms(lisbon, 2026, 10, 20, 12)),
            timed(5, ms(lisbon, 2026, 10, 21, 11), ms(lisbon, 2026, 10, 21, 12)),
        )
        val days = AgendaRules.group(events, now, lisbon, 2)
        assertEquals(2, days.size)
        assertEquals(listOf(3L), days[0].allDay.map { it.eventId })
        assertEquals(listOf(2L, 1L), days[0].timed.map { it.eventId })
        assertEquals(listOf(4L), days[1].timed.map { it.eventId })
        assertEquals(listOf("Today", "Tomorrow"), days.map { AgendaRules.dayLabel(it, Locale.US) })
    }

    @Test fun daysAheadControlsHowManyDaysAndLabelsLaterDaysByDate() {
        val now = ms(lisbon, 2026, 10, 19, 8)
        val days = AgendaRules.group(emptyList(), now, lisbon, 4)
        assertEquals(4, days.size)
        assertEquals("Wed 21 Oct", AgendaRules.dayLabel(days[2], Locale.US))
        assertEquals(7, AgendaRules.group(emptyList(), now, lisbon, 99).size)
        assertEquals(1, AgendaRules.group(emptyList(), now, lisbon, 0).size)
        assertEquals(2, AgendaRules.clampDays(null))
    }

    @Test fun endedTimedEventsAreDroppedForTodayOnly() {
        val now = ms(lisbon, 2026, 10, 19, 12)
        val events = listOf(
            timed(1, ms(lisbon, 2026, 10, 19, 9), ms(lisbon, 2026, 10, 19, 10)),
            timed(2, ms(lisbon, 2026, 10, 19, 11), ms(lisbon, 2026, 10, 19, 13)),
            allDay(3, utcDay(2026, 10, 19), utcDay(2026, 10, 20)),
        )
        val today = AgendaRules.group(events, now, lisbon, 1)[0]
        assertEquals(listOf(2L), today.timed.map { it.eventId })
        assertEquals(listOf(3L), today.allDay.map { it.eventId })
    }

    @Test fun duplicateInstancesAreCollapsed() {
        val now = ms(lisbon, 2026, 10, 19, 8)
        val e = timed(1, ms(lisbon, 2026, 10, 19, 9), ms(lisbon, 2026, 10, 19, 10))
        assertEquals(1, AgendaRules.group(listOf(e, e), now, lisbon, 1)[0].timed.size)
    }

    // --- day boundaries ---

    @Test fun eventsAroundMidnightBelongToTheRightDay() {
        val now = ms(lisbon, 2026, 10, 19, 8)
        val justBefore = timed(1, ms(lisbon, 2026, 10, 19, 23, 59), ms(lisbon, 2026, 10, 20, 0, 30))
        val atMidnight = timed(2, ms(lisbon, 2026, 10, 20, 0, 0), ms(lisbon, 2026, 10, 20, 1))
        val endsAtMidnight = timed(3, ms(lisbon, 2026, 10, 19, 22), ms(lisbon, 2026, 10, 20, 0, 0))
        val days = AgendaRules.group(listOf(justBefore, atMidnight, endsAtMidnight), now, lisbon, 2)
        assertEquals(setOf(1L, 3L), days[0].timed.map { it.eventId }.toSet())
        assertEquals(setOf(1L, 2L), days[1].timed.map { it.eventId }.toSet())
    }

    @Test fun zeroLengthEventLandsOnOneDayOnly() {
        val now = ms(lisbon, 2026, 10, 19, 8)
        val at = ms(lisbon, 2026, 10, 20, 0, 0)
        val days = AgendaRules.group(listOf(timed(1, at, at)), now, lisbon, 2)
        assertTrue(days[0].timed.isEmpty())
        assertEquals(1, days[1].timed.size)
    }

    @Test fun windowIsLocalMidnightToLocalMidnight() {
        val now = ms(lisbon, 2026, 10, 19, 15)
        val (from, to) = AgendaRules.window(now, lisbon, 2)
        assertEquals(ms(lisbon, 2026, 10, 19), from)
        assertEquals(ms(lisbon, 2026, 10, 21), to)
    }

    // --- DST ---

    @Test fun springForwardDayIs23HoursAndWindowFollowsIt() {
        // Europe/Lisbon moves 01:00 -> 02:00 on 2026-03-29.
        val now = ms(lisbon, 2026, 3, 29, 10)
        val (from, to) = AgendaRules.window(now, lisbon, 1)
        assertEquals(23L * 3_600_000, to - from)
        val late = timed(1, ms(lisbon, 2026, 3, 29, 23, 30), ms(lisbon, 2026, 3, 30, 0, 30))
        val days = AgendaRules.group(listOf(late), now, lisbon, 2)
        assertEquals(1, days[0].timed.size)
        assertEquals(1, days[1].timed.size)
    }

    @Test fun fallBackDayIs25HoursAndLateEventStaysOnItsDay() {
        // Europe/Lisbon moves 02:00 -> 01:00 on 2026-10-25.
        val now = ms(lisbon, 2026, 10, 25, 0, 10)
        val (from, to) = AgendaRules.window(now, lisbon, 1)
        assertEquals(25L * 3_600_000, to - from)
        val e = timed(1, ms(lisbon, 2026, 10, 25, 23, 0), ms(lisbon, 2026, 10, 25, 23, 45))
        val days = AgendaRules.group(listOf(e), now, lisbon, 2)
        assertEquals(1, days[0].timed.size)
        assertTrue(days[1].timed.isEmpty())
    }

    // --- all-day across time zones ---

    @Test fun allDayEventStaysOnItsOwnDateInEveryTimeZone() {
        // Stored as UTC midnight 19 Oct to 20 Oct: it is "19 Oct" for everyone, east or west of UTC.
        val event = allDay(1, utcDay(2026, 10, 19), utcDay(2026, 10, 20))
        for ((zone, nowLocal) in listOf(
            lisbon to ms(lisbon, 2026, 10, 19, 12),
            auckland to ms(auckland, 2026, 10, 19, 12),
            la to ms(la, 2026, 10, 19, 12),
        )) {
            val days = AgendaRules.group(listOf(event), nowLocal, zone, 2)
            assertEquals("today in $zone", 1, days[0].allDay.size)
            assertTrue("tomorrow in $zone", days[1].allDay.isEmpty())
        }
    }

    @Test fun multiDayAllDayCoversEachDateUpToTheExclusiveEnd() {
        val event = allDay(1, utcDay(2026, 10, 19), utcDay(2026, 10, 22))
        val now = ms(la, 2026, 10, 19, 12)
        val days = AgendaRules.group(listOf(event), now, la, 4)
        assertEquals(listOf(1, 1, 1, 0), days.map { it.allDay.size })
    }

    @Test fun malformedAllDayWithEqualBoundsStillShowsOnItsDate() {
        val event = allDay(1, utcDay(2026, 10, 19), utcDay(2026, 10, 19))
        assertEquals(1, AgendaRules.group(listOf(event), ms(lisbon, 2026, 10, 19, 9), lisbon, 1)[0].allDay.size)
    }

    @Test fun allDayEventIsNeverTheNextEvent() {
        val now = ms(lisbon, 2026, 10, 19, 8)
        val days = AgendaRules.group(listOf(allDay(1, utcDay(2026, 10, 19), utcDay(2026, 10, 20))), now, lisbon, 2)
        assertNull(AgendaRules.next(days, now))
    }

    // --- next event and "in N min" ---

    @Test fun nextIsRunningOrEarliestUpcomingTimedEvent() {
        val now = ms(lisbon, 2026, 10, 19, 12)
        val running = timed(1, ms(lisbon, 2026, 10, 19, 11, 30), ms(lisbon, 2026, 10, 19, 12, 30))
        val later = timed(2, ms(lisbon, 2026, 10, 19, 13), ms(lisbon, 2026, 10, 19, 14))
        val tomorrow = timed(3, ms(lisbon, 2026, 10, 20, 9), ms(lisbon, 2026, 10, 20, 10))
        assertEquals(1L, AgendaRules.next(AgendaRules.group(listOf(later, running, tomorrow), now, lisbon, 2), now)?.eventId)
        assertEquals(2L, AgendaRules.next(AgendaRules.group(listOf(later, tomorrow), now, lisbon, 2), now)?.eventId)
        assertEquals(3L, AgendaRules.next(AgendaRules.group(listOf(tomorrow), now, lisbon, 2), now)?.eventId)
        assertNull(AgendaRules.next(AgendaRules.group(emptyList(), now, lisbon, 2), now))
    }

    @Test fun untilTextFormatting() {
        val now = 1_000_000_000L
        fun text(deltaMs: Long) = AgendaRules.untilText(now + deltaMs, now)
        assertEquals("now", text(0))
        assertEquals("now", text(-5_000))
        assertEquals("in 1 min", text(1))
        assertEquals("in 1 min", text(60_000))
        assertEquals("in 2 min", text(60_001))
        assertEquals("in 25 min", text(24 * 60_000L + 30_000))
        assertEquals("in 59 min", text(59 * 60_000L))
        assertEquals("in 1 h", text(60 * 60_000L))
        assertEquals("in 1 h 5 min", text(65 * 60_000L))
        assertEquals("in 3 h", text(3 * 3_600_000L))
        assertEquals("in 23 h 59 min", text(23 * 3_600_000L + 59 * 60_000L))
        assertEquals("in 1 d", text(24 * 3_600_000L))
        assertEquals("in 2 d", text(50 * 3_600_000L))
    }

    // --- calendar filter ---

    @Test fun excludedCalendarsAreFilteredByName() {
        val names = mapOf(1L to "Personal", 2L to "Holidays", 3L to "Work")
        val events = listOf(timed(1, 0, 1, cal = 1), timed(2, 0, 1, cal = 2), timed(3, 0, 1, cal = 3), timed(4, 0, 1, cal = 99))
        assertEquals(4, AgendaRules.filter(events, names, AgendaSettings()).size)
        val settings = AgendaSettings(excluded = setOf("Holidays", "Work"))
        assertEquals(listOf(1L, 4L), AgendaRules.filter(events, names, settings).map { it.eventId })
        assertTrue(settings.includes(AgendaCalendar(1, "Personal")))
        assertFalse(settings.includes(AgendaCalendar(2, "Holidays")))
        assertEquals(setOf("Holidays"), settings.withCalendar("Work", true).excluded)
        assertEquals(setOf("Holidays", "Work", "Birthdays"), settings.withCalendar("Birthdays", false).excluded)
    }

    // --- settings codec ---

    @Test fun settingsRoundTripAndClampBadValues() {
        val s = AgendaSettings(5, setOf("Work", "Name, with comma"))
        val back = AgendaSettings.decode(s.encodeDays(), s.encodeExcluded())
        assertEquals(s, back)
        assertEquals(AgendaSettings(), AgendaSettings.decode(null, null))
        assertEquals(7, AgendaSettings.decode("40", "").days)
        assertEquals(1, AgendaSettings.decode("-3", "").days)
        assertEquals(2, AgendaSettings.decode("abc", "").days)
        assertEquals(setOf("A"), AgendaSettings.decode("2", "\n A \n\n").excluded)
        val huge = AgendaSettings(2, (1..500).map { "Calendar number $it" }.toSet())
        assertTrue(huge.encodeExcluded().length <= AgendaSettings.MaxEncoded)
    }

    // --- text ---

    @Test fun clockFollowsTwelveOrTwentyFourHour() {
        val t = ms(lisbon, 2026, 10, 19, 15, 5)
        assertEquals("15:05", AgendaRules.clock(t, lisbon, true, Locale.US))
        assertEquals("3:05 PM", AgendaRules.clock(t, lisbon, false, Locale.US))
    }

    @Test fun timeRangeHandlesEventsCrossingMidnight() {
        val day1 = LocalDate.of(2026, 10, 19)
        val day2 = LocalDate.of(2026, 10, 20)
        val e = timed(1, ms(lisbon, 2026, 10, 19, 22), ms(lisbon, 2026, 10, 20, 2))
        assertEquals("from 22:00", AgendaRules.timeRange(e, day1, lisbon, true, Locale.US))
        assertEquals("until 02:00", AgendaRules.timeRange(e, day2, lisbon, true, Locale.US))
        val plain = timed(2, ms(lisbon, 2026, 10, 19, 9), ms(lisbon, 2026, 10, 19, 10, 30))
        assertEquals("09:00 - 10:30", AgendaRules.timeRange(plain, day1, lisbon, true, Locale.US))
        val span = timed(3, ms(lisbon, 2026, 10, 18, 22), ms(lisbon, 2026, 10, 21, 2))
        assertEquals("all day", AgendaRules.timeRange(span, day1, lisbon, true, Locale.US))
    }

    @Test fun emptyLinesAndTitles() {
        assertEquals("Loading", AgendaRules.emptyLine(null, null))
        assertEquals("Calendar access is off", AgendaRules.emptyLine(AgendaRead.NoPermission, null))
        val now = ms(lisbon, 2026, 10, 19, 8)
        val empty = AgendaRules.group(emptyList(), now, lisbon, 2)
        assertEquals("Nothing planned", AgendaRules.emptyLine(AgendaRead.Data(emptyList()), empty))
        val e = timed(1, ms(lisbon, 2026, 10, 19, 9), ms(lisbon, 2026, 10, 19, 10))
        assertNull(AgendaRules.emptyLine(AgendaRead.Data(listOf(e)), AgendaRules.group(listOf(e), now, lisbon, 2)))
        assertEquals("(No title)", AgendaRules.titleText(e.copy(title = " ")))
    }
}
