package com.brightblu.parc.core.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure probe-to-archive mapping (T78): storage arithmetic, ranges and cumulative network totals. */
class StatsArchiveMappingTest {

    @Test
    fun `a full reading maps every metric`() {
        val reading = StatsArchiveMapping.reading(
            battery = BatterySample(
                percent = 73,
                chargingState = ChargingState.Charging,
                temperatureC = 34.5f,
            ),
            memory = MemorySample(usedPercent = 61),
            storage = StorageSample(freePercent = 20, usedBytes = 80L, totalBytes = 100L),
            network = NetworkBytes(rxBytes = 4_000_000L, txBytes = 1_000_000L),
        )

        assertEquals(73, reading.batteryPercent)
        assertEquals(34.5f, reading.batteryTemperatureC ?: -1f, 0f)
        assertEquals(61, reading.ramUsedPercent)
        assertEquals(80, reading.storageUsedPercent)
        assertEquals(4_000_000L, reading.networkRxTotalBytes)
        assertEquals(1_000_000L, reading.networkTxTotalBytes)
        assertEquals(false, reading.isEmpty)
    }

    @Test
    fun `storage used percent comes from bytes and is rounded down`() {
        assertEquals(
            66,
            StatsArchiveMapping.storageUsedPercent(
                StorageSample(freePercent = 40, usedBytes = 666L, totalBytes = 1_000L),
            ),
        )
    }

    @Test
    fun `storage falls back to free percent when bytes are unavailable`() {
        assertEquals(
            12,
            StatsArchiveMapping.storageUsedPercent(StorageSample(freePercent = 88)),
        )
        assertNull(StatsArchiveMapping.storageUsedPercent(StorageSample()))
        assertNull(StatsArchiveMapping.storageUsedPercent(null))
        // A total of zero cannot describe a percentage and is not guessed at.
        assertNull(
            StatsArchiveMapping.storageUsedPercent(
                StorageSample(usedBytes = 5L, totalBytes = 0L),
            ),
        )
    }

    @Test
    fun `implausible values become unavailable instead of corrupting the graph`() {
        val reading = StatsArchiveMapping.reading(
            battery = BatterySample(percent = 101, temperatureC = Float.NaN),
            memory = MemorySample(usedPercent = -1),
            storage = StorageSample(freePercent = 200),
            network = NetworkBytes(rxBytes = -1L, txBytes = 5L),
        )

        assertNull(reading.batteryPercent)
        assertNull(reading.batteryTemperatureC)
        assertNull(reading.ramUsedPercent)
        assertNull(reading.storageUsedPercent)
        assertNull(reading.networkRxTotalBytes)
        assertEquals(5L, reading.networkTxTotalBytes)
    }

    @Test
    fun `a missing source contributes nothing`() {
        val reading = StatsArchiveMapping.reading(null, null, null, null)
        assertEquals(StatsArchiveReading(), reading)
        assertEquals(true, reading.isEmpty)
    }
}
