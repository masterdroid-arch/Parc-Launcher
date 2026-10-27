package com.brightblu.parc.core.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure T78 archive ring buffer: per-metric gaps, capacity and immutability. */
class StatsArchiveTest {

    private fun reading(
        battery: Int? = 50,
        temperature: Float? = 30f,
        ram: Int? = 40,
        storage: Int? = 60,
        rx: Long? = 1_000L,
        tx: Long? = 500L,
    ) = StatsArchiveReading(
        batteryPercent = battery,
        batteryTemperatureC = temperature,
        ramUsedPercent = ram,
        storageUsedPercent = storage,
        networkRxTotalBytes = rx,
        networkTxTotalBytes = tx,
    )

    @Test
    fun `one reading lands in every matching series`() {
        val archive = StatsArchive.Empty.record(1_000L, reading())

        assertEquals(6, archive.seriesByMetric.size)
        assertEquals(
            listOf(StatsArchiveMetric.BatteryPercent, StatsArchiveMetric.BatteryTemperature),
            StatsArchiveMetric.entries.take(2),
        )
        assertEquals(StatsArchivePoint(1_000L, 50.0), archive.latest(StatsArchiveMetric.BatteryPercent))
        assertEquals(StatsArchivePoint(1_000L, 30.0), archive.latest(StatsArchiveMetric.BatteryTemperature))
        assertEquals(StatsArchivePoint(1_000L, 1_000.0), archive.latest(StatsArchiveMetric.NetworkRxTotalBytes))
        assertEquals(6, archive.pointCount)
    }

    @Test
    fun `a null field leaves its series alone without shifting another`() {
        val archive = StatsArchive.Empty
            .record(1_000L, reading())
            .record(2_000L, reading(battery = null, temperature = null))
            .record(3_000L, StatsArchiveReading())

        assertEquals(1, archive.series(StatsArchiveMetric.BatteryPercent).size)
        assertEquals(1, archive.series(StatsArchiveMetric.BatteryTemperature).size)
        assertEquals(2, archive.series(StatsArchiveMetric.RamPercent).size)
        assertEquals(2_000L, archive.series(StatsArchiveMetric.RamPercent).last().atMillis)
    }

    @Test
    fun `an all-null reading returns the same archive instance`() {
        val archive = StatsArchive.Empty.record(1_000L, reading())
        assertSame(archive, archive.record(2_000L, StatsArchiveReading()))
    }

    @Test
    fun `each series keeps at most 288 points and drops the oldest`() {
        var archive = StatsArchive.Empty
        for (index in 0 until StatsArchive.Capacity + 12) {
            archive = archive.record(index.toLong(), StatsArchiveReading(batteryPercent = 50))
        }

        val series = archive.series(StatsArchiveMetric.BatteryPercent)
        assertEquals(StatsArchive.Capacity, series.size)
        assertEquals(12L, series.first().atMillis)
        assertEquals((StatsArchive.Capacity + 11).toLong(), series.last().atMillis)
        assertTrue(archive.pointCount == StatsArchive.Capacity)
    }

    @Test
    fun `series are independent and the map is not mutated by later records`() {
        val first = StatsArchive.Empty.record(1_000L, StatsArchiveReading(batteryPercent = 10))
        val second = first.record(2_000L, StatsArchiveReading(batteryPercent = 11))

        assertEquals(1, first.series(StatsArchiveMetric.BatteryPercent).size)
        assertEquals(2, second.series(StatsArchiveMetric.BatteryPercent).size)
        assertEquals(10.0, first.latest(StatsArchiveMetric.BatteryPercent)?.value ?: -1.0, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a reading outside the honest ranges is refused`() {
        StatsArchiveReading(batteryPercent = 101)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-finite temperature is refused`() {
        StatsArchiveReading(batteryTemperatureC = Float.NaN)
    }

    @Test
    fun `a percent reading is stored exactly and byte totals keep their magnitude`() {        val archive = StatsArchive.Empty.record(
            5_000L,
            StatsArchiveReading(networkRxTotalBytes = 9_876_543_210L),
        )
        assertEquals(9_876_543_210.0, archive.latest(StatsArchiveMetric.NetworkRxTotalBytes)?.value ?: 0.0, 0.0)
    }

    @Test
    fun `bigger readings survive the byte-total graph`() {
        // A Float cannot represent this exactly; the Double model can.
        val total = 16_777_219L
        val archive = StatsArchive.Empty.record(1L, StatsArchiveReading(networkTxTotalBytes = total))
        assertEquals(total.toDouble(), archive.latest(StatsArchiveMetric.NetworkTxTotalBytes)?.value ?: 0.0, 0.0)
    }
}
