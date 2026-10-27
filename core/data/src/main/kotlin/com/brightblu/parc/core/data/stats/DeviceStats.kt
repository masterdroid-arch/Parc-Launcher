package com.brightblu.parc.core.data.stats

/** Battery charging state, mirroring `BatteryManager.BATTERY_STATUS_*`. */
enum class ChargingState { Charging, Full, Discharging, NotCharging }

/**
 * Power source, mirroring `BatteryManager.BATTERY_PLUGGED_*`. A value of `null` on
 * [DeviceStats.plug] means nothing is plugged in, or the system does not report a plug type.
 */
enum class PlugType { AC, USB, Wireless }

/** Thermal throttling level, mirroring `PowerManager.THERMAL_STATUS_*` (0..6). */
enum class ThermalStatus { None, Light, Moderate, Severe, Critical, Emergency, Shutdown }

/**
 * One CPU cluster (the CPU indices sharing a cpufreq policy) and its current clock.
 * [currentKhz] is null when the kernel does not expose a readable frequency for the cluster.
 */
data class CpuCluster(val cpus: List<Int>, val currentKhz: Int?)

/** The physical sensor a temperature reading belongs to. */
enum class TemperatureSensor { Cpu, Gpu, Skin, Npu, Soc, Battery }

/**
 * Data-partition usage in bytes (T44). [usedBytes] is total minus *available* bytes, matching
 * [DeviceStats.storageFreePercent]: "free" means writable by an unprivileged app, so the kernel's
 * reserved blocks count as used. Percentages therefore agree with the free reading.
 *
 * Thread-safety: immutable value; no Android types.
 */
data class StorageUsage(val usedBytes: Long, val totalBytes: Long) {
    /** Whole percentage currently used, or null when the total is unusable. */
    val usedPercent: Int?
        get() = if (totalBytes <= 0L || usedBytes < 0L) {
            null
        } else {
            (usedBytes * 100L / totalBytes).toInt().coerceIn(0, 100)
        }
}

/**
 * One immutable device-stats snapshot. Every field is nullable on purpose: null means the source
 * is unavailable on this device (unsupported API, denied permission, unreadable file) and is
 * never a guess. Instances are immutable and safe to publish on a `StateFlow`.
 *
 * [batteryTemperatureC] is the live `BatteryManager` broadcast reading; it falls back to the
 * `thermalservice` `BATTERY` sensor only when the broadcast carries no temperature. [uptimeMillis]
 * is a session fact rather than a device reading: it is the sampler's own monotonic clock, so it
 * is present on every tick while sampling and does not, on its own, make a snapshot "available"
 * (see [isAllUnavailable]).
 *
 * The CPU/GPU/skin/NPU/SOC temperatures come from `/sys/class/thermal` when those zones are
 * readable and from `dumpsys thermalservice` otherwise. A sensor whose value is identical on three
 * consecutive 5 s samples, or whose first value after a restart equals the value persisted at the
 * end of the previous start, is treated as frozen: its field is null and the sensor is listed in
 * [staleTemperatureSensors], so a consumer can never draw a stale vendor value as a live one
 * (T44 rule, revised by T58).
 *
 * Thread-safety: immutable value; no Android types.
 */
data class DeviceStats(
    val batteryPercent: Int? = null,
    val chargingState: ChargingState? = null,
    val plug: PlugType? = null,
    val batteryTemperatureC: Float? = null,
    val batteryCurrentMa: Float? = null,
    val thermalStatus: ThermalStatus? = null,
    val thermalHeadroom: Float? = null,
    val networkRxBytesPerSecond: Long? = null,
    val networkTxBytesPerSecond: Long? = null,
    val ramUsedPercent: Int? = null,
    val cpuClusters: List<CpuCluster>? = null,
    val cpuTemperatureC: Float? = null,
    val gpuTemperatureC: Float? = null,
    val skinTemperatureC: Float? = null,
    val cpuLoadPercent: Float? = null,
    val npuTemperatureC: Float? = null,
    val socTemperatureC: Float? = null,
    /** Share of the data partition still writable, as a whole percentage. */
    val storageFreePercent: Int? = null,
    /** Data-partition used/total bytes, sampled at most once per 30 s (T44). */
    val storage: StorageUsage? = null,
    /**
     * Sensors whose value was frozen across three fresh samples (or matched the previous start's
     * persisted value). Their temperature fields are null; this flag exists so the HUD can say why
     * (T44, T58).
     */
    val staleTemperatureSensors: Set<TemperatureSensor> = emptySet(),
    /** Milliseconds since boot, including deep sleep (`SystemClock.elapsedRealtime`). */
    val uptimeMillis: Long? = null,
    val wifiRssiDbm: Int? = null,
    val wifiLinkSpeedMbps: Int? = null,
) {
    /**
     * True when no source produced a value, so a consumer can hide the whole group. [uptimeMillis]
     * is excluded: it is always present while sampling and must not keep an otherwise empty
     * module on screen. [staleTemperatureSensors] is a diagnostic flag, not a value, so it is
     * excluded too.
     */
    val isAllUnavailable: Boolean
        get() = copy(uptimeMillis = null, staleTemperatureSensors = emptySet()) == Empty

    companion object {
        /** The all-null snapshot: every field unavailable. */
        val Empty = DeviceStats()
    }
}
