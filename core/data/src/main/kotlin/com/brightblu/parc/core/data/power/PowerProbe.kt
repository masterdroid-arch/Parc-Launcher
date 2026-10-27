package com.brightblu.parc.core.data.power

import com.brightblu.parc.core.data.stats.ChargingState

/**
 * One battery reading for T51. A null field is a value the platform did not report; it is not a
 * zero and not a placeholder.
 *
 * [currentMicroAmps] keeps the platform's documented microampere unit. [voltageMillivolts] comes
 * from the sticky `ACTION_BATTERY_CHANGED` broadcast. [cycleCount] is API 34's `EXTRA_CYCLE_COUNT`.
 */
internal data class PowerBatterySample(
    val currentMicroAmps: Long? = null,
    val voltageMillivolts: Int? = null,
    val chargingState: ChargingState? = null,
    val chargeTimeRemainingMillis: Long? = null,
    val health: BatteryHealth? = null,
    val cycleCount: Int? = null,
    val temperatureC: Float? = null,
)

/**
 * The Android boundary for T51. Implementations must not throw and must return null for a source
 * that is missing, denied or unsupported. [PowerSampler] also guards every call.
 *
 * Thread-safety: may be called from any thread; implementations must be re-entrant.
 */
internal interface PowerProbe {

    /** One cheap `BatteryManager` read. */
    suspend fun battery(): PowerBatterySample?

    /**
     * Interactive screen time inside `[windowStartMillis, windowEndMillis]`, or null when usage
     * access is missing or the platform refuses the query. An empty event history is a real zero.
     *
     * Implementations are expected to read events from at least one day before the window so a
     * screen already on at midnight is counted from midnight, not from the first event of the day.
     */
    suspend fun screenOn(windowStartMillis: Long, windowEndMillis: Long): Long?

    /** Drops any reading cached from a previous active period. */
    fun reset()
}
