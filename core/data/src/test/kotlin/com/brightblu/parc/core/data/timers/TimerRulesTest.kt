package com.brightblu.parc.core.data.timers

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerRulesTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L

    @Test fun startCreatesRunningTimerWithAbsoluteEnd() {
        val book = TimerBook().start(5 * min, t0)!!
        val t = book.find(1)!!
        assertEquals(TimerStatus.Running, t.status)
        assertEquals(t0 + 5 * min, t.endAtMs)
        assertEquals(5 * min, t.left(t0))
        assertEquals(3 * min, t.left(t0 + 2 * min))
    }

    @Test fun pauseKeepsRemainingAndResumeMovesEnd() {
        val book = TimerBook().start(10 * min, t0)!!.pause(1, t0 + 4 * min)
        val p = book.find(1)!!
        assertEquals(TimerStatus.Paused, p.status)
        assertEquals(6 * min, p.remainingMs)
        // Paused time does not count.
        assertEquals(6 * min, p.left(t0 + 100 * min))
        val r = book.resume(1, t0 + 20 * min).find(1)!!
        assertEquals(TimerStatus.Running, r.status)
        assertEquals(t0 + 26 * min, r.endAtMs)
    }

    @Test fun finishAtEndTimeAndNotBefore() {
        val book = TimerBook().start(min, t0)!!
        assertEquals(TimerStatus.Running, book.settle(t0 + min - 1).find(1)!!.status)
        val done = book.settle(t0 + min)
        assertEquals(TimerStatus.Finished, done.find(1)!!.status)
        assertEquals(0L, done.find(1)!!.left(t0 + 5 * min))
        assertEquals(listOf(1), done.newlyFinished(book))
        // Settling again is a no-op, and nothing is newly finished the second time.
        assertEquals(done, done.settle(t0 + 9 * min))
        assertTrue(done.settle(t0 + 9 * min).newlyFinished(done).isEmpty())
    }

    @Test fun effectiveStatusShowsFinishedBeforeSettle() {
        val t = TimerBook().start(min, t0)!!.find(1)!!
        assertEquals(TimerStatus.Finished, t.effective(t0 + 2 * min))
    }

    @Test fun pauseAfterEndFinishesInstead() {
        val book = TimerBook().start(min, t0)!!.pause(1, t0 + 2 * min)
        assertEquals(TimerStatus.Finished, book.find(1)!!.status)
    }

    @Test fun resetReturnsToFullDurationNotRunning() {
        val book = TimerBook().start(5 * min, t0)!!.pause(1, t0 + min).reset(1)
        val t = book.find(1)!!
        assertEquals(TimerStatus.Idle, t.status)
        assertEquals(5 * min, t.remainingMs)
        val again = book.resume(1, t0 + 10 * min).find(1)!!
        assertEquals(t0 + 15 * min, again.endAtMs)
        assertEquals(TimerStatus.Idle, TimerBook().start(min, t0)!!.settle(t0 + 2 * min).reset(1).find(1)!!.status)
    }

    @Test fun multipleTimersAreIndependentAndLimitedToFive() {
        var book = TimerBook()
        for (i in 1..5) book = book.start(i * min, t0)!!
        assertTrue(book.isFull)
        assertNull(book.start(min, t0))
        val paused = book.pause(2, t0 + 30_000)
        assertEquals(TimerStatus.Paused, paused.find(2)!!.status)
        assertEquals(TimerStatus.Running, paused.find(3)!!.status)
        // Only the 1 and 2 minute timers are done at 2 minutes; 2 is paused so it stays.
        val settled = paused.settle(t0 + 2 * min)
        assertEquals(listOf(1), settled.newlyFinished(paused))
        assertEquals(t0 + min, TimerRules.nextEnd(paused.settle(t0)))
    }

    @Test fun deleteFreesTheSlot() {
        var book = TimerBook().start(min, t0)!!.start(2 * min, t0)!!.start(3 * min, t0)!!
        book = book.delete(2)
        assertEquals(listOf(1, 3), book.timers.map { it.id })
        book = book.start(9 * min, t0)!!
        assertEquals(listOf(1, 2, 3), book.timers.map { it.id })
        assertEquals(9 * min, book.find(2)!!.totalMs)
    }

    @Test fun rejectsOutOfRangeDurations() {
        assertNull(TimerBook().start(0, t0))
        assertNull(TimerBook().start(TimerRules.MaxMs + 1, t0))
        assertNotNull(TimerBook().start(TimerRules.MinMs, t0))
    }

    @Test fun restoreAfterRestartKeepsAbsoluteEndTimes() {
        val book = TimerBook().start(10 * min, t0)!!.start(5 * min, t0)!!.pause(2, t0 + min)
        val restored = TimerCodec.decodeBook(TimerCodec.encodeBook(book))
        assertEquals(book, restored)
        // The process was dead for 7 minutes: the 10 min timer has 3 left, the 5 min one is paused.
        val later = t0 + 7 * min
        assertEquals(3 * min, restored.find(1)!!.left(later))
        assertEquals(4 * min, restored.find(2)!!.left(later))
        // And after 11 minutes the 10 min timer is finished by settle.
        val done = restored.settle(t0 + 11 * min)
        assertEquals(TimerStatus.Finished, done.find(1)!!.status)
        assertEquals(TimerStatus.Paused, done.find(2)!!.status)
    }

    @Test fun codecDropsHostileValues() {
        val raw = "9,60000,Running,5,60000;1,notanumber,Idle,0,0;2,60000,Bogus,0,0;3,60000,Running,0,60000;" +
            "4,60000,Paused,0,99999999;4,60000,Idle,0,60000;;1,1,Idle,0,1"
        val book = TimerCodec.decodeBook(raw)
        // 9: id out of range; 1: bad number; 2: bad status; 3: running without an end; 4 survives once, remaining clamped; last: below min.
        assertEquals(listOf(4), book.timers.map { it.id })
        assertEquals(60_000L, book.find(4)!!.remainingMs)
        assertEquals(TimerBook(), TimerCodec.decodeBook(null))
        assertEquals(TimerBook(), TimerCodec.decodeBook("garbage"))
    }

    @Test fun customDurationClamps() {
        assertEquals(1_000L, TimerRules.custom(0, 0, 0))
        assertEquals(90 * 60_000L, TimerRules.custom(1, 30, 0))
        assertEquals(TimerRules.MaxMs, TimerRules.custom(99, 99, 99).coerceAtMost(TimerRules.MaxMs))
        assertEquals(23 * 3600_000L + 59 * 60_000L + 59_000L, TimerRules.custom(40, 70, 70))
    }

    @Test fun notificationAskRule() {
        assertTrue(TimerRules.shouldAskNotifications(granted = false, alreadyAsked = false))
        assertFalse(TimerRules.shouldAskNotifications(granted = false, alreadyAsked = true))
        assertFalse(TimerRules.shouldAskNotifications(granted = true, alreadyAsked = false))
    }

    @Test fun noticesNameWhatIsLimited() {
        assertNull(TimerNotices.of(true, true))
        assertTrue(TimerNotices.of(false, true)!!.contains("Notifications are off"))
        assertTrue(TimerNotices.of(true, false)!!.contains("late"))
        assertTrue(TimerNotices.of(false, false)!!.contains("late") && TimerNotices.of(false, false)!!.contains("off"))
    }

    // ---- stopwatch ----

    @Test fun stopwatchRunsPausesAndResumes() {
        var sw = Stopwatch().start(t0)
        assertEquals(2_500L, sw.elapsed(t0 + 2_500))
        sw = sw.stop(t0 + 4_000)
        assertEquals(4_000L, sw.elapsed(t0 + 100_000))
        sw = sw.start(t0 + 200_000)
        assertEquals(6_000L, sw.elapsed(t0 + 202_000))
        assertTrue(sw.reset().isPristine)
    }

    @Test fun stopwatchLaps() {
        var sw = Stopwatch().start(t0)
        assertEquals(sw, sw.lap(t0)) // zero time is ignored
        sw = sw.lap(t0 + 3_000).lap(t0 + 10_000).lap(t0 + 11_500)
        assertEquals(listOf(3_000L, 10_000L, 11_500L), sw.laps)
        assertEquals(3_000L, sw.split(0))
        assertEquals(7_000L, sw.split(1))
        assertEquals(1_500L, sw.split(2))
        assertEquals(0L, sw.split(7))
        // A stopped stopwatch takes no lap.
        val stopped = sw.stop(t0 + 12_000)
        assertEquals(stopped, stopped.lap(t0 + 13_000))
        assertEquals(12_000L, stopped.elapsed(t0 + 99_000))
    }

    @Test fun lapsAreCapped() {
        var sw = Stopwatch().start(t0)
        for (i in 1..150) sw = sw.lap(t0 + i * 1000L)
        assertEquals(TimerRules.MaxLaps, sw.laps.size)
    }

    @Test fun stopwatchSurvivesRestart() {
        val sw = Stopwatch().start(t0).lap(t0 + 5_000)
        val back = TimerCodec.decodeStopwatch(TimerCodec.encodeStopwatch(sw))
        assertEquals(sw, back)
        assertEquals(9_000L, back.elapsed(t0 + 9_000))
        val stopped = sw.stop(t0 + 8_000)
        assertEquals(stopped, TimerCodec.decodeStopwatch(TimerCodec.encodeStopwatch(stopped)))
        assertTrue(TimerCodec.decodeStopwatch("junk").isPristine)
        assertTrue(TimerCodec.decodeStopwatch("1,0,0|").isPristine)
        assertTrue(TimerCodec.decodeStopwatch(null).isPristine)
    }

    // ---- formatting ----

    @Test fun countdownFormat() {
        assertEquals("0:00", TimerFormat.countdown(0))
        assertEquals("0:01", TimerFormat.countdown(1))
        assertEquals("0:05", TimerFormat.countdown(4_100))
        assertEquals("9:59", TimerFormat.countdown(9 * 60_000L + 59_000L))
        assertEquals("1:02:03", TimerFormat.countdown(3_723_000L))
        assertEquals("0:00", TimerFormat.countdown(-5))
    }

    @Test fun stopwatchFormat() {
        assertEquals("00:00.00", TimerFormat.stopwatch(0))
        assertEquals("00:07.42", TimerFormat.stopwatch(7_429))
        assertEquals("12:00.99", TimerFormat.stopwatch(720_999))
        assertEquals("1:02:03.45", TimerFormat.stopwatch(3_723_450))
    }

    @Test fun durationAndPresetLabels() {
        assertEquals("1 min", TimerFormat.presetLabel(1))
        assertEquals("30 min", TimerFormat.presetLabel(30))
        assertEquals("1 h 30 min", TimerFormat.duration(90 * 60_000L))
        assertEquals("45 s", TimerFormat.duration(45_000))
        assertEquals("2 min 5 s", TimerFormat.duration(125_000))
        assertEquals("Lap 3", TimerFormat.lapName(2))
        assertEquals("Running", TimerFormat.statusText(TimerStatus.Running))
        assertEquals("Done", TimerFormat.statusText(TimerStatus.Finished))
    }

    @Test fun nextAlarmLine() {
        val zone = ZoneId.of("UTC")
        // 2027-01-15 07:30 UTC is a Friday.
        val trigger = java.time.ZonedDateTime.of(2027, 1, 15, 7, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Fri 07:30", TimerFormat.nextAlarm(trigger, zone, true, Locale.ENGLISH))
        assertEquals("Fri 7:30 AM", TimerFormat.nextAlarm(trigger, zone, false, Locale.ENGLISH))
        assertEquals("No alarm set", TimerFormat.nextAlarm(null, zone, true, Locale.ENGLISH))
    }
}
