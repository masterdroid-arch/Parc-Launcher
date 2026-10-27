package com.brightblu.parc

import com.brightblu.parc.core.data.timers.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class T135Test {
    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    @Test fun defaultsAndRoundTrip() {
        assertEquals(ClockOptions(), ClockOptions.decode(null))
        assertEquals(ClockOptions(), ClockOptions.decode("unknown|unknown|Mars/Base"))
        val options = ClockOptions(false, true, "Pacific/Auckland")
        assertEquals(options, ClockOptions.decode(options.encode()))
        assertEquals(HubSize.Standard, HubConfig.decode("clock:true::").size)
    }
    @Test fun countdownRoundsUpAndNeverGoesNegative() {
        assertEquals("in 1 min", ClockDisplay.until(1, 0))
        assertEquals("in 0 min", ClockDisplay.until(0, 1))
        assertEquals("in 1 h 1 min", ClockDisplay.until(3_600_001, 0))
    }
    @Test fun dstUsesInstantsAcrossBothTransitions() {
        val zone = ZoneId.of("Europe/Lisbon")
        val options = ClockOptions()
        assertEquals("00:59", ClockDisplay.time(at("2026-03-29T00:59:00Z"), zone, options))
        assertEquals("02:00", ClockDisplay.time(at("2026-03-29T01:00:00Z"), zone, options))
        assertEquals("01:59", ClockDisplay.time(at("2026-10-25T00:59:00Z"), zone, options))
        assertEquals("01:00", ClockDisplay.time(at("2026-10-25T01:00:00Z"), zone, options))
        assertEquals("in 1 min", ClockDisplay.until(at("2026-10-25T01:00:00Z"), at("2026-10-25T00:59:00Z")))
    }
    @Test fun secondZoneDateAndIsoWeek() {
        val now = at("2026-01-01T01:02:03Z")
        val zone = ZoneId.of("America/Los_Angeles")
        assertEquals("5:02:03 PM", ClockDisplay.time(now, zone, ClockOptions(false, true), Locale.US))
        assertEquals("Wednesday, 31 December", ClockDisplay.date(now, zone, Locale.US))
        assertEquals(1, ClockDisplay.week(now, zone))
    }
    @Test fun allPresetsStartWithCorrectDurationAndCapacity() {
        assertEquals(listOf(1, 5, 10, 15, 30), TimerRules.PresetMinutes)
        var book = TimerBook()
        TimerRules.PresetMinutes.forEach { minutes -> book = book.start(minutes * 60_000L, 10)!! }
        assertTrue(book.isFull)
        assertNull(book.start(60_000, 10))
        assertEquals(60_000L, book.timers.first().left(10))
        assertEquals(60_000L, timerRemainingMs(book.timers.first(), 0))
        assertEquals(1000L, TimerRules.custom(0, 0, 0))
    }
    @Test fun extensionPreservesIdentityAndBoundsAndSurvivesCodec() {
        val book = TimerBook().start(60_000, 0)!!
        val extended = book.addMinute(1, 30_000)
        assertEquals(90_000L, extended.find(1)!!.left(30_000))
        assertEquals(.75f, timerFraction(extended.find(1)!!, 30_000), .001f)
        assertEquals(extended, TimerCodec.decodeBook(TimerCodec.encodeBook(extended)))
        assertEquals(book, book.addMinute(1, 60_000))
        val max = TimerBook().start(TimerRules.MaxMs, 0)!!
        assertEquals(max, max.addMinute(1, 0))
        assertEquals(book.pause(1, 10), book.pause(1, 10).addMinute(1, 20))
        assertTrue(extended.delete(1).timers.isEmpty())
    }
    @Test fun sizesLimitLapsWithoutLosingThem() {
        val stopwatch = Stopwatch().start(0).lap(1000).lap(2000).stop(3000)
        assertEquals(listOf(1000L, 2000L), stopwatch.laps)
        assertEquals(1000L, stopwatch.split(1))
        assertEquals(0, timerLapsShown(HubSize.Compact))
        assertEquals(2, timerLapsShown(HubSize.Standard))
        assertEquals(6, timerLapsShown(HubSize.Tall))
    }
}
