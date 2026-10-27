package com.brightblu.parc.core.data.stats

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Framework-free sampling core. Owns the [DeviceStats] snapshot and the [StatsHistory] series, and
 * reads the [StatsProbe] once per tick, but only while active: [setActive] starts a coroutine,
 * cancelling it stops every further read and `delay`, so an inactive sampler does no work and
 * schedules no wakeup.
 *
 * Every probe call is guarded separately, so a field whose source throws or is missing is null and
 * the rest of the snapshot still updates. [elapsedMillis] is expected to be a monotonic boot clock
 * (production: `SystemClock.elapsedRealtime`), so it also supplies [DeviceStats.uptimeMillis].
 *
 * Cadences (T58): the 1 s tick serves the cheap live fields (battery, memory, network, Wi-Fi,
 * clocks). Temperatures and CPU load are sampled every 5 s while the hub is visible and the last
 * values are carried between rounds, so both are fresh enough to judge a frozen HAL and cheap
 * enough not to spawn a `dumpsys` process every second. A temperature identical across three such
 * samples is suppressed by [TemperatureTracker]; the values seen are persisted through
 * [ThermalMemory] so the next app start can recognise the same frozen number immediately.
 *
 * Thread-safety: [setActive] must be called from one thread; [stats] and [history] are `StateFlow`s
 * and safe to collect anywhere. [sampleOnce] runs on the sampler's coroutine.
 */
internal class StatsSampler(
    private val probe: StatsProbe,
    private val scope: CoroutineScope,
    private val elapsedMillis: () -> Long,
    private val tickMillis: Long = DefaultTickMillis,
    private val temperatureIntervalMillis: Long = DefaultTemperatureIntervalMillis,
    private val cpuLoadIntervalMillis: Long = DefaultCpuLoadIntervalMillis,
    private val storageIntervalMillis: Long = DefaultStorageIntervalMillis,
    private val thermalMemory: ThermalMemory = NoThermalMemory,
) {
    private val _stats = MutableStateFlow(DeviceStats.Empty)
    val stats: StateFlow<DeviceStats> = _stats.asStateFlow()

    private val _history = MutableStateFlow(StatsHistory())
    val history: StateFlow<StatsHistory> = _history.asStateFlow()

    private var tickJob: Job? = null
    private var lastNetwork: NetworkSample? = null
    private var lastTemperatureAtMillis: Long? = null
    private var dumpsysSample: DumpsysSample? = null
    private var lastCpuLoadAtMillis: Long? = null
    private var cpuLoadPercent: Float? = null
    private var lastStorageAtMillis: Long? = null
    private var storageSample: StorageSample? = null
    private val temperatures = TemperatureTracker()
    private val cpuLoad = CpuLoadTracker()

    /** True while the tick loop is running. */
    val isSampling: Boolean get() = tickJob != null

    fun setActive(active: Boolean) {
        if (active) start() else stop()
    }

    private fun start() {
        if (tickJob != null) return
        tickJob = scope.launch {
            // The previous app start's HAL values decide the cross-start frozen rule (T58). A
            // failed read is an empty baseline, never a reason to skip sampling.
            temperatures.beginSession(guarded { thermalMemory.load() } ?: emptyMap())
            while (isActive) {
                sampleOnce()
                delay(tickMillis)
            }
        }
    }

    private fun stop() {
        if (tickJob == null) return
        tickJob?.cancel()
        tickJob = null
        // Keep what the HAL last said for the next start's frozen check. Only a real HAL read is
        // worth storing; an empty map would erase the previous session's evidence.
        val halValues = temperatures.lastHalValues()
        // A stopped sampler publishes nothing and keeps no baseline: the next active period starts
        // from a fresh counter reading, and stale values are never presented as live ones.
        lastNetwork = null
        lastTemperatureAtMillis = null
        dumpsysSample = null
        lastCpuLoadAtMillis = null
        cpuLoadPercent = null
        lastStorageAtMillis = null
        storageSample = null
        temperatures.reset()
        cpuLoad.reset()
        guarded { probe.reset() }
        _stats.value = DeviceStats.Empty
        _history.value = StatsHistory()
        if (halValues.isNotEmpty()) {
            scope.launch { guarded { thermalMemory.save(halValues) } }
        }
    }

    /** One sampling pass. Runs on the sampling coroutine; exposed for tests. */
    internal suspend fun sampleOnce() {
        val now = elapsedMillis()
        val battery = guarded { probe.battery() }
        val thermal = guarded { probe.thermal() }
        val memory = guarded { probe.memory() }
        val storage = readStorage(now)
        val wifi = guarded { probe.wifi() }
        val bytes = guarded { probe.network() }
        val rate = if (bytes == null) {
            lastNetwork = null
            null
        } else {
            val current = NetworkSample(bytes.rxBytes, bytes.txBytes, now)
            StatsParsers.networkRate(lastNetwork, current).also { lastNetwork = current }
        }
        val temperatureRound = readTemperatures(now)
        val tracked = temperatures.update(
            zones = temperatureRound.zones,
            hal = temperatureRound.sample?.temperatures,
            halResolved = temperatureRound.resolved,
        )
        val load = readCpuLoad(now)
        _stats.value = DeviceStats(
            batteryPercent = battery?.percent,
            chargingState = battery?.chargingState,
            plug = battery?.plug,
            // BatteryManager's broadcast is the live reading (T44) and is never suppressed. The
            // thermalservice BATTERY sensor is a fallback only; it can be frozen like the other
            // HAL values and is then null and flagged stale by the tracker.
            batteryTemperatureC = battery?.temperatureC ?: tracked.readings.batteryTemperatureC,
            batteryCurrentMa = battery?.currentMa,
            thermalStatus = thermal?.status,
            thermalHeadroom = thermal?.headroom,
            networkRxBytesPerSecond = rate?.rxBytesPerSecond,
            networkTxBytesPerSecond = rate?.txBytesPerSecond,
            ramUsedPercent = memory?.usedPercent,
            cpuClusters = guarded { probe.cpuClusters() }?.takeIf { it.isNotEmpty() },
            cpuTemperatureC = tracked.readings.cpuTemperatureC,
            gpuTemperatureC = tracked.readings.gpuTemperatureC,
            skinTemperatureC = tracked.readings.skinTemperatureC,
            cpuLoadPercent = load,
            npuTemperatureC = tracked.readings.npuTemperatureC,
            socTemperatureC = tracked.readings.socTemperatureC,
            storageFreePercent = storage?.freePercent,
            storage = storage?.let { sample ->
                val used = sample.usedBytes
                val total = sample.totalBytes
                if (used != null && total != null) StorageUsage(used, total) else null
            },
            staleTemperatureSensors = tracked.stale,
            uptimeMillis = now,
            wifiRssiDbm = wifi?.rssiDbm,
            wifiLinkSpeedMbps = wifi?.linkSpeedMbps,
        )
        // The series follow the published snapshot, so a suppressed (stale) temperature leaves a
        // gap instead of drawing a frozen line, and a carried value repeats only between source
        // reads, which is what the module actually knows.
        _history.value = _history.value
            .recordNetwork(rate?.rxBytesPerSecond, rate?.txBytesPerSecond)
            .recordCpuTemperature(tracked.readings.cpuTemperatureC)
    }

    /**
     * Reads the data partition at most once per [storageIntervalMillis] (T44): it changes far more
     * slowly than the 1 s tick, and every read opens the filesystem. The last value is held in
     * between, which is a deliberate 30 s cache, not a live claim.
     */
    private suspend fun readStorage(now: Long): StorageSample? {
        val last = lastStorageAtMillis
        if (last != null && now - last < storageIntervalMillis) return storageSample
        lastStorageAtMillis = now
        storageSample = guarded { probe.storage() }
        return storageSample
    }

    /**
     * One temperature round and whether this tick's HAL result is authoritative. [resolved] is true
     * after a `dumpsys` process ran (even if it failed) and when DUMP is not held, so a revoked
     * permission cannot leave old HAL temperatures on screen. It is false on a throttled tick that
     * reuses the last parsed sample.
     */
    private data class TemperatureRound(
        val zones: TemperatureReading?,
        val sample: DumpsysSample?,
        val resolved: Boolean,
    )

    /** Reads kernel zones and spawns at most one thermalservice round per temperature interval. */
    private suspend fun readTemperatures(now: Long): TemperatureRound {
        val last = lastTemperatureAtMillis
        if (last != null && now - last < temperatureIntervalMillis) {
            return TemperatureRound(zones = null, sample = dumpsysSample, resolved = false)
        }
        lastTemperatureAtMillis = now
        val zones = guarded { probe.thermalZones() }
        if (!(guarded { probe.dumpsysGranted() } ?: false)) {
            dumpsysSample = null
            return TemperatureRound(zones = zones, sample = null, resolved = true)
        }
        dumpsysSample = guarded { probe.dumpsys() }?.takeIf { !it.isAllUnavailable }
        return TemperatureRound(zones = zones, sample = dumpsysSample, resolved = true)
    }

    /**
     * CPU load at most once per [cpuLoadIntervalMillis]. `/proc/stat` deltas are preferred (no
     * permission, no child process). Only when the file is unreadable and DUMP is held is a
     * `dumpsys cpuinfo` round attempted; when both fail the field is null, so the HUD can hide the
     * line rather than print a dash (T58). A failed round clears the carried percentage.
     */
    private suspend fun readCpuLoad(now: Long): Float? {
        val last = lastCpuLoadAtMillis
        if (last != null && now - last < cpuLoadIntervalMillis) return cpuLoadPercent
        lastCpuLoadAtMillis = now
        val ticks = guarded { probe.procStatTicks() }
        cpuLoadPercent = if (ticks != null) {
            cpuLoad.update(ticks)
        } else {
            cpuLoad.reset()
            if (guarded { probe.dumpsysGranted() } == true) {
                guarded { probe.dumpsysCpuLoad() }
            } else {
                null
            }
        }
        return cpuLoadPercent
    }

    private inline fun <T> guarded(read: () -> T): T? =
        try {
            read()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            null
        }

    companion object {
        const val DefaultTickMillis = 1_000L

        /** T58: three identical samples at 5 s each, 10 s of evidence before a sensor is hidden. */
        const val DefaultTemperatureIntervalMillis = 5_000L

        /** T58: CPU load deltas every 5 s, matching the temperature cadence. */
        const val DefaultCpuLoadIntervalMillis = 5_000L

        /** T44: the data partition is re-read at most twice a minute. */
        const val DefaultStorageIntervalMillis = 30_000L
    }
}
