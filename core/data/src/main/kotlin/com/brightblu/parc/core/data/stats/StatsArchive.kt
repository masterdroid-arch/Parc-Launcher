package com.brightblu.parc.core.data.stats

/**
 * One series kept by the persisted stats archive (T78). The first four are the card's readings;
 * the two network series are the cumulative `TrafficStats` totals the card calls "network totals".
 * Storing both directions keeps a later graph able to show download and upload separately, and their
 * difference between two samples is the traffic used in that window (survives a process restart,
 * unlike the in-memory [StatsHistory] rates from T32).
 *
 * [unit] is the display unit of the series, not a conversion: values are stored as reported.
 */
enum class StatsArchiveMetric(val unit: StatsArchiveUnit) {
    BatteryPercent(StatsArchiveUnit.Percent),
    BatteryTemperature(StatsArchiveUnit.Celsius),
    RamPercent(StatsArchiveUnit.Percent),
    StorageUsedPercent(StatsArchiveUnit.Percent),
    NetworkRxTotalBytes(StatsArchiveUnit.Bytes),
    NetworkTxTotalBytes(StatsArchiveUnit.Bytes),
}

/** The unit a [StatsArchiveMetric] series is expressed in. */
enum class StatsArchiveUnit { Percent, Celsius, Bytes }

/**
 * One archived reading at [atMillis] (epoch milliseconds, from the injected clock). [value] is a
 * `Double` because the same series container holds whole percentages, a temperature with decimals
 * and byte totals that exceed a `Float`'s exact integer range; every value is exact for realistic
 * phone figures.
 *
 * Immutable, finite and non-negative timestamp.
 */
data class StatsArchivePoint(val atMillis: Long, val value: Double) {
    init {
        require(atMillis >= 0L) { "Archive timestamp must not be negative: $atMillis" }
        require(value.isFinite()) { "Archive value must be finite: $value" }
    }
}

/**
 * One 5-minute reading before it is split into per-metric series (T78). Every field is nullable:
 * null means the source did not answer this time and the series gets no point, never a fake zero.
 * [isEmpty] lets the recorder skip a tick where the device answered nothing at all.
 *
 * Immutable, non-null container.
 */
data class StatsArchiveReading(
    val batteryPercent: Int? = null,
    val batteryTemperatureC: Float? = null,
    val ramUsedPercent: Int? = null,
    val storageUsedPercent: Int? = null,
    val networkRxTotalBytes: Long? = null,
    val networkTxTotalBytes: Long? = null,
) {
    init {
        require(batteryPercent == null || batteryPercent in 0..100) {
            "Battery percent out of range: $batteryPercent"
        }
        require(batteryTemperatureC == null || batteryTemperatureC.isFinite()) {
            "Battery temperature must be finite: $batteryTemperatureC"
        }
        require(ramUsedPercent == null || ramUsedPercent in 0..100) {
            "RAM percent out of range: $ramUsedPercent"
        }
        require(storageUsedPercent == null || storageUsedPercent in 0..100) {
            "Storage percent out of range: $storageUsedPercent"
        }
        require(networkRxTotalBytes == null || networkRxTotalBytes >= 0L) {
            "Receive total must not be negative: $networkRxTotalBytes"
        }
        require(networkTxTotalBytes == null || networkTxTotalBytes >= 0L) {
            "Transmit total must not be negative: $networkTxTotalBytes"
        }
    }

    /** True when no metric produced a value in this reading. */
    val isEmpty: Boolean
        get() = batteryPercent == null &&
            batteryTemperatureC == null &&
            ramUsedPercent == null &&
            storageUsedPercent == null &&
            networkRxTotalBytes == null &&
            networkTxTotalBytes == null
}

/**
 * The immutable T78 archive: up to [Capacity] points per metric, oldest first. The capacity is a
 * ring buffer: recording a point on a full series drops that series' oldest point, so 288 samples at
 * one sample per 5 minutes is exactly 24 hours.
 *
 * A missing reading leaves a gap in one series rather than shifting any other series, so points in
 * different series are not required to share timestamps. The map is treated as read-only and every
 * [record] returns a new instance; a supplied map must not be mutated afterwards.
 */
data class StatsArchive(
    val seriesByMetric: Map<StatsArchiveMetric, List<StatsArchivePoint>> = emptyMap(),
) {
    /** The stored points for [metric], oldest first; empty when the series has none. */
    fun series(metric: StatsArchiveMetric): List<StatsArchivePoint> =
        seriesByMetric[metric].orEmpty()

    /** The newest point of [metric], or null. */
    fun latest(metric: StatsArchiveMetric): StatsArchivePoint? = series(metric).lastOrNull()

    /** True when no series has a point yet. */
    val isEmpty: Boolean
        get() = seriesByMetric.values.all { it.isEmpty() }

    /** Total points across all series; useful for tests and diagnostics. */
    val pointCount: Int
        get() = seriesByMetric.values.sumOf { it.size }

    /**
     * Appends one reading at [atMillis]. A null field appends nothing for that metric; a full series
     * drops its oldest point. Series order inside the map is the enum order, and timestamps are
     * appended as supplied, so the caller must sample in increasing time.
     */
    fun record(atMillis: Long, reading: StatsArchiveReading): StatsArchive {
        val additions = buildList {
            reading.batteryPercent?.let { add(StatsArchiveMetric.BatteryPercent to it.toDouble()) }
            reading.batteryTemperatureC?.let {
                add(StatsArchiveMetric.BatteryTemperature to it.toDouble())
            }
            reading.ramUsedPercent?.let { add(StatsArchiveMetric.RamPercent to it.toDouble()) }
            reading.storageUsedPercent?.let {
                add(StatsArchiveMetric.StorageUsedPercent to it.toDouble())
            }
            reading.networkRxTotalBytes?.let {
                add(StatsArchiveMetric.NetworkRxTotalBytes to it.toDouble())
            }
            reading.networkTxTotalBytes?.let {
                add(StatsArchiveMetric.NetworkTxTotalBytes to it.toDouble())
            }
        }
        if (additions.isEmpty()) return this
        val next = LinkedHashMap<StatsArchiveMetric, List<StatsArchivePoint>>(seriesByMetric)
        for ((metric, value) in additions) {
            val appended = next[metric].orEmpty() + StatsArchivePoint(atMillis, value)
            next[metric] = if (appended.size > Capacity) {
                appended.takeLast(Capacity)
            } else {
                appended
            }
        }
        return StatsArchive(next)
    }

    companion object {
        /** Points per metric: 288 samples at 5 minutes each is 24 hours. */
        const val Capacity = 288

        /** The all-empty archive. */
        val Empty = StatsArchive()
    }
}

/** A stored archive and when it was last written, as read back from DataStore (T78). */
data class StoredStatsArchive(
    val archive: StatsArchive = StatsArchive.Empty,
    /** Epoch millis of the last persisted write, or null when nothing valid was stored. */
    val writtenAtMillis: Long? = null,
)
