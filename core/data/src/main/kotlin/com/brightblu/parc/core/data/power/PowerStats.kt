package com.brightblu.parc.core.data.power

import kotlinx.coroutines.flow.StateFlow

/**
 * Battery health, mirroring `BatteryManager.BATTERY_HEALTH_*` (T51). [Unknown] is the platform's
 * own "unknown" value; an absent extra is represented by `null`, not by [Unknown].
 */
enum class BatteryHealth { Good, Overheat, Dead, OverVoltage, UnspecifiedFailure, Cold, Unknown }

/** Which way energy is moving. Derived from the charging state first, then from the signed current. */
enum class PowerDirection { Charging, Discharging }

/**
 * Charging or discharging power. [watts] is a magnitude (never negative); [direction] carries the
 * sign, so a consumer can draw an up/down arrow without re-interpreting the raw current.
 */
data class ChargingPower(val watts: Float, val direction: PowerDirection) {
    init {
        require(watts.isFinite() && watts >= 0f) { "watts must be finite and non-negative" }
    }
}

/**
 * One immutable power snapshot (T51). Every field is nullable on purpose: null means the platform
 * did not report that value, the API is unavailable on this device, or the reading was implausible.
 * A null is never a guess and never a zero substitute.
 *
 * [temperatureHistoryC] is the live `BatteryManager` temperature, oldest first, at most
 * [PowerStatsSource.HistoryCapacity] samples captured one per 5 s sampling tick. It is empty until
 * the first sample and is cleared when sampling stops, so a hidden hub never keeps a stale series.
 *
 * [screenOnTodayMillis] is the sum of interactive screen intervals since local midnight. It is null
 * when usage access is missing or the `UsageEvents` query is refused. It is read at most every 30 s
 * while the source is active, so it can trail a just-changed interval by that much.
 *
 * Thread-safety: immutable value; no Android types.
 */
data class PowerStats(
    val chargingPower: ChargingPower? = null,
    val chargeTimeRemainingMillis: Long? = null,
    val health: BatteryHealth? = null,
    val cycleCount: Int? = null,
    val temperatureHistoryC: List<Float> = emptyList(),
    val screenOnTodayMillis: Long? = null,
) {
    companion object {
        /** The all-null snapshot. */
        val Empty = PowerStats()
    }
}

/**
 * Thread-safe power stream. [stats] never changes while the source is inactive and is reset to
 * [PowerStats.Empty] on [setActive]`(false)`.
 *
 * `setActive(true)` starts a 5 s tick; `setActive(false)` cancels it and clears the temperature
 * history. While inactive there is no coroutine, no `delay`, no `UsageEvents` query and no battery
 * read, so a hidden hub costs nothing.
 *
 * Call [setActive] from one thread (the owner of the lifecycle). [stats] is safe to collect anywhere.
 */
interface PowerStatsSource {
    val stats: StateFlow<PowerStats>

    fun setActive(active: Boolean)

    companion object {
        /** Five minutes of battery temperature at one sample per five seconds. */
        const val HistoryCapacity = 60

        /** The card's sampling cadence while the hub is visible. */
        const val SampleIntervalMillis = 5_000L

        /** Screen-on time is a much more expensive `UsageEvents` query. */
        const val ScreenOnIntervalMillis = 30_000L
    }
}
