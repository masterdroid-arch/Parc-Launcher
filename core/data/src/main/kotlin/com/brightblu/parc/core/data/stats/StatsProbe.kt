package com.brightblu.parc.core.data.stats

/** One battery reading; a field is null when that part of the source is unavailable. */
internal data class BatterySample(
    val percent: Int? = null,
    val chargingState: ChargingState? = null,
    val plug: PlugType? = null,
    val temperatureC: Float? = null,
    val currentMa: Float? = null,
)

/** Thermal reading: throttling status and headroom (1.0 = severe). */
internal data class ThermalSample(val status: ThermalStatus? = null, val headroom: Float? = null)

/** System RAM usage as a whole percentage. */
internal data class MemorySample(val usedPercent: Int? = null)

/** One data-partition reading; a field is null when the probe cannot read that part. */
internal data class StorageSample(
    val freePercent: Int? = null,
    /** User-visible used bytes (`totalBytes` minus available bytes), when the probe can read it. */
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
)

/** Current Wi-Fi link; a field is null when the connection does not report it. */
internal data class WifiSample(val rssiDbm: Int? = null, val linkSpeedMbps: Int? = null)

/** Temperatures from one `dumpsys thermalservice` round; an empty reading is unavailable. */
internal data class DumpsysSample(val temperatures: TemperatureReading = TemperatureReading()) {
    val isAllUnavailable: Boolean get() = temperatures.isEmpty
}

/**
 * The aggregate CPU counters from the `cpu` line of `/proc/stat`. [idle] already includes
 * `iowait`, which Linux counts as idle time but the `cpu` line reports separately (T58).
 */
internal data class ProcStatTicks(val total: Long, val idle: Long)

/**
 * The Android boundary for one sampling pass. Implementations must not throw and must return null
 * (or an all-null sample) for a source that is unsupported, unreadable or denied, never a guess.
 * [StatsSampler] guards every call as well, so a misbehaving implementation cannot stop the loop.
 *
 * Thread-safety: may be called from any thread; implementations must be re-entrant.
 */
internal interface StatsProbe {
    suspend fun battery(): BatterySample?
    suspend fun thermal(): ThermalSample?
    suspend fun memory(): MemorySample?
    suspend fun storage(): StorageSample?

    /** The current Wi-Fi link, or null when Wi-Fi is off, disconnected or denied. */
    suspend fun wifi(): WifiSample?
    suspend fun network(): NetworkBytes?

    /** Per-cluster CPU clocks, or null/empty when no `scaling_cur_freq` file is readable. */
    suspend fun cpuClusters(): List<CpuCluster>?

    /**
     * Temperatures from `/sys/class/thermal/thermal_zone*`, or null when no zone is readable
     * (for example SELinux denies the app on some devices). Read on every sample: it is a
     * few tiny files and is the only live non-HAL temperature source (T44).
     */
    suspend fun thermalZones(): TemperatureReading?

    /** True when `android.permission.DUMP` is held, so `dumpsys` may be spawned. */
    fun dumpsysGranted(): Boolean

    /**
     * One `dumpsys thermalservice` round; called at most once per temperature interval. Never
     * reads `cpuinfo` (T58 moved CPU load to `/proc/stat` with `cpuinfo` as a fallback only).
     */
    suspend fun dumpsys(): DumpsysSample?

    /**
     * The `cpu` line totals from `/proc/stat`, or null when the file is denied or unreadable
     * (T58). This is the preferred CPU-load source: no permission and no child process.
     */
    suspend fun procStatTicks(): ProcStatTicks?

    /**
     * CPU busy percent parsed from one `dumpsys cpuinfo` round (T58). Only called when
     * `/proc/stat` was unreadable; [dumpsysGranted] must be true. Null when the dump cannot be
     * spawned, the app UID cannot reach the `cpuinfo` service, or no `TOTAL:` line is present.
     */
    suspend fun dumpsysCpuLoad(): Float?

    /**
     * Drops any reading cached from a previous active period. Called when sampling stops, so a
     * value captured before the module was hidden is never published as a live one on resume.
     */
    fun reset()
}
