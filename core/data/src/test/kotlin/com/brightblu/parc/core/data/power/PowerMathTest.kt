package com.brightblu.parc.core.data.power

import com.brightblu.parc.core.data.stats.ChargingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T51 power arithmetic and screen-on summation, all pure. */
class PowerMathTest {

    @Test
    fun `charging power is current times voltage`() {
        val power = PowerMath.chargingPower(
            currentMicroAmps = 1_000_000,
            voltageMillivolts = 4_000,
            chargingState = ChargingState.Charging,
        )

        assertEquals(4.0f, power!!.watts, 0.001f)
        assertEquals(PowerDirection.Charging, power.direction)
    }

    @Test
    fun `a negative current while charging is still charging`() {
        val power = PowerMath.chargingPower(
            currentMicroAmps = -1_500_000,
            voltageMillivolts = 4_000,
            chargingState = ChargingState.Charging,
        )

        assertEquals(6.0f, power!!.watts, 0.001f)
        assertEquals(PowerDirection.Charging, power.direction)
    }

    @Test
    fun `a positive current while discharging is still discharging`() {
        val power = PowerMath.chargingPower(
            currentMicroAmps = 500_000,
            voltageMillivolts = 4_000,
            chargingState = ChargingState.Discharging,
        )

        assertEquals(2.0f, power!!.watts, 0.001f)
        assertEquals(PowerDirection.Discharging, power.direction)
    }

    @Test
    fun `an unknown charging state falls back to the sign`() {
        val positive = PowerMath.chargingPower(500_000, 4_000, null)
        val negative = PowerMath.chargingPower(-500_000, 4_000, null)
        val notChargingNegative = PowerMath.chargingPower(
            -500_000,
            4_000,
            ChargingState.NotCharging,
        )

        assertEquals(PowerDirection.Charging, positive!!.direction)
        assertEquals(PowerDirection.Discharging, negative!!.direction)
        assertEquals(PowerDirection.Discharging, notChargingNegative!!.direction)
    }

    @Test
    fun `zero current is a valid zero watt reading`() {
        val power = PowerMath.chargingPower(0, 4_200, ChargingState.Full)

        assertEquals(0f, power!!.watts, 0.001f)
        assertEquals(PowerDirection.Charging, power.direction)
    }

    @Test
    fun `missing or implausible readings are ignored`() {
        assertNull(PowerMath.chargingPower(null, 4_000, ChargingState.Charging))
        assertNull(PowerMath.chargingPower(1_000_000, null, ChargingState.Charging))
        assertNull(PowerMath.chargingPower(1_000_000, 0, ChargingState.Charging))
        assertNull(PowerMath.chargingPower(1_000_000, 1_999, ChargingState.Charging))
        assertNull(PowerMath.chargingPower(1_000_000, 6_001, ChargingState.Charging))
        assertNull(PowerMath.chargingPower(10_000_001, 4_000, ChargingState.Charging))
        assertNull(PowerMath.chargingPower(-10_000_001, 4_000, ChargingState.Discharging))
        assertNull(
            PowerMath.chargingPower(
                PowerMath.UnsupportedLongProperty,
                4_000,
                ChargingState.Charging,
            ),
        )
        assertNull(
            PowerMath.chargingPower(
                PowerMath.UnsupportedIntProperty,
                4_000,
                ChargingState.Charging,
            ),
        )
    }

    @Test
    fun `charge time is null unless the platform gives a positive estimate`() {
        assertNull(PowerMath.chargeTimeRemaining(-1L))
        assertNull(PowerMath.chargeTimeRemaining(0L))
        assertNull(PowerMath.chargeTimeRemaining(Long.MIN_VALUE))
        assertEquals(1_800_000L, PowerMath.chargeTimeRemaining(1_800_000L))
    }

    @Test
    fun `battery health maps every platform value and rejects the rest`() {
        assertEquals(BatteryHealth.Unknown, PowerMath.batteryHealth(1))
        assertEquals(BatteryHealth.Good, PowerMath.batteryHealth(2))
        assertEquals(BatteryHealth.Overheat, PowerMath.batteryHealth(3))
        assertEquals(BatteryHealth.Dead, PowerMath.batteryHealth(4))
        assertEquals(BatteryHealth.OverVoltage, PowerMath.batteryHealth(5))
        assertEquals(BatteryHealth.UnspecifiedFailure, PowerMath.batteryHealth(6))
        assertEquals(BatteryHealth.Cold, PowerMath.batteryHealth(7))
        assertNull(PowerMath.batteryHealth(0))
        assertNull(PowerMath.batteryHealth(99))
        assertNull(PowerMath.batteryHealth(null))
    }

    @Test
    fun `cycle count only accepts non-negative values`() {
        assertEquals(0, PowerMath.cycleCount(0))
        assertEquals(320, PowerMath.cycleCount(320))
        assertNull(PowerMath.cycleCount(-1))
        assertNull(PowerMath.cycleCount(null))
    }

    @Test
    fun `screen-on time sums interactive intervals inside the day`() {
        val minutes = { value: Long -> value * 60_000L }
        val events = listOf(
            ScreenEvent(interactive = true, atMillis = minutes(600)),
            ScreenEvent(interactive = false, atMillis = minutes(630)),
            ScreenEvent(interactive = true, atMillis = minutes(700)),
            ScreenEvent(interactive = false, atMillis = minutes(730)),
        )

        val total = ScreenOnTimeMath.todayMillis(events, 0L, minutes(1_440))

        assertEquals(minutes(60), total)
    }

    @Test
    fun `a screen already on at midnight is counted from midnight`() {
        val start = 1_000L
        val end = 5_000L
        val events = listOf(
            ScreenEvent(interactive = true, atMillis = 500L),
            ScreenEvent(interactive = false, atMillis = 2_000L),
            ScreenEvent(interactive = true, atMillis = 3_000L),
        )

        val total = ScreenOnTimeMath.todayMillis(events, start, end)

        assertEquals(1_000L + 2_000L, total)
    }

    @Test
    fun `a screen still on at the end is counted up to now`() {
        val total = ScreenOnTimeMath.todayMillis(
            events = listOf(ScreenEvent(interactive = true, atMillis = 1_000L)),
            windowStartMillis = 0L,
            windowEndMillis = 4_000L,
        )

        assertEquals(3_000L, total)
    }

    @Test
    fun `repeated interactive and non-interactive events do not double count`() {
        val events = listOf(
            ScreenEvent(interactive = true, atMillis = 1_000L),
            ScreenEvent(interactive = true, atMillis = 2_000L),
            ScreenEvent(interactive = false, atMillis = 3_000L),
            ScreenEvent(interactive = false, atMillis = 4_000L),
        )

        assertEquals(2_000L, ScreenOnTimeMath.todayMillis(events, 0L, 10_000L))
    }

    @Test
    fun `no events is zero and non-interactive alone is zero`() {
        assertEquals(0L, ScreenOnTimeMath.todayMillis(emptyList(), 0L, 10_000L))
        assertEquals(
            0L,
            ScreenOnTimeMath.todayMillis(
                listOf(ScreenEvent(interactive = false, atMillis = 2_000L)),
                0L,
                10_000L,
            ),
        )
    }

    @Test
    fun `events outside the window are ignored or clamped`() {
        val events = listOf(
            ScreenEvent(interactive = true, atMillis = 1_000L),
            ScreenEvent(interactive = false, atMillis = 15_000L),
            ScreenEvent(interactive = true, atMillis = 30_000L),
        )

        assertEquals(5_000L, ScreenOnTimeMath.todayMillis(events, 10_000L, 20_000L))
    }

    @Test
    fun `an inverted or empty window is zero`() {
        val events = listOf(ScreenEvent(interactive = true, atMillis = 1_000L))

        assertEquals(0L, ScreenOnTimeMath.todayMillis(events, 5_000L, 5_000L))
        assertEquals(0L, ScreenOnTimeMath.todayMillis(events, 5_000L, 4_000L))
    }
}
