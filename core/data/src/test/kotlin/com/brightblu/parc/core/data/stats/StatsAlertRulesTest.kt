package com.brightblu.parc.core.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure T78 alert evaluation: defaults, the exact trigger/recovery boundaries, the charging rule
 * for the low-battery alert, disabled metrics and unavailable readings.
 */
class StatsAlertRulesTest {

    private fun stats(
        batteryPercent: Int? = null,
        chargingState: ChargingState? = null,
        batteryTemperatureC: Float? = null,
        ramUsedPercent: Int? = null,
        storageFreePercent: Int? = null,
        storageUsedPercent: Int? = null,
    ): DeviceStats = DeviceStats(
        batteryPercent = batteryPercent,
        chargingState = chargingState,
        batteryTemperatureC = batteryTemperatureC,
        ramUsedPercent = ramUsedPercent,
        storageFreePercent = storageFreePercent,
        storage = storageUsedPercent?.let {
            StorageUsage(usedBytes = it.toLong(), totalBytes = 100L)
        },
    )

    private fun evaluate(
        stats: DeviceStats,
        previous: Set<StatsAlertMetric> = emptySet(),
        thresholds: List<StatsAlertThreshold> = StatsAlertThresholds.Default,
    ): Set<StatsAlertMetric> = StatsAlertRules.evaluate(stats, thresholds, previous)

    @Test
    fun `defaults match the card and arm every metric`() {
        assertEquals(4, StatsAlertThresholds.Default.size)
        assertEquals(
            listOf(
                StatsAlertMetric.BatteryTemperatureHigh to 42f,
                StatsAlertMetric.StorageHigh to 90f,
                StatsAlertMetric.BatteryLow to 15f,
                StatsAlertMetric.RamHigh to 90f,
            ),
            StatsAlertThresholds.Default.map { it.metric to it.value },
        )
        assertTrue(StatsAlertThresholds.Default.all { it.enabled })
    }

    @Test
    fun `battery temperature triggers at 42 and clears below the hysteresis band`() {
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(stats(batteryTemperatureC = 41.9f)),
        )
        assertEquals(
            setOf(StatsAlertMetric.BatteryTemperatureHigh),
            evaluate(stats(batteryTemperatureC = 42f)),
        )
        // 41.5 is inside the 1 degree recovery band: it stays active.
        assertEquals(
            setOf(StatsAlertMetric.BatteryTemperatureHigh),
            evaluate(
                stats(batteryTemperatureC = 41.5f),
                previous = setOf(StatsAlertMetric.BatteryTemperatureHigh),
            ),
        )
        // 41.0 is the recovery edge and no longer inside the band.
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(
                stats(batteryTemperatureC = 41f),
                previous = setOf(StatsAlertMetric.BatteryTemperatureHigh),
            ),
        )
    }

    @Test
    fun `storage triggers at 90 and clears at the 88 recovery edge`() {
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(stats(storageUsedPercent = 89)),
        )
        assertEquals(
            setOf(StatsAlertMetric.StorageHigh),
            evaluate(stats(storageUsedPercent = 90)),
        )
        assertEquals(
            setOf(StatsAlertMetric.StorageHigh),
            evaluate(
                stats(storageUsedPercent = 89),
                previous = setOf(StatsAlertMetric.StorageHigh),
            ),
        )
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(
                stats(storageUsedPercent = 88),
                previous = setOf(StatsAlertMetric.StorageHigh),
            ),
        )
    }

    @Test
    fun `storage falls back to free percent when the byte reading is missing`() {
        assertEquals(
            setOf(StatsAlertMetric.StorageHigh),
            evaluate(stats(storageFreePercent = 5)),
        )
        // The used/total reading wins when both are present.
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(stats(storageFreePercent = 5, storageUsedPercent = 50)),
        )
    }

    @Test
    fun `ram triggers at 90 and clears at 88`() {
        assertEquals(emptySet<StatsAlertMetric>(), evaluate(stats(ramUsedPercent = 89)))
        assertEquals(
            setOf(StatsAlertMetric.RamHigh),
            evaluate(stats(ramUsedPercent = 90)),
        )
        assertEquals(
            setOf(StatsAlertMetric.RamHigh),
            evaluate(stats(ramUsedPercent = 89), previous = setOf(StatsAlertMetric.RamHigh)),
        )
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(stats(ramUsedPercent = 88), previous = setOf(StatsAlertMetric.RamHigh)),
        )
    }

    @Test
    fun `battery low triggers below 15 while not charging and clears at 17`() {
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(stats(batteryPercent = 15, chargingState = ChargingState.Discharging)),
        )
        assertEquals(
            setOf(StatsAlertMetric.BatteryLow),
            evaluate(stats(batteryPercent = 14, chargingState = ChargingState.Discharging)),
        )
        assertEquals(
            setOf(StatsAlertMetric.BatteryLow),
            evaluate(
                stats(batteryPercent = 16, chargingState = ChargingState.Discharging),
                previous = setOf(StatsAlertMetric.BatteryLow),
            ),
        )
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(
                stats(batteryPercent = 17, chargingState = ChargingState.Discharging),
                previous = setOf(StatsAlertMetric.BatteryLow),
            ),
        )
    }

    @Test
    fun `charging and full clear the low alert immediately, not charging keeps it`() {
        val low = stats(batteryPercent = 5, chargingState = ChargingState.Discharging)
        val active = setOf(StatsAlertMetric.BatteryLow)
        assertEquals(active, evaluate(low))
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(low.copy(chargingState = ChargingState.Charging), previous = active),
        )
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(low.copy(chargingState = ChargingState.Full), previous = active),
        )
        assertEquals(
            active,
            evaluate(low.copy(chargingState = ChargingState.NotCharging)),
        )
        // An unknown charging state is treated as not charging: the launcher cannot prove power is
        // connected, so it warns rather than hide a warning.
        assertEquals(active, evaluate(low.copy(chargingState = null)))
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(low.copy(batteryPercent = 15, chargingState = ChargingState.Discharging)),
        )
    }

    @Test
    fun `a disabled metric never alerts and clears an existing alert`() {
        val thresholds = StatsAlertThresholds.Default.map {
            if (it.metric == StatsAlertMetric.RamHigh) it.copy(enabled = false) else it
        }
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(stats(ramUsedPercent = 99), previous = setOf(StatsAlertMetric.RamHigh), thresholds = thresholds),
        )
        // The other metrics are unaffected by the switch.
        assertEquals(
            setOf(StatsAlertMetric.BatteryLow),
            evaluate(
                stats(batteryPercent = 10, chargingState = ChargingState.Discharging, ramUsedPercent = 99),
                thresholds = thresholds,
            ),
        )
    }

    @Test
    fun `an unavailable reading drops the alert instead of guessing`() {
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(stats(), previous = StatsAlertMetric.entries.toSet()),
        )
        assertEquals(
            emptySet<StatsAlertMetric>(),
            evaluate(stats(batteryTemperatureC = Float.NaN), previous = setOf(StatsAlertMetric.BatteryTemperatureHigh)),
        )
    }

    @Test
    fun `a threshold missing from the list is filled from the defaults`() {
        val onlyRam = listOf(
            StatsAlertThreshold(
                metric = StatsAlertMetric.RamHigh,
                enabled = true,
                value = 80f,
            ),
        )
        assertEquals(
            setOf(StatsAlertMetric.BatteryLow, StatsAlertMetric.RamHigh),
            evaluate(
                stats(batteryPercent = 10, chargingState = ChargingState.Discharging, ramUsedPercent = 80),
                thresholds = onlyRam,
            ),
        )
    }

    @Test
    fun `normalising fills, orders and de-duplicates by metric`() {
        val duplicate = listOf(
            StatsAlertThreshold(StatsAlertMetric.RamHigh, enabled = false, value = 70f),
            StatsAlertThreshold(StatsAlertMetric.RamHigh, enabled = true, value = 95f),
        )
        val normalised = StatsAlertThresholds.normalize(duplicate)
        assertEquals(StatsAlertMetric.entries, normalised.map { it.metric })
        assertEquals(4, normalised.size)
        val ram = normalised.first { it.metric == StatsAlertMetric.RamHigh }
        assertFalse(ram.enabled)
        assertEquals(70f, ram.value, 0f)
        // Every other metric is at its default.
        assertEquals(42f, normalised.first { it.metric == StatsAlertMetric.BatteryTemperatureHigh }.value, 0f)
    }
}
