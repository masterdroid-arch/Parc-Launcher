package com.brightblu.parc

import com.brightblu.parc.core.data.agenda.AgendaEvent
import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.LedgerDay
import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.BookFormat
import com.brightblu.parc.core.data.timers.TimerBook
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T249: the pure side of the new Today rows and Continue reading. */
class T249TodayTest {
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private val date = LocalDate.of(2026, 11, 10)

    private fun book(id: String, opened: Long?) = Book(
        id = id,
        uri = "content://demo/$id",
        title = "Book $id",
        format = BookFormat.EPUB,
        lastOpenedAt = opened,
    )

    @Test fun continueReadingPicksTheMostRecentlyOpenedBookOnly() {
        assertNull(TodayLines.continueReading(emptyList()))
        assertNull(TodayLines.continueReading(listOf(book("never", null), book("not-opened", 0))))
        val books = listOf(book("old", 10), book("new", 40), book("middle", 20))
        assertEquals("new", TodayLines.continueReading(books)?.id)
    }

    @Test fun continuePercentIsTheStoredProgressRoundedAndBounded() {
        assertEquals(0, TodayLines.continuePercent(book("zero", 1)))
        assertEquals(42, TodayLines.continuePercent(book("some", 1).copy(progress = 0.42)))
        assertEquals(100, TodayLines.continuePercent(book("done", 1).copy(progress = 1.0)))
    }

    @Test fun continueReadingTakesOneOfTheSixStandardSlots() {
        assertEquals(6, TodayLines.shownLimit(TodayLines.StandardLimit, hasContinue = false))
        assertEquals(5, TodayLines.shownLimit(TodayLines.StandardLimit, hasContinue = true))
        assertEquals(0, TodayLines.shownLimit(0, hasContinue = true))
    }

    @Test fun rowsCarryAFigureAndAShortQuietLabel() {
        val history = (1..14).fold(DayLedger.Empty) { ledger, day ->
            ledger.withDay(LedgerDay(date.minusDays(day.toLong()), screenOnMillis = 180 * 60_000L))
        }
        val input = TodayInput(
            now = now,
            zone = ZoneOffset.UTC,
            visible = setOf("agenda", "timers", "notes", "weather", "steps", "year", "apps"),
            calendarAccess = true,
            usageAccess = true,
            stepsAccess = true,
            nextEvent = AgendaEvent(1, 1, "A walk", now.toEpochMilli() + 60_000, now.toEpochMilli() + 120_000, false),
            timers = TimerBook().start(300_000, now.toEpochMilli(), "Tea")!!,
            notes = listOf(Note("note", "Water the plants", remindAt = now.toEpochMilli() + 3_600_000)),
            weather = TodayWeather("Place", "20°", "22°", "15°"),
            steps = 5210,
            stepGoal = 8000,
            countdowns = listOf(Countdown("A day away", date.plusDays(14))),
            ledger = history.withDay(LedgerDay(date, screenOnMillis = 130 * 60_000L)),
        )
        val rows = TodayLines.ordered(input, Int.MAX_VALUE).associateBy { it.page }

        assertEquals("1 min", rows.getValue("agenda").figure)
        assertTrue(rows.getValue("agenda").caption.startsWith("Next event · A walk"))
        assertEquals("5 min", rows.getValue("timers").figure)
        assertEquals("Timer · Tea", rows.getValue("timers").caption)
        assertEquals("13:00", rows.getValue("notes").figure)
        assertTrue(rows.getValue("notes").caption.contains("Water the plants"))
        assertEquals("20°", rows.getValue("weather").figure)
        assertEquals("Place · high 22° · low 15°", rows.getValue("weather").caption)
        assertEquals("5,210", rows.getValue("steps").figure)
        assertEquals("Steps · goal 8,000", rows.getValue("steps").caption)
        assertEquals("14 days", rows.getValue("year").figure)
        assertTrue(rows.getValue("year").caption.startsWith("Countdown · A day away"))
        assertEquals("2 h 10", rows.getValue("apps").figure)
        assertEquals("Screen time · less than usual", rows.getValue("apps").caption)
    }

    @Test fun aTextOnlyRowKeepsItsWholeLineAndSource() {
        val line = TodayLines.looseLine("records", "Close to your step record · 4,200 of 5,000")
        assertEquals("", line.figure)
        assertEquals("Close to your step record · 4,200 of 5,000", line.title)
        assertEquals("Records", line.label)
        assertEquals(TodayIconKind.Records, line.icon)
    }
}
