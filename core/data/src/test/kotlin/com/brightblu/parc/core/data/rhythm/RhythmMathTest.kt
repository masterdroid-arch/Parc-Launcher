package com.brightblu.parc.core.data.rhythm

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T188: events in, segments, pickups and the longest phone-free stretch out. */
class RhythmMathTest {
    private val z = ZoneOffset.UTC
    private val d = LocalDate.of(2026, 11, 10)
    private fun t(date: String, time: String) = Instant.parse("${date}T$time:00Z").toEpochMilli()
    private fun on(date: String, time: String) = RhythmEvent(RhythmEvent.Kind.ScreenOn, t(date, time))
    private fun off(date: String, time: String) = RhythmEvent(RhythmEvent.Kind.ScreenOff, t(date, time))
    private fun unlock(date: String, time: String) = RhythmEvent(RhythmEvent.Kind.KeyguardHidden, t(date, time))
    private val start = t("2026-11-10", "00:00")
    private val end = t("2026-11-11", "00:00")
    private val min = 60_000L

    @Test fun noEventsMeansNothingHappenedAndOneLongFreeStretch() {
        val day = RhythmMath.day(emptyList(), d, z, RhythmOptions(), t("2026-11-10", "20:00"))
        assertTrue(day.segments.isEmpty())
        assertEquals(0, day.pickups)
        assertNull(day.firstPickupMinute)
        assertEquals(0L, day.screenOnMillis)
        assertEquals(14L * 60 * min, day.longestFreeMillis)
        assertTrue(day.bins.none { it })
    }

    @Test fun aSessionAcrossMidnightIsCutInTwo() {
        val events = listOf(on("2026-11-09", "23:40"), off("2026-11-10", "00:20"), on("2026-11-10", "08:00"), off("2026-11-10", "08:10"))
        val today = RhythmMath.segments(events, start, end, end)
        assertEquals(listOf(OnSegment(start, start + 20 * min), OnSegment(t("2026-11-10", "08:00"), t("2026-11-10", "08:10"))), today)
        val yesterday = RhythmMath.segments(events, start - 24 * 60 * min, start, end)
        assertEquals(listOf(OnSegment(t("2026-11-09", "23:40"), start)), yesterday)
    }

    @Test fun aSessionWithoutAnEndRunsToNowOrToTheEndOfAPastDay() {
        val events = listOf(on("2026-11-10", "09:00"))
        assertEquals(listOf(OnSegment(t("2026-11-10", "09:00"), t("2026-11-10", "09:30"))), RhythmMath.segments(events, start, end, t("2026-11-10", "09:30")))
        assertEquals(listOf(OnSegment(t("2026-11-10", "09:00"), end)), RhythmMath.segments(events, start, end, end + 5 * 60 * 60_000L))
    }

    @Test fun theScreenOnAtQueryTimeCountsUpToNow() {
        val now = t("2026-11-10", "12:00")
        val day = RhythmMath.day(listOf(on("2026-11-10", "11:30")), d, z, RhythmOptions(), now)
        assertEquals(30 * min, day.screenOnMillis)
        assertTrue(day.longestFreeMillis!! >= 5L * 60 * min) // 06:00 to 11:30 is the longest stretch
        assertEquals(5L * 60 * min + 30 * min, day.longestFreeMillis!!)
    }

    @Test fun brokenSequencesDoNotInventTime() {
        val events = listOf(off("2026-11-10", "07:00"), on("2026-11-10", "08:00"), on("2026-11-10", "08:05"), off("2026-11-10", "08:10"), off("2026-11-10", "08:20"))
        assertEquals(listOf(OnSegment(t("2026-11-10", "08:00"), t("2026-11-10", "08:10"))), RhythmMath.segments(events, start, end, end))
    }

    @Test fun eventsInAnyOrderGiveTheSameAnswer() {
        val events = listOf(on("2026-11-10", "08:00"), off("2026-11-10", "08:10"))
        assertEquals(RhythmMath.segments(events, start, end, end), RhythmMath.segments(events.reversed(), start, end, end))
    }

    @Test fun pickupsAreKeyguardHiddenEventsAndTheFirstOneGivesTheMinute() {
        val events = listOf(on("2026-11-10", "07:40"), unlock("2026-11-10", "07:41"), off("2026-11-10", "07:50"),
            on("2026-11-10", "09:00"), off("2026-11-10", "09:01"), on("2026-11-10", "12:00"), unlock("2026-11-10", "12:00"), off("2026-11-10", "12:10"))
        val day = RhythmMath.day(events, d, z, RhythmOptions(), end)
        assertEquals(2, day.pickups) // the 09:00 glance never unlocked the phone
        assertEquals(7 * 60 + 41, day.firstPickupMinute)
    }

    @Test fun aPhoneWithNoLockScreenCountsEachScreenOnAsAPickup() {
        val events = listOf(on("2026-11-10", "07:40"), off("2026-11-10", "07:50"), on("2026-11-10", "09:00"), on("2026-11-10", "09:01"), off("2026-11-10", "09:10"))
        val (count, first) = RhythmMath.pickups(events, start, end, end)
        assertEquals(2, count)
        assertEquals(t("2026-11-10", "07:40"), first)
    }

    @Test fun pickupsOutsideTheDayOrAfterNowAreNotCounted() {
        val events = listOf(unlock("2026-11-09", "23:59"), unlock("2026-11-10", "10:00"), unlock("2026-11-10", "15:00"))
        assertEquals(1, RhythmMath.pickups(events, start, end, t("2026-11-10", "12:00")).first)
        assertEquals(0, RhythmMath.pickups(emptyList(), start, end, end).first)
    }

    @Test fun theLongestFreeStretchIsLookedForOnlyInTheWakingHours() {
        // Asleep until 07:00 with the phone off: the night before 06:00 does not count.
        val events = listOf(on("2026-11-10", "07:00"), off("2026-11-10", "07:05"), on("2026-11-10", "13:00"), off("2026-11-10", "13:05"))
        val day = RhythmMath.day(events, d, z, RhythmOptions(), end)
        // 13:05 to 24:00 is 10 h 55 min, longer than 07:05 to 13:00 and than 06:00 to 07:00.
        assertEquals(10L * 60 * min + 55 * min, day.longestFreeMillis!!)
    }

    @Test fun theLongestFreeStretchMeasuresGapsBetweenSegments() {
        val segs = listOf(OnSegment(100 * min, 110 * min), OnSegment(200 * min, 210 * min), OnSegment(205 * min, 300 * min))
        assertEquals(100L * min, RhythmMath.longestFree(segs, 0L, 400 * min)) // 0..100 and 300..400 both 100; the gap 110..200 is 90
        assertEquals(90L * min, RhythmMath.longestFree(segs, 100 * min, 300 * min))
        assertNull(RhythmMath.longestFree(segs, 500 * min, 500 * min))
        assertNull(RhythmMath.longestFree(segs, 600 * min, 500 * min))
    }

    @Test fun beforeTheWindowStartsThereIsNoStretchYet() {
        val day = RhythmMath.day(emptyList(), d, z, RhythmOptions(), t("2026-11-10", "05:00"))
        assertNull(day.longestFreeMillis)
    }

    @Test fun theWindowIsSetByTheOptionsAndAnEndOf24MeansMidnight() {
        val (from, to) = RhythmMath.freeWindow(d, z, RhythmOptions(8, 22), end)
        assertEquals(t("2026-11-10", "08:00"), from)
        assertEquals(t("2026-11-10", "22:00"), to)
        assertEquals(end, RhythmMath.freeWindow(d, z, RhythmOptions(), end + 1_000_000L).second)
    }

    @Test fun optionsReadOldEmptyAndBrokenTextAsDefaultsAndClamp() {
        assertEquals(RhythmOptions(), RhythmOptions.decode(null))
        assertEquals(RhythmOptions(), RhythmOptions.decode(""))
        assertEquals(RhythmOptions(), RhythmOptions.decode("late,early"))
        assertEquals(RhythmOptions(), RhythmOptions.decode("9,10")) // too short
        assertEquals(RhythmOptions(), RhythmOptions.decode("25,30"))
        assertEquals(RhythmOptions(8, 22), RhythmOptions.decode("8,22"))
        assertEquals(RhythmOptions(8, 22), RhythmOptions.decode(RhythmOptions(8, 22).encode()))
        assertEquals(RhythmOptions(6, 9), RhythmOptions().withEnd(0))
        assertEquals(RhythmOptions(21, 24), RhythmOptions().withStart(23))
    }

    @Test fun binsLightOnlyWhenTheScreenWasOnLongEnoughAndRoundTripThroughHex() {
        val segs = listOf(OnSegment(start + 10 * min, start + 10 * min + 30_000L), OnSegment(start + 60 * min, start + 78 * min))
        val bins = RhythmBins.of(segs, start)
        assertFalse(bins[2]) // 30 s is a glance, not a stretch
        assertTrue(bins[12]); assertTrue(bins[13]); assertTrue(bins[14]); assertTrue(bins[15]) // 12:00..13:18 -> bins 12..15
        assertEquals(4, bins.count { it })
        val text = RhythmBins.encode(bins)
        assertEquals(72, text.length)
        assertArrayEquals(bins, RhythmBins.decode(text))
        assertEquals(listOf(12..15), RhythmBins.runs(bins))
    }

    @Test fun badStoredBinsReadAsNothing() {
        assertNull(RhythmBins.decode(null))
        assertNull(RhythmBins.decode(""))
        assertNull(RhythmBins.decode("ff"))
        assertNull(RhythmBins.decode("z".repeat(72)))
        assertNotNull(RhythmBins.decode("0".repeat(72)))
    }

    @Test fun runsMergeAcrossTheEndOfTheDayAndSplitOnGaps() {
        val bins = BooleanArray(RhythmBins.Count)
        bins[0] = true; bins[1] = true; bins[5] = true; bins[287] = true; bins[286] = true
        assertEquals(listOf(0..1, 5..5, 286..287), RhythmBins.runs(bins))
        assertEquals("00:00", RhythmBins.time(0))
        assertEquals("23:55", RhythmBins.time(287))
    }

    @Test fun durationsAreShortAndPlain() {
        assertEquals("—", RhythmMath.duration(null))
        assertEquals("0 min", RhythmMath.duration(0))
        assertEquals("45 min", RhythmMath.duration(45 * min))
        assertEquals("2 h 05 min", RhythmMath.duration(125 * min))
    }
}
