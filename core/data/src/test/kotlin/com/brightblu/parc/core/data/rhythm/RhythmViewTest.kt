package com.brightblu.parc.core.data.rhythm

import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.LedgerDay
import com.brightblu.parc.core.data.ledger.LedgerJson
import com.brightblu.parc.core.data.ledger.LedgerMath
import java.time.LocalDate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T188: reading the ledger for the page, and the ledger's new fields. */
class RhythmViewTest {
    private val today = LocalDate.of(2026, 11, 10)
    private val bins = BooleanArray(RhythmBins.Count).also { it[96] = true; it[97] = true }
    private val text = RhythmBins.encode(bins)
    private fun day(offset: Long, rhythm: Boolean = true) = LedgerDay(today.minusDays(offset), pickups = if (rhythm) 10 else null,
        rhythmBins = if (rhythm) text else null, firstPickupMinute = if (rhythm) 485 else null, screenOnMillis = 600_000L, phoneFreeMillis = 3_600_000L)
    private val ledger = DayLedger.Empty.withDay(day(0)).withDay(day(1)).withDay(day(4)).withDay(day(2, rhythm = false))

    @Test fun onlyDaysWithRhythmCanBeVisitedAndTodayIsAlwaysTheEnd() {
        assertEquals(listOf(today.minusDays(4), today.minusDays(1), today), RhythmView.recordedDates(ledger))
        assertEquals(today.minusDays(1), RhythmView.previous(ledger, today))
        assertEquals(today.minusDays(4), RhythmView.previous(ledger, today.minusDays(1)))
        assertNull(RhythmView.previous(ledger, today.minusDays(4)))
        assertEquals(today.minusDays(1), RhythmView.next(ledger, today.minusDays(4), today))
        assertEquals(today, RhythmView.next(ledger, today.minusDays(1), today))
        assertNull(RhythmView.next(ledger, today, today))
        // Even with an empty ledger today can be reached from an old date.
        assertEquals(today, RhythmView.next(DayLedger.Empty, today.minusDays(3), today))
    }

    @Test fun aPastDayDrawsFromTheStoredSegmentsAndNumbers() {
        val shown = RhythmView.show(ledger, today.minusDays(1), today, 0L, 86_400_000L)
        assertArrayEquals(bins, shown.bins)
        assertEquals("08:05", shown.firstPickup)
        assertEquals(10, shown.pickups)
        assertEquals(600_000L, shown.screenOnMillis)
        assertEquals(3_600_000L, shown.longestFreeMillis)
        assertNull(shown.nowFraction)
        assertFalse(shown.isToday)
        assertTrue(shown.recorded)
    }

    @Test fun todayGetsTheNowMarkAndAnEmptyDayIsNotRecorded() {
        val shown = RhythmView.show(ledger, today, today, 21_600_000L, 86_400_000L)
        assertEquals(0.25f, shown.nowFraction!!, 0.0001f)
        val none = RhythmView.show(DayLedger.Empty, today, today, 0L, 86_400_000L)
        assertFalse(none.recorded)
        assertNull(none.screenOnMillis)
        assertNull(none.firstPickup)
    }

    @Test fun theScreenTotalFallsBackToTheLitBinsWhenTheLedgerHasNone() {
        val l = DayLedger.Empty.withDay(LedgerDay(today, pickups = 1, rhythmBins = text))
        assertEquals(10L * 60_000L, RhythmView.show(l, today, today, 0L, 86_400_000L).screenOnMillis)
    }

    @Test fun datesAndRunsReadInWords() {
        assertEquals("Today", RhythmView.dateLabel(today, today))
        assertEquals("Yesterday", RhythmView.dateLabel(today.minusDays(1), today))
        assertEquals("Fri 6 Nov", RhythmView.dateLabel(today.minusDays(4), today))
        assertEquals("The screen was on 08:00 to 08:10", RhythmView.runsDescription(bins))
        assertEquals("The screen was not on", RhythmView.runsDescription(BooleanArray(RhythmBins.Count)))
        val many = BooleanArray(RhythmBins.Count) { it % 2 == 0 }
        assertTrue(RhythmView.runsDescription(many, 3).endsWith(", and 141 more"))
    }

    @Test fun theLedgerRoundTripsTheRhythmFieldsAndOldDocumentsReadThemAsNull() {
        val l = DayLedger.Empty.withDay(day(0))
        assertEquals(l, LedgerJson.decode(LedgerJson.encode(l)))
        val old = """{"v":1,"days":{"${today.toEpochDay()}":{"wifi":5,"pickups":3}}}"""
        val d = LedgerJson.decode(old).day(today)!!
        assertNull(d.rhythmBins); assertNull(d.firstPickupMinute); assertEquals(3, d.pickups)
        val broken = """{"v":1,"days":{"${today.toEpochDay()}":{"bins":"nope","firstPickup":5000}}}"""
        val b = LedgerJson.decode(broken).day(today)!!
        assertNull(b.rhythmBins); assertNull(b.firstPickupMinute)
        val empty = LedgerJson.decode("")
        assertEquals(DayLedger.Empty, empty)
    }

    @Test fun closingADayKeepsTheRhythmFields() {
        val open = DayLedger.Empty.withDay(day(1).copy(screenOnMillis = null, phoneFreeMillis = null))
        val merged = LedgerMath.mergeClosed(open, listOf(LedgerDay(today.minusDays(1), wifiBytes = 5, screenOnMillis = 1_000L)))
        val closed = merged.day(today.minusDays(1))!!
        assertTrue(closed.closed)
        assertEquals(text, closed.rhythmBins)
        assertEquals(485, closed.firstPickupMinute)
        assertEquals(10, closed.pickups)
    }
}
