package com.brightblu.parc.core.data.power

import com.brightblu.parc.core.data.apps.ScreenTimeWindows
import java.time.ZoneId
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
 * Framework-free sampling core for T51. Owns the [PowerStats] snapshot and the battery-temperature
 * ring buffer, and reads the [PowerProbe] on a 5 s tick but only while active.
 *
 * The screen-on query is the expensive read, so it is capped at one call per
 * [screenOnIntervalMillis]; the last answer is held between calls and is deliberately not a live
 * claim. Every probe call is guarded separately, so one missing source leaves its field null and
 * the rest of the snapshot still updates.
 *
 * Thread-safety: [setActive] must be called from one thread; [stats] is a `StateFlow`.
 * [sampleOnce] runs on the sampler's coroutine.
 */
internal class PowerSampler(
    private val probe: PowerProbe,
    private val scope: CoroutineScope,
    private val elapsedMillis: () -> Long,
    private val wallClock: () -> Long,
    private val zone: () -> ZoneId,
    private val tickMillis: Long = PowerStatsSource.SampleIntervalMillis,
    private val screenOnIntervalMillis: Long = PowerStatsSource.ScreenOnIntervalMillis,
    private val historyCapacity: Int = PowerStatsSource.HistoryCapacity,
) {
    private val _stats = MutableStateFlow(PowerStats.Empty)
    val stats: StateFlow<PowerStats> = _stats.asStateFlow()

    private var tickJob: Job? = null
    private var lastScreenOnAtMillis: Long? = null
    private var screenOnMillis: Long? = null
    private var temperatureHistory: List<Float> = emptyList()

    /** True while the tick loop is running. */
    val isSampling: Boolean get() = tickJob != null

    fun setActive(active: Boolean) {
        if (active) start() else stop()
    }

    private fun start() {
        if (tickJob != null) return
        tickJob = scope.launch {
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
        // A hidden hub keeps nothing: the next visible period starts with a fresh series and no
        // screen-on value from before the pause.
        lastScreenOnAtMillis = null
        screenOnMillis = null
        temperatureHistory = emptyList()
        guarded { probe.reset() }
        _stats.value = PowerStats.Empty
    }

    /** One sampling pass. Runs on the sampling coroutine; exposed for tests. */
    internal suspend fun sampleOnce() {
        val nowElapsed = elapsedMillis()
        val battery = guarded { probe.battery() }
        val temperature = battery?.temperatureC
        if (temperature != null) {
            temperatureHistory = append(temperatureHistory, temperature)
        }
        val screenOn = readScreenOn(nowElapsed)
        _stats.value = PowerStats(
            chargingPower = PowerMath.chargingPower(
                currentMicroAmps = battery?.currentMicroAmps,
                voltageMillivolts = battery?.voltageMillivolts,
                chargingState = battery?.chargingState,
            ),
            chargeTimeRemainingMillis = battery?.chargeTimeRemainingMillis,
            health = battery?.health,
            cycleCount = battery?.cycleCount,
            temperatureHistoryC = temperatureHistory,
            screenOnTodayMillis = screenOn,
        )
    }

    /** Local-midnight-bounded screen-on read, capped at one `UsageEvents` pass per 30 s. */
    private suspend fun readScreenOn(nowElapsed: Long): Long? {
        val last = lastScreenOnAtMillis
        if (last != null && nowElapsed - last < screenOnIntervalMillis) return screenOnMillis
        lastScreenOnAtMillis = nowElapsed
        val nowWall = wallClock()
        val start = windowStart(nowWall)
        screenOnMillis = guarded { probe.screenOn(start, nowWall) }
        return screenOnMillis
    }

    private fun windowStart(nowMillis: Long): Long =
        ScreenTimeWindows.startOfToday(nowMillis, zone())

    private fun append(history: List<Float>, value: Float): List<Float> = when {
        historyCapacity <= 0 -> emptyList()
        history.size < historyCapacity -> history + value
        else -> history.drop(history.size - historyCapacity + 1) + value
    }

    private inline fun <T> guarded(read: () -> T): T? =
        try {
            read()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            null
        }
}
