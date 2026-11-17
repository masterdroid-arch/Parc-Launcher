package com.brightblu.parc

import com.brightblu.parc.core.data.agenda.*
import com.brightblu.parc.core.data.ledger.*
import com.brightblu.parc.core.data.timers.*
import java.time.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TodayLinesTest {
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private val date = LocalDate.of(2026, 11, 10)
    private val visible = setOf("agenda", "timers", "notes", "weather", "steps", "sky", "year", "apps", "rhythm")
    private fun input() = TodayInput(now, ZoneOffset.UTC, visible, calendarAccess = true, usageAccess = true, stepsAccess = true)
    private fun history(count: Int = 14, screen: Long? = 100L, pickups: Int? = 100): DayLedger =
        (1..count).fold(DayLedger.Empty) { ledger, day ->
            ledger.withDay(LedgerDay(date.minusDays(day.toLong()), screenOnMillis = screen, pickups = pickups))
        }
    private fun full() = input().copy(
        nextEvent = AgendaEvent(1, 1, "A walk", now.toEpochMilli() + 60_000, now.toEpochMilli() + 120_000, false),
        timers = TimerBook().start(300_000, now.toEpochMilli())!!,
        notes = listOf(Note("one", "# **Water plants**\nLater", pinned = true, remindAt = now.toEpochMilli() + 3_600_000)),
        weather = TodayWeather("Place", "20°", "22°", "15°"), steps = 1200, stepGoal = 8000,
        sky = SkyPlace(38.72, -9.14), countdowns = listOf(Countdown("A day away", date.plusDays(14))),
        ledger = history(screen = 100 * 60_000L).withDay(LedgerDay(date, screenOnMillis = 130 * 60_000L)))

    @Test fun fixedProviderOrderAndSixLineCapWithEveryDestination() {
        val all = TodayLines.ordered(full(), Int.MAX_VALUE)
        assertEquals(listOf("agenda", "timers", "notes", "weather", "steps", "sky", "year", "apps", "notes"), all.map { it.page })
        assertEquals(all.take(6), TodayLines.ordered(full()))
        assertEquals("Water plants", all.last().text)
        assertTrue(TodayLines.ordered(full(), 0).isEmpty())
    }
    @Test fun hiddenSourcesAreSilentAndRemainingLinesKeepOrder() {
        val data = full().copy(visible = setOf("year", "apps", "notes"))
        assertEquals(listOf("notes", "year", "apps", "notes"), TodayLines.ordered(data).map { it.page })
        assertTrue(TodayLines.ordered(full().copy(visible = emptySet())).isEmpty())
    }
    @Test fun deniedPermissionsSuppressOnlyTheirSourcesEvenWithSavedData() {
        val lines = TodayLines.ordered(full().copy(calendarAccess = false, usageAccess = false, stepsAccess = false), Int.MAX_VALUE)
        assertFalse(lines.any { it.page in setOf("agenda", "apps", "steps") })
        assertTrue(lines.any { it.page == "notes" })
    }
    @Test fun absentDataProducesNoPermissionRequestsOrPlaceholderRows() {
        assertTrue(TodayLines.ordered(input()).isEmpty())
        assertTrue(TodayLines.showQuote(emptyList(), false))
        assertTrue(TodayLines.showQuote(listOf(TodayLine("notes", "One")), true))
    }
    // 1.2.1 (changed on purpose): the quote card also fills the page while fewer than four rows have something to say.
    @Test fun theQuoteFillsThePageBelowFourRowsAndLeavesWhenItIsFull() {
        fun rows(n: Int) = List(n) { TodayLine("notes", "Row $it") }
        assertTrue(TodayLines.showQuote(rows(1), false))
        assertTrue(TodayLines.showQuote(rows(3), false))
        assertFalse(TodayLines.showQuote(rows(4), false))
        assertFalse(TodayLines.showQuote(rows(6), false))
        assertTrue(TodayLines.showQuote(rows(6), true)) // the heavy-day rule is unchanged: the quote leads at any length
    }
    @Test fun everyProviderNamesItsSourceAndTheRowDropsARepeatOfTheName() {
        val all = TodayLines.ordered(full(), Int.MAX_VALUE)
        assertEquals(listOf("Next event", "Timer", "Reminder", "Weather", "Steps", "Sky", "Countdown", "Screen time", "Pinned note"), all.map { it.label })
        assertEquals("Timer", TodayLine("timers", "Timer · 4 min left", "Timer").label)
        assertEquals("4 min left", TodayLine("timers", "Timer · 4 min left", "Timer").value)
        assertEquals("3 h 12 · less than usual", TodayLine("apps", "Screen time · 3 h 12 · less than usual", "Screen time").value)
        assertEquals("Tea · 4 min left", TodayLine("timers", "Tea · 4 min left", "Timer").value)
        assertEquals("socks", TodayLine("notes", "socks", "Pinned note").value)
    }
    @Test fun sixSamplesDoNotPermitComparisonOrHeavyDayButExactlySevenDo() {
        val six = input().copy(ledger = history(6).withDay(LedgerDay(date, screenOnMillis = 200, pickups = 200)))
        assertNull(TodayLines.average(six.ledger, date) { it.screenOnMillis })
        assertFalse(TodayLines.heavy(six))
        val seven = six.copy(ledger = history(7).withDay(LedgerDay(date, screenOnMillis = 200)))
        assertEquals(100.0, TodayLines.average(seven.ledger, date) { it.screenOnMillis }!!, 0.0)
        assertTrue(TodayLines.heavy(seven))
    }
    @Test fun historyIsPreviousFourteenDatesAndCountsMissingMetricsSeparately() {
        val ledger = history(20).withDay(LedgerDay(date, screenOnMillis = 999999))
            .withDay(LedgerDay(date.plusDays(1), screenOnMillis = 999999))
            .withDay(LedgerDay(date.minusDays(15), screenOnMillis = 999999))
        assertEquals(100.0, TodayLines.average(ledger, date) { it.screenOnMillis }!!, 0.0)
        assertNull(TodayLines.average(history(7, screen = null), date) { it.screenOnMillis })
        assertEquals(100.0, TodayLines.average(history(7, screen = null), date) { it.pickups?.toLong() }!!, 0.0)
    }
    @Test fun heavyThresholdIsStrictForScreenAndPickupsIndependently() {
        for (value in listOf(124, 125, 126)) {
            assertEquals(value > 125, TodayLines.heavy(input().copy(ledger = history(7).withDay(LedgerDay(date, screenOnMillis = value.toLong())))))
            assertEquals(value > 125, TodayLines.heavy(input().copy(ledger = history(7).withDay(LedgerDay(date, pickups = value)))))
        }
    }
    @Test fun missingHistoryIsNotZeroButARecordedZeroBaselineIsValid() {
        val current = LedgerDay(date, screenOnMillis = 1)
        assertFalse(TodayLines.heavy(input().copy(ledger = DayLedger.Empty.withDay(current))))
        assertTrue(TodayLines.heavy(input().copy(ledger = history(7, screen = 0).withDay(current))))
        assertFalse(TodayLines.heavy(input().copy(ledger = history(7, screen = 0).withDay(current.copy(screenOnMillis = 0)))))
    }
    @Test fun hiddenOrDeniedUsageCannotTriggerHeavyDayAndSwitchOverridesNight() {
        val data = full()
        assertTrue(TodayLines.heavy(data))
        assertFalse(TodayLines.heavy(data.copy(visible = setOf("notes"))))
        assertFalse(TodayLines.heavy(data.copy(usageAccess = false)))
        assertFalse(TodayLines.heavy(data, TodayOptions(false)))
        assertFalse(TodayLines.heavy(data.copy(now = Instant.parse("2026-11-10T00:00:00Z")), TodayOptions(false)))
    }
    @Test fun nightUsesLocalClockWithInclusiveMidnightAndExclusiveFive() {
        for ((time, heavy) in listOf("23:59:59" to false, "00:00:00" to true, "04:59:59" to true, "05:00:00" to false)) {
            assertEquals(time, heavy, TodayLines.heavy(input().copy(now = Instant.parse("2026-11-10T${time}Z"))))
        }
        assertTrue(TodayLines.heavy(input().copy(now = Instant.parse("2026-11-09T23:00:00Z"), zone = ZoneOffset.ofHours(2))))
        assertFalse(TodayLines.heavy(input().copy(now = Instant.parse("2026-11-10T00:00:00Z"), zone = ZoneOffset.ofHours(-2))))
    }
    @Test fun wordingHandlesNoHistoryBelowEqualAndAboveWithoutJudgement() {
        assertNull(TodayLines.comparison(10, null))
        assertEquals("less than usual", TodayLines.comparison(99, 100.0))
        assertEquals("as usual", TodayLines.comparison(100, 100.0))
        assertEquals("more than usual", TodayLines.comparison(101, 100.0))
        val data = full().copy(ledger = history(screen = 180 * 60_000L).withDay(LedgerDay(date, screenOnMillis = 130 * 60_000L)))
        assertEquals("Screen time · 2 h 10 · less than usual", TodayLines.screen(data))
        assertEquals("Screen time · 0 min", TodayLines.screen(input().copy(ledger = DayLedger.Empty.withDay(LedgerDay(date, screenOnMillis = 0)))))
    }
    @Test fun timerSummaryCountsOnlyRunningAndPicksNearest() {
        val book = TimerBook().start(300_000, now.toEpochMilli(), "Tea")!!.start(60_000, now.toEpochMilli())!!
        assertEquals("2 timers · next in 1 min", TodayLines.timers(input().copy(timers = book)))
        assertEquals("Tea · 5 min left", TodayLines.timers(input().copy(timers = book.pause(2, now.toEpochMilli()))))
        assertNull(TodayLines.timers(input().copy(timers = book, now = now.plusSeconds(301))))
    }
    @Test fun nextReminderIsFutureAndUsesNotesDestinationEvenWithoutTimers() {
        val notes = listOf(Note("past", "Past", remindAt = now.toEpochMilli()), Note("later", "Later", remindAt = now.toEpochMilli() + 7_200_000),
            Note("next", "**Water**", remindAt = now.toEpochMilli() + 3_600_000))
        val line = TodayLines.ordered(input().copy(visible = setOf("notes"), notes = notes, exactReminders = false)).single()
        assertEquals("notes", line.page)
        assertTrue(line.text.contains("around"))
        assertTrue(line.text.endsWith("Water"))
    }
    @Test fun stepsIncludeOptionalGoalAndZeroIsRecordedData() {
        assertEquals("0 steps", TodayLines.steps(0, null))
        assertEquals("1,200 steps · goal 8,000", TodayLines.steps(1200, 8000))
    }
    @Test fun countdownIncludesTodayAndFourteenButNotFifteenOrPast() {
        for ((days, expected) in listOf(-1 to false, 0 to true, 14 to true, 15 to false)) {
            assertEquals(expected, TodayLines.countdown(input().copy(countdowns = listOf(Countdown("Visit", date.plusDays(days.toLong()))))) != null)
        }
        val data = input().copy(countdowns = listOf(Countdown("Later", date.plusDays(4)), Countdown("Nearest", date.plusDays(2))))
        assertTrue(TodayLines.countdown(data)!!.startsWith("Nearest"))
    }
    @Test fun skyOmitsPastEventsAndPolarDaysAndKeepsRemoteOffset() {
        val place = SkyPlace(38.72, -9.14)
        val sun = SkyMath.sunDay(date, place)
        val before = input().copy(sky = place, now = Instant.ofEpochMilli(sun.eveningGolden!!.startMillis - 1))
        assertTrue(TodayLines.sky(before)!!.startsWith("Golden hour"))
        assertTrue(TodayLines.sky(before.copy(now = Instant.ofEpochMilli(sun.eveningGolden.startMillis)))!!.startsWith("Sunset"))
        assertNull(TodayLines.sky(before.copy(now = Instant.ofEpochMilli(sun.sunsetMillis!!))))
        assertNull(TodayLines.sky(input().copy(now = Instant.parse("2026-06-21T12:00:00Z"), sky = SkyPlace(69.65, 18.96))))
        val remote = input().copy(sky = SkyPlace(46.35, 48.04, utcOffsetSeconds = 14400), now = Instant.parse("2026-11-10T05:00:00Z"))
        assertTrue(TodayLines.sky(remote)!!.endsWith("UTC+4"))
    }
    @Test fun agendaUsesSameCalendarExclusionsAndNextRule() {
        val event = full().nextEvent!!
        val source = object : AgendaSource {
            override fun calendars() = listOf(AgendaCalendar(1, "Home"), AgendaCalendar(2, "Work"))
            override fun events(fromMs: Long, toMs: Long) = AgendaRead.Data(listOf(event, event.copy(eventId = 2, calendarId = 2, beginMs = now.toEpochMilli())))
        }
        assertEquals(event, todayAgenda(source, AgendaSettings(excluded = setOf("Work")), now, ZoneOffset.UTC))
        assertNull(todayAgenda(source, AgendaSettings(excluded = setOf("Work", "Home")), now, ZoneOffset.UTC))
        assertNull(TodayLines.agenda(input().copy(nextEvent = event.copy(allDay = true))))
        assertNull(TodayLines.agenda(input().copy(nextEvent = event, now = now.plusSeconds(121))))
    }
    @Test fun pinnedFirstLineUsesNewestPinAndStripsMarksWithoutTruncation() {
        val text = "a".repeat(200)
        val data = input().copy(notes = listOf(Note("old", "Old", true, 1), Note("new", "# **$text**\nOther", true, 2), Note("recent", "Unpinned", false, 3)))
        assertEquals(text, TodayLines.pinned(data))
        assertNull(TodayLines.pinned(input().copy(notes = listOf(Note("empty", "\nSecond", true)))))
    }
    @Test fun oneReadFailureIsMissingAndCancellationIsNeverSwallowed() = runTest {
        assertNull(todayRead<String> { error("Unavailable") })
        assertEquals("Next provider", todayRead { "Next provider" })
        try { todayRead<String> { throw CancellationException() }; fail("Cancellation swallowed") }
        catch (_: CancellationException) { }
    }
}
