package com.brightblu.parc.core.data.rhythm

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T188: sampling usage events into day records on a fake source and a plain clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class RhythmRepositoryTest {
    private class FakeSource(var granted: Boolean = true, var events: List<RhythmEvent>? = emptyList()) : RhythmSource {
        var queries = 0
        override fun accessGranted() = granted
        override fun events(fromMillis: Long, toMillis: Long): List<RhythmEvent>? { queries++; return events?.filter { it.atMillis in fromMillis..toMillis } }
    }

    private class MemorySink : RhythmSink {
        val days = HashMap<LocalDate, RhythmRecord>()
        override suspend fun write(date: LocalDate, record: RhythmRecord) { days[date] = record }
    }

    private fun t(date: String, time: String) = Instant.parse("${date}T$time:00Z").toEpochMilli()
    private fun d(date: String) = LocalDate.parse(date)
    private fun on(date: String, time: String) = RhythmEvent(RhythmEvent.Kind.ScreenOn, t(date, time))
    private fun off(date: String, time: String) = RhythmEvent(RhythmEvent.Kind.ScreenOff, t(date, time))
    private fun unlock(date: String, time: String) = RhythmEvent(RhythmEvent.Kind.KeyguardHidden, t(date, time))

    private fun TestScope.repo(source: FakeSource, sink: MemorySink, clock: () -> Long) =
        RhythmRepository(source, sink, backgroundScope, clock, { ZoneOffset.UTC }, { RhythmOptions() }, startTicker = false)

    @Test fun noUsageAccessWritesNothingAndSaysSo() = runTest {
        val source = FakeSource(granted = false); val sink = MemorySink()
        val repo = repo(source, sink) { t("2026-11-10", "12:00") }
        repo.sample()
        assertEquals(false, repo.accessGranted.value)
        assertTrue(sink.days.isEmpty())
        assertEquals(0, source.queries)
    }

    @Test fun noEventsAtAllWritesNothing() = runTest {
        val sink = MemorySink()
        repo(FakeSource(), sink) { t("2026-11-10", "12:00") }.sample()
        assertTrue(sink.days.isEmpty())
    }

    @Test fun aDayInProgressIsWrittenWithTheScreenOnAtQueryTime() = runTest {
        val source = FakeSource(events = listOf(on("2026-11-09", "20:00"), off("2026-11-09", "20:30"),
            on("2026-11-10", "07:40"), unlock("2026-11-10", "07:41"), off("2026-11-10", "07:50"), on("2026-11-10", "11:55")))
        val sink = MemorySink()
        repo(source, sink) { t("2026-11-10", "12:00") }.sample()
        val today = sink.days.getValue(d("2026-11-10"))
        assertEquals(1, today.pickups)
        assertEquals(7 * 60 + 41, today.firstPickupMinute)
        val bins = RhythmBins.decode(today.bins)!!
        assertTrue(bins[(7 * 60 + 40) / 5]) // the morning session
        assertTrue(bins[(11 * 60 + 55) / 5]) // still on
        // 07:50 to 11:55 is the longest stretch from 06:00 (06:00 to 07:40 is shorter).
        assertEquals((4L * 60 + 5) * 60_000L, today.phoneFreeMillis)
    }

    @Test fun anEarlierDayIsWrittenOnlyWhenAndroidStillCoversItWhole() = runTest {
        // The oldest event is on 2026-11-08 at 10:00, so the 8th is partly covered; the 9th is whole.
        val source = FakeSource(events = listOf(on("2026-11-08", "10:00"), off("2026-11-08", "10:10"), on("2026-11-09", "09:00"), off("2026-11-09", "09:20"), on("2026-11-10", "08:00")))
        val sink = MemorySink()
        repo(source, sink) { t("2026-11-10", "09:00") }.sample()
        assertNull(sink.days[d("2026-11-08")])
        assertEquals(4, RhythmBins.decode(sink.days.getValue(d("2026-11-09")).bins)!!.count { it })
        assertFalse(sink.days.getValue(d("2026-11-10")).bins.isEmpty())
    }

    @Test fun aSecondSampleRecomputesAndASessionAcrossMidnightLandsOnBothDays() = runTest {
        val events = listOf(on("2026-11-08", "20:00"), off("2026-11-08", "20:10"), on("2026-11-09", "10:00"), off("2026-11-09", "10:10"), on("2026-11-09", "23:50"), off("2026-11-10", "00:10"))
        val source = FakeSource(events = events); val sink = MemorySink()
        var now = t("2026-11-10", "00:05")
        val repo = repo(source, sink) { now }
        repo.sample()
        now = t("2026-11-10", "08:00")
        repo.sample()
        val last = RhythmBins.decode(sink.days.getValue(d("2026-11-09")).bins)!!
        assertTrue(last[287]) // 23:55
        val today = RhythmBins.decode(sink.days.getValue(d("2026-11-10")).bins)!!
        assertTrue(today[0]) // 00:00..00:10
        assertEquals(2, today.count { it })
    }

    @Test fun anAnswerOfNullFromAndroidChangesNothing() = runTest {
        val sink = MemorySink()
        repo(FakeSource(events = null), sink) { t("2026-11-10", "12:00") }.sample()
        assertTrue(sink.days.isEmpty())
    }
}
