package com.brightblu.parc.core.data.stats

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JSON codec for one persisted T78 archive document: the write timestamp and the per-metric series.
 *
 * One document means one atomic DataStore write, and the timestamp travels with the data, so a new
 * process can respect "at most one write per 15 minutes" across restarts instead of writing
 * immediately every time it starts.
 *
 * Reading is tolerant: a missing, blank or corrupt document is an empty archive with no timestamp.
 * Unknown metric names are skipped (a newer build may add one), points are sorted by time, duplicate
 * timestamps keep the last value, each series is trimmed to [StatsArchive.Capacity], and a reading
 * outside the metric's plausible range is dropped rather than drawn. Writing is canonical and
 * includes all series in enum order.
 */
internal object StatsArchiveJson {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** One stored point; short names keep a full 24-hour document small. */
    @Serializable
    private data class PointDto(val t: Long, val v: Double)

    /** The stored document; `series` is keyed by enum name so unknown keys stay harmless. */
    @Serializable
    private data class DocumentDto(
        val writtenAtMillis: Long = 0L,
        val series: Map<String, List<PointDto>> = emptyMap(),
    )

    /** Encodes [archive] and the moment it was written. */
    fun encode(archive: StatsArchive, writtenAtMillis: Long): String {
        val series = LinkedHashMap<String, List<PointDto>>()
        for (metric in StatsArchiveMetric.entries) {
            val points = archive.series(metric)
            if (points.isNotEmpty()) {
                series[metric.name] = points.map { PointDto(it.atMillis, it.value) }
            }
        }
        return json.encodeToString(
            DocumentDto.serializer(),
            DocumentDto(writtenAtMillis = writtenAtMillis, series = series),
        )
    }

    /** Decodes a stored document; anything unusable becomes [StoredStatsArchive] defaults. */
    fun decode(text: String?): StoredStatsArchive {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return StoredStatsArchive()
        val document = try {
            json.decodeFromString(DocumentDto.serializer(), raw)
        } catch (invalid: Exception) {
            return StoredStatsArchive()
        }
        val series = LinkedHashMap<StatsArchiveMetric, List<StatsArchivePoint>>()
        for (metric in StatsArchiveMetric.entries) {
            val points = document.series[metric.name] ?: continue
            val clean = points
                .asSequence()
                .filter { it.t >= 0L && it.v.isFinite() && plausible(metric, it.v) }
                .sortedBy { it.t }
                .fold(LinkedHashMap<Long, StatsArchivePoint>()) { byTime, point ->
                    // A duplicate timestamp is one sample; the later stored value wins.
                    byTime[point.t] = StatsArchivePoint(point.t, point.v)
                    byTime
                }
                .values
                .toList()
                .takeLast(StatsArchive.Capacity)
            if (clean.isNotEmpty()) series[metric] = clean
        }
        return StoredStatsArchive(
            archive = StatsArchive(series),
            writtenAtMillis = document.writtenAtMillis.takeIf { it > 0L },
        )
    }

    /**
     * Stored values outside these bounds are corruption or a different unit, not a reading worth
     * drawing: percentages are 0 to 100, a phone battery sensor outside -50 to 150 °C is nonsense,
     * and a byte counter cannot be negative.
     */
    private fun plausible(metric: StatsArchiveMetric, value: Double): Boolean = when (metric) {
        StatsArchiveMetric.BatteryPercent,
        StatsArchiveMetric.RamPercent,
        StatsArchiveMetric.StorageUsedPercent,
        -> value in 0.0..100.0
        StatsArchiveMetric.BatteryTemperature -> value in -50.0..150.0
        StatsArchiveMetric.NetworkRxTotalBytes,
        StatsArchiveMetric.NetworkTxTotalBytes,
        -> value >= 0.0
    }
}
