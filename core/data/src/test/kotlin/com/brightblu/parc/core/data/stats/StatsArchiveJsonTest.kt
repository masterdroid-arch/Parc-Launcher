package com.brightblu.parc.core.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tolerant T78 archive document: round trip, sorting, de-duplication, trimming and corruption. */
class StatsArchiveJsonTest {

    private fun archive(vararg points: Pair<Long, StatsArchiveReading>): StatsArchive =
        points.fold(StatsArchive.Empty) { acc, (at, reading) -> acc.record(at, reading) }

    @Test
    fun `a round trip preserves values and the write timestamp`() {
        val original = archive(
            1_000L to StatsArchiveReading(
                batteryPercent = 80,
                batteryTemperatureC = 31.5f,
                ramUsedPercent = 44,
                storageUsedPercent = 70,
                networkRxTotalBytes = 5_000_000L,
                networkTxTotalBytes = 900_000L,
            ),
            2_000L to StatsArchiveReading(batteryPercent = 79),
        )

        val decoded = StatsArchiveJson.decode(StatsArchiveJson.encode(original, 12_345L))

        assertEquals(original.series(StatsArchiveMetric.BatteryPercent), decoded.archive.series(StatsArchiveMetric.BatteryPercent))
        assertEquals(31.5, decoded.archive.latest(StatsArchiveMetric.BatteryTemperature)?.value ?: -1.0, 0.0)
        assertEquals(5_000_000.0, decoded.archive.latest(StatsArchiveMetric.NetworkRxTotalBytes)?.value ?: 0.0, 0.0)
        assertEquals(12_345L, decoded.writtenAtMillis)
    }

    @Test
    fun `missing, blank and corrupt documents read as empty`() {
        assertEquals(StoredStatsArchive(), StatsArchiveJson.decode(null))
        assertEquals(StoredStatsArchive(), StatsArchiveJson.decode(""))
        assertEquals(StoredStatsArchive(), StatsArchiveJson.decode("{"))
        assertEquals(StoredStatsArchive(), StatsArchiveJson.decode("[]"))
    }

    @Test
    fun `unknown metric names are skipped and known ones still load`() {
        val decoded = StatsArchiveJson.decode(
            """{
                "writtenAtMillis": 900,
                "series": {
                    "FutureMetric": [{"t": 1, "v": 2}],
                    "RamPercent": [{"t": 10, "v": 55}]
                }
            }""",
        )

        assertEquals(1, decoded.archive.series(StatsArchiveMetric.RamPercent).size)
        assertEquals(55.0, decoded.archive.latest(StatsArchiveMetric.RamPercent)?.value ?: 0.0, 0.0)
        assertEquals(900L, decoded.writtenAtMillis)
    }

    @Test
    fun `points are sorted, duplicates keep the last value and the oldest are trimmed`() {
        val points = buildString {
            append("""{"writtenAtMillis": 5, "series": {"BatteryPercent": [""")
            append("""{"t": 3, "v": 30},""")
            append("""{"t": 1, "v": 10},""")
            append("""{"t": 3, "v": 33}""")
            append("]}}")
        }
        val sorted = StatsArchiveJson.decode(points)
        assertEquals(
            listOf(1L to 10.0, 3L to 33.0),
            sorted.archive.series(StatsArchiveMetric.BatteryPercent).map { it.atMillis to it.value },
        )

        // 300 points decode as the newest 288.
        val many = buildString {
            append("""{"series": {"BatteryPercent": [""")
            for (index in 0 until 300) {
                if (index > 0) append(',')
                append("""{"t": $index, "v": 50}""")
            }
            append("]}}")
        }
        val trimmed = StatsArchiveJson.decode(many).archive.series(StatsArchiveMetric.BatteryPercent)
        assertEquals(StatsArchive.Capacity, trimmed.size)
        assertEquals(12L, trimmed.first().atMillis)
        assertEquals(299L, trimmed.last().atMillis)
    }

    @Test
    fun `implausible stored values are dropped rather than drawn`() {
        val decoded = StatsArchiveJson.decode(
            """{
                "series": {
                    "BatteryPercent": [{"t": 1, "v": 101}, {"t": 2, "v": 50}],
                    "RamPercent": [{"t": 1, "v": -1}],
                    "BatteryTemperature": [{"t": 1, "v": 900}],
                    "StorageUsedPercent": [{"t": 1, "v": 88}],
                    "NetworkRxTotalBytes": [{"t": 1, "v": -5}]
                }
            }""",
        )

        assertEquals(1, decoded.archive.series(StatsArchiveMetric.BatteryPercent).size)
        assertEquals(50.0, decoded.archive.latest(StatsArchiveMetric.BatteryPercent)?.value ?: 0.0, 0.0)
        assertTrue(decoded.archive.series(StatsArchiveMetric.RamPercent).isEmpty())
        assertTrue(decoded.archive.series(StatsArchiveMetric.BatteryTemperature).isEmpty())
        assertTrue(decoded.archive.series(StatsArchiveMetric.NetworkRxTotalBytes).isEmpty())
        assertEquals(88.0, decoded.archive.latest(StatsArchiveMetric.StorageUsedPercent)?.value ?: 0.0, 0.0)
    }

    @Test
    fun `a zero or negative write timestamp reads as never written`() {
        val decoded = StatsArchiveJson.decode("""{"writtenAtMillis": 0, "series": {}}""")
        assertNull(decoded.writtenAtMillis)
        assertTrue(decoded.archive.isEmpty)
    }

    @Test
    fun `encoding an empty archive still carries the write timestamp`() {
        val text = StatsArchiveJson.encode(StatsArchive.Empty, 7_000L)
        val decoded = StatsArchiveJson.decode(text)
        assertTrue(decoded.archive.isEmpty)
        assertEquals(7_000L, decoded.writtenAtMillis)
    }
}
