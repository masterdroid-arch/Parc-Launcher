package com.brightblu.parc.core.data.stats

/**
 * Maps one set of probe samples to a T78 archive reading. Pure and internal so the storage and
 * network arithmetic is unit-tested without an Android runtime.
 *
 * Rules: a reading is kept only in the range its source can honestly produce (percent 0 to 100, a
 * battery sensor -50 to 150 °C, byte counters not negative); anything else is unavailable. Storage
 * used percent prefers the exact used/total bytes and falls back to `100 - freePercent` when the
 * probe could only report free space. The network figures are the cumulative `TrafficStats` totals,
 * which reset when the device reboots.
 */
internal object StatsArchiveMapping {

    fun reading(
        battery: BatterySample?,
        memory: MemorySample?,
        storage: StorageSample?,
        network: NetworkBytes?,
    ): StatsArchiveReading = StatsArchiveReading(
        batteryPercent = battery?.percent?.takeIf { it in 0..100 },
        batteryTemperatureC = battery?.temperatureC?.takeIf { it.isFinite() && it in -50f..150f },
        ramUsedPercent = memory?.usedPercent?.takeIf { it in 0..100 },
        storageUsedPercent = storageUsedPercent(storage),
        networkRxTotalBytes = network?.rxBytes?.takeIf { it >= 0L },
        networkTxTotalBytes = network?.txBytes?.takeIf { it >= 0L },
    )

    /** Used percentage of the data partition, or null when neither byte reading is usable. */
    fun storageUsedPercent(storage: StorageSample?): Int? {
        if (storage == null) return null
        val used = storage.usedBytes
        val total = storage.totalBytes
        if (used != null && total != null && total > 0L && used >= 0L) {
            return (used * 100L / total).toInt().coerceIn(0, 100)
        }
        return storage.freePercent?.takeIf { it in 0..100 }?.let { (100 - it).coerceIn(0, 100) }
    }
}
