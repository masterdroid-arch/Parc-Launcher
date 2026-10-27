package com.brightblu.parc.core.data.power

import com.brightblu.parc.core.data.stats.ChargingState

/**
 * One screen power transition, already reduced from `UsageEvents` to the two events T51 needs.
 * [atMillis] is an epoch-millisecond timestamp; [interactive] is true for `SCREEN_INTERACTIVE`.
 */
internal data class ScreenEvent(val interactive: Boolean, val atMillis: Long)

/**
 * Pure power arithmetic (T51). No Android types, so every rule is unit-tested with plain values.
 *
 * **Current units.** `BATTERY_PROPERTY_CURRENT_NOW` is documented in microamperes. Some vendor
 * builds report milliamperes instead, but there is no API that says which, and guessing from a
 * value such as 8,000 would turn 8 mA into 8 A. This module therefore follows the documented unit.
 * A device that reports mA will under-report watts, which is visible and harmless; an invented
 * conversion could show a plausible but false number.
 *
 * **Sign.** Positive current means energy entering the battery on the reference platform. Vendor
 * sign conventions differ, so [chargingPower] prefers the platform's [ChargingState] when it is
 * known and falls back to the sign only when the state is unknown.
 *
 * **Bogus values.** An unsupported-property sentinel, a voltage outside a phone battery's range,
 * a current above [MaxPlausibleCurrentMicroAmps], or a non-finite derived wattage yields null. The
 * thresholds are deliberately loose: they reject nonsense, not slow charging.
 */
internal object PowerMath {

    /** A phone battery under load stays roughly inside this range. */
    const val MinVoltageMillivolts = 2_000
    const val MaxVoltageMillivolts = 6_000

    /** 10 A is above any phone's charging current; larger readings are sensor nonsense. */
    const val MaxPlausibleCurrentMicroAmps = 10_000_000L

    /** `getIntProperty`/`getLongProperty` return this when a property is unsupported. */
    const val UnsupportedIntProperty = -2_147_483_648L
    const val UnsupportedLongProperty = Long.MIN_VALUE

    /**
     * Charging power, or null when [currentMicroAmps] or [voltageMillivolts] is absent/implausible.
     * Zero current is a valid zero-watt reading (for example, a full battery on the charger).
     */
    fun chargingPower(
        currentMicroAmps: Long?,
        voltageMillivolts: Int?,
        chargingState: ChargingState?,
    ): ChargingPower? {
        val current = currentMicroAmps ?: return null
        val voltage = voltageMillivolts ?: return null
        if (current == UnsupportedIntProperty || current == UnsupportedLongProperty) return null
        if (voltage !in MinVoltageMillivolts..MaxVoltageMillivolts) return null
        if (current > MaxPlausibleCurrentMicroAmps || current < -MaxPlausibleCurrentMicroAmps) return null

        val watts = (kotlin.math.abs(current) * voltage) / NanowattsPerWatt
        if (!watts.isFinite()) return null
        return ChargingPower(watts = watts, direction = direction(chargingState, current))
    }

    /**
     * `computeChargeTimeRemaining()` returns -1 (or another non-positive value) when it cannot
     * estimate, which must not be shown as "0 s to full".
     */
    fun chargeTimeRemaining(rawMillis: Long): Long? = rawMillis.takeIf { it > 0L }

    /** Maps `BatteryManager.EXTRA_HEALTH`; unknown or absent values become null. */
    fun batteryHealth(raw: Int?): BatteryHealth? = when (raw) {
        1 -> BatteryHealth.Unknown
        2 -> BatteryHealth.Good
        3 -> BatteryHealth.Overheat
        4 -> BatteryHealth.Dead
        5 -> BatteryHealth.OverVoltage
        6 -> BatteryHealth.UnspecifiedFailure
        7 -> BatteryHealth.Cold
        else -> null
    }

    /** `EXTRA_CYCLE_COUNT` is -1 when the platform does not know; only non-negative values count. */
    fun cycleCount(raw: Int?): Int? = raw?.takeIf { it >= 0 }

    private fun direction(state: ChargingState?, currentMicroAmps: Long): PowerDirection = when (state) {
        ChargingState.Charging, ChargingState.Full -> PowerDirection.Charging
        ChargingState.Discharging -> PowerDirection.Discharging
        // "Plugged but not charging" and unknown: the sign is the only evidence left.
        ChargingState.NotCharging, null ->
            if (currentMicroAmps > 0L) PowerDirection.Charging else PowerDirection.Discharging
    }

    private const val NanowattsPerWatt = 1_000_000_000f
}

/**
 * Sums interactive screen intervals from a chronological screen-event list. The caller supplies
 * events from at least one day before [windowStartMillis] so the state at local midnight is known;
 * events outside the window are clamped or ignored rather than counted.
 *
 * A single long interval is counted to [windowEndMillis] even when no `SCREEN_NON_INTERACTIVE`
 * event has arrived yet (the screen is still on). Repeated interactive events do not restart the
 * interval, and repeated non-interactive events add nothing.
 */
internal object ScreenOnTimeMath {

    fun todayMillis(
        events: List<ScreenEvent>,
        windowStartMillis: Long,
        windowEndMillis: Long,
    ): Long {
        val windowLength = windowEndMillis - windowStartMillis
        if (windowLength <= 0L) return 0L

        var onSince: Long? = null
        var total = 0L
        for (event in events.sortedBy { it.atMillis }) {
            if (event.atMillis <= windowStartMillis) {
                // Last transition before the window establishes the state at midnight.
                onSince = if (event.interactive) windowStartMillis else null
                continue
            }
            if (event.atMillis > windowEndMillis) break
            if (event.interactive) {
                if (onSince == null) onSince = event.atMillis
            } else {
                val started = onSince
                if (started != null) {
                    total += (event.atMillis - started).coerceAtLeast(0L)
                    onSince = null
                }
            }
        }
        val started = onSince
        if (started != null) total += (windowEndMillis - started).coerceAtLeast(0L)
        return total.coerceIn(0L, windowLength)
    }
}
