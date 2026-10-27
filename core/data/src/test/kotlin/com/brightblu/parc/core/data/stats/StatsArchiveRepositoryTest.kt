package com.brightblu.parc.core.data.stats

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The T78 archive recorder on virtual time: one sample every 5 minutes, one DataStore write at most
 * every 15 minutes, restart awareness and failure isolation. The clock is the test scheduler, so
 * `advanceTimeBy` moves both the coroutine delay and the epoch the repository reads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsArchiveRepositoryTest {

    private val start = 1_700_000_000_000L
    private val sampleInterval = StatsArchiveRepository.DefaultSampleIntervalMillis

    private class FakeArchiveStore(initial: StoredStatsArchive = StoredStatsArchive()) : StatsArchiveStore {
        var document: StoredStatsArchive = initial
        var writes = 0
        var attempts = 0
        var failedWrites = 0
        var loadError: Throwable? = null

        override suspend fun load(): StoredStatsArchive {
            loadError?.let { throw it }
            return document
        }

        override suspend fun save(archive: StatsArchive, writtenAtMillis: Long) {
            attempts++
            if (failedWrites > 0) {
                failedWrites--
                throw IOException("write failed")
            }
            writes++
            document = StoredStatsArchive(archive, writtenAtMillis)
        }
    }

    private inner class Fixture(scope: TestScope) {
        val store = FakeArchiveStore()
        var sampleCalls = 0
        var sampleFails = false
        var reading: StatsArchiveReading? = StatsArchiveReading(batteryPercent = 50)
        val repository = StatsArchiveRepository(
            store = store,
            scope = scope.backgroundScope,
            clock = { start + scope.testScheduler.currentTime },
            sample = {
                sampleCalls++
                if (sampleFails) throw IOException("probe failed")
                reading
            },
            sampleIntervalMillis = sampleInterval,
        )
    }

    /** Advances virtual time to [millis] after the fixture started, then runs due tasks. */
    private fun TestScope.advanceTo(millis: Long) {
        advanceTimeBy(millis - testScheduler.currentTime)
        runCurrent()
    }

    private fun TestScope.advanceToMinutes(minutes: Long) = advanceTo(minutes * 60_000L)

    private fun Fixture.series(metric: StatsArchiveMetric): List<StatsArchivePoint> =
        repository.archive.value.series(metric)

    @Test
    fun `the first sample is taken and persisted immediately`() = runTest {
        val fixture = Fixture(this)
        runCurrent()

        assertEquals(1, fixture.sampleCalls)
        assertEquals(1, fixture.store.writes)
        assertEquals(1, fixture.series(StatsArchiveMetric.BatteryPercent).size)
        assertEquals(50.0, fixture.series(StatsArchiveMetric.BatteryPercent).single().value, 0.0)
    }

    @Test
    fun `samples arrive every five minutes and not before`() = runTest {
        val fixture = Fixture(this)
        runCurrent()
        assertEquals(1, fixture.sampleCalls)

        advanceTo(sampleInterval - 1_000L)
        assertEquals(1, fixture.sampleCalls)

        advanceTo(sampleInterval)
        assertEquals(2, fixture.sampleCalls)

        advanceTo(sampleInterval * 2)
        assertEquals(3, fixture.sampleCalls)
        assertEquals(3, fixture.series(StatsArchiveMetric.BatteryPercent).size)
    }

    @Test
    fun `the archive is written at most once every fifteen minutes`() = runTest {
        val fixture = Fixture(this)
        runCurrent()
        assertEquals(1, fixture.store.writes)

        advanceToMinutes(5)
        assertEquals(1, fixture.store.writes)
        advanceToMinutes(10)
        assertEquals(1, fixture.store.writes)
        advanceToMinutes(15)
        assertEquals(2, fixture.store.writes)
        advanceToMinutes(20)
        assertEquals(2, fixture.store.writes)
        advanceToMinutes(30)
        assertEquals(3, fixture.store.writes)

        // The document holds the live points, not just the first sample.
        assertEquals(7, fixture.store.document.archive.pointCount)
        assertEquals(30 * 60_000L, fixture.store.document.writtenAtMillis?.minus(start))
    }

    @Test
    fun `a restart restores the archive without writing before the gate`() = runTest {
        val previous = StatsArchive.Empty.record(
            start - 60_000L,
            StatsArchiveReading(batteryPercent = 10, ramUsedPercent = 20),
        )
        val store = FakeArchiveStore(StoredStatsArchive(previous, writtenAtMillis = start - 60_000L))
        var calls = 0
        val repository = StatsArchiveRepository(
            store = store,
            scope = backgroundScope,
            clock = { start + testScheduler.currentTime },
            sample = {
                calls++
                StatsArchiveReading(batteryPercent = 11)
            },
            sampleIntervalMillis = sampleInterval,
        )
        runCurrent()

        assertEquals(1, calls)
        assertEquals(0, store.writes)
        assertEquals(2, repository.archive.value.series(StatsArchiveMetric.BatteryPercent).size)
        assertEquals(20.0, repository.archive.value.latest(StatsArchiveMetric.RamPercent)?.value ?: 0.0, 0.0)

        // The stored write was 1 minute before this process started, so the gate opens 14 minutes
        // in, but a write is only attempted on a sample: the one at 15 minutes takes it.
        advanceToMinutes(14)
        assertEquals(0, store.writes)
        advanceToMinutes(15)
        assertEquals(1, store.writes)
    }

    @Test
    fun `a failed write is retried on the next sample`() = runTest {
        val fixture = Fixture(this)
        fixture.store.failedWrites = 1
        runCurrent()

        assertEquals(1, fixture.store.attempts)
        assertEquals(0, fixture.store.writes)

        advanceToMinutes(5)
        assertEquals(2, fixture.store.attempts)
        assertEquals(1, fixture.store.writes)
        assertEquals(2, fixture.store.document.archive.pointCount)
    }

    @Test
    fun `a throwing probe loses one tick and the loop continues`() = runTest {
        val fixture = Fixture(this)
        fixture.sampleFails = true
        runCurrent()

        assertEquals(1, fixture.sampleCalls)
        assertTrue(fixture.repository.archive.value.isEmpty)
        assertEquals(0, fixture.store.writes)

        fixture.sampleFails = false
        advanceToMinutes(5)
        assertEquals(2, fixture.sampleCalls)
        assertEquals(1, fixture.store.writes)
        assertEquals(1, fixture.series(StatsArchiveMetric.BatteryPercent).size)
    }

    @Test
    fun `an all-unavailable reading appends nothing and writes nothing`() = runTest {
        val fixture = Fixture(this)
        fixture.reading = StatsArchiveReading()
        runCurrent()
        advanceToMinutes(5)

        assertEquals(2, fixture.sampleCalls)
        assertTrue(fixture.repository.archive.value.isEmpty)
        assertEquals(0, fixture.store.writes)
    }

    @Test
    fun `a failed load starts empty and still samples`() = runTest {
        val fixture = Fixture(this)
        fixture.store.loadError = IOException("read failed")
        runCurrent()

        assertEquals(1, fixture.sampleCalls)
        assertEquals(1, fixture.series(StatsArchiveMetric.BatteryPercent).size)
        assertEquals(1, fixture.store.writes)
    }
}
