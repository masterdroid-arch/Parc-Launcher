package com.brightblu.parc.core.data.steps

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T187: counting into the ledger on a fake sensor, an in-memory baseline store and a plain clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class StepsRepositoryTest {
    private class MemoryStore(var text: String? = null) : StepStore {
        override suspend fun read() = text
        override suspend fun write(text: String) { this.text = text }
    }

    private class MemorySink : StepSink {
        val days = HashMap<LocalDate, Int>()
        var adds = 0
        override fun saved(date: LocalDate) = days[date]
        override suspend fun add(date: LocalDate, delta: Int) { days[date] = (days[date] ?: 0) + delta; adds++ }
    }

    private class FakeSource(var sensor: Boolean = true, var granted: Boolean = true) : StepSource {
        var next: StepReading? = null
        override fun hasSensor() = sensor
        override fun granted() = granted
        override fun updates(): Flow<StepReading> = emptyFlow()
        override suspend fun readOnce() = if (sensor && granted) next else null
    }

    private fun utc(date: String, time: String) = Instant.parse("${date}T$time:00Z").toEpochMilli()
    private fun d(date: String) = LocalDate.parse(date)

    private fun TestScope.repo(store: MemoryStore, source: FakeSource, sink: MemorySink, clock: () -> Long, flushMillis: Long = 0L) =
        StepsRepository(store, source, sink, backgroundScope, clock, { ZoneOffset.UTC }, flushMillis = flushMillis, startTicker = false)

    @Test fun firstReadingCreatesTheDayWithZeroAndLaterReadingsAdd() = runTest {
        val sink = MemorySink(); val source = FakeSource(); var now = utc("2026-11-10", "08:00")
        val repo = repo(MemoryStore(), source, sink, { now })
        source.next = StepReading(50_000, 1_000); repo.sample()
        assertEquals(0, sink.days[d("2026-11-10")])
        now = utc("2026-11-10", "09:00"); source.next = StepReading(50_450, 2_000); repo.sample()
        assertEquals(450, sink.days[d("2026-11-10")])
    }

    @Test fun rebootLosesNothingAlreadyCounted() = runTest {
        val sink = MemorySink(); val source = FakeSource(); var now = utc("2026-11-10", "08:00")
        val repo = repo(MemoryStore(), source, sink, { now })
        source.next = StepReading(1_000, 5_000_000); repo.sample()
        now = utc("2026-11-10", "10:00"); source.next = StepReading(1_300, 5_100_000); repo.sample()
        assertEquals(300, sink.days[d("2026-11-10")])
        // Reboot: counter restarts, 40 steps walked since.
        now = utc("2026-11-10", "12:00"); source.next = StepReading(40, 60_000); repo.sample()
        assertEquals(340, sink.days[d("2026-11-10")])
        now = utc("2026-11-10", "13:00"); source.next = StepReading(100, 3_000_000); repo.sample()
        assertEquals(400, sink.days[d("2026-11-10")])
    }

    @Test fun readingsStraddlingMidnightGoToTheLaterDay() = runTest {
        val sink = MemorySink(); val source = FakeSource(); var now = utc("2026-11-10", "23:50")
        val repo = repo(MemoryStore(), source, sink, { now })
        source.next = StepReading(2_000, 1_000); repo.sample()
        now = utc("2026-11-11", "00:10"); source.next = StepReading(2_100, 2_000); repo.sample()
        assertEquals(0, sink.days[d("2026-11-10")])
        assertEquals(100, sink.days[d("2026-11-11")])
    }

    @Test fun aRestartContinuesFromTheStoredBaseline() = runTest {
        val store = MemoryStore(); val sink = MemorySink(); val source = FakeSource(); var now = utc("2026-11-10", "08:00")
        repo(store, source, sink, { now }).also { source.next = StepReading(5_000, 1_000); it.sample() }
        // A new process over the same store: the first reading after it counts from the saved baseline.
        now = utc("2026-11-10", "09:00"); source.next = StepReading(5_250, 9_000)
        repo(store, source, sink, { now }).sample()
        assertEquals(250, sink.days[d("2026-11-10")])
    }

    @Test fun nothingIsCountedWithoutSensorOrPermission() = runTest {
        val sink = MemorySink(); val source = FakeSource(sensor = false); val now = utc("2026-11-10", "08:00")
        val repo = repo(MemoryStore(), source, sink, { now })
        source.next = StepReading(1, 1); repo.sample()
        assertEquals(false, repo.live.value.sensor)
        assertTrue(sink.days.isEmpty())
        source.sensor = true; source.granted = false; repo.sample()
        assertEquals(true, repo.live.value.sensor)
        assertFalse(repo.live.value.granted)
        assertTrue(sink.days.isEmpty())
        source.granted = true; repo.sample()
        assertTrue(repo.live.value.granted)
        assertEquals(0, sink.days[d("2026-11-10")])
    }

    @Test fun writesAreGatheredBetweenFlushesAndShownAsPending() = runTest {
        val sink = MemorySink(); val source = FakeSource(); var now = utc("2026-11-10", "08:00")
        val repo = repo(MemoryStore(), source, sink, { now }, flushMillis = 30_000L)
        source.next = StepReading(100, 1_000); repo.sample()
        val writes = sink.adds
        now += 5_000L; repo.record(StepReading(110, 6_000))
        now += 5_000L; repo.record(StepReading(125, 11_000))
        assertEquals(writes, sink.adds)
        assertEquals(StepPending(d("2026-11-10"), 25), repo.live.value.pending)
        now += 30_000L; repo.record(StepReading(130, 41_000))
        assertEquals(30, sink.days[d("2026-11-10")])
        assertNull(repo.live.value.pending)
    }

    @Test fun flushSavesWhatIsPending() = runTest {
        val sink = MemorySink(); val source = FakeSource(); var now = utc("2026-11-10", "08:00")
        val repo = repo(MemoryStore(), source, sink, { now }, flushMillis = 60_000L)
        source.next = StepReading(100, 1_000); repo.sample()
        now += 1_000L; repo.record(StepReading(160, 2_000))
        assertEquals(0, sink.days[d("2026-11-10")])
        repo.flush()
        assertEquals(60, sink.days[d("2026-11-10")])
    }
}
