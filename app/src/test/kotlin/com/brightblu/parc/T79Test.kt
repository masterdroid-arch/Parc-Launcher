package com.brightblu.parc

import com.brightblu.parc.core.data.stats.*
import org.junit.Assert.*
import org.junit.Test

class T79Test {
    private val limits = StatsAlertThresholds.Default
    private val low = DeviceStats(batteryPercent = 10, chargingState = ChargingState.Discharging)
    private fun StatsAlertUiState.read(stats: DeviceStats, enabled: Boolean = true) = evaluate(stats, limits, enabled)

    @Test fun offByDefaultLeavesNoAlert() {
        assertTrue(StatsAlertUiState().read(low, false).active.isEmpty())
    }
    @Test fun dismissalHidesBannerButKeepsTileWarning() {
        val state = StatsAlertUiState().read(low).dismiss(StatsAlertMetric.BatteryLow)
        assertNull(state.banner)
        assertEquals(StatsAlertMetric.BatteryLow, alertForTile("battery", state.active))
        assertNull(state.read(low).banner)
    }
    @Test fun recoveryRearmsDismissedAlertWithHysteresis() {
        val state = StatsAlertUiState().read(low).dismiss(StatsAlertMetric.BatteryLow)
        assertNull(state.read(low.copy(batteryPercent = 16)).banner)
        val recovered = state.read(low.copy(batteryPercent = 17))
        assertTrue(recovered.dismissed.isEmpty())
        assertEquals(StatsAlertMetric.BatteryLow, recovered.read(low).banner)
    }
    @Test fun chargingClearsDismissalAndNextEpisodeReturns() {
        val state = StatsAlertUiState().read(low).dismiss(StatsAlertMetric.BatteryLow)
            .read(low.copy(chargingState = ChargingState.Charging))
        assertTrue(state.active.isEmpty())
        assertEquals(StatsAlertMetric.BatteryLow, state.read(low).banner)
    }
    @Test fun multipleAlertsDismissAndRecoverIndependently() {
        val stats = low.copy(ramUsedPercent = 95)
        val first = StatsAlertUiState().read(stats)
        assertEquals(StatsAlertMetric.BatteryLow, first.banner)
        val next = first.dismiss(StatsAlertMetric.BatteryLow)
        assertEquals(StatsAlertMetric.RamHigh, next.banner)
        assertEquals(setOf(StatsAlertMetric.BatteryLow), next.read(stats.copy(ramUsedPercent = 88)).dismissed)
    }
    @Test fun disabledAndMissingMetricsClearTheirDismissals() {
        val dismissed = StatsAlertUiState().read(low).dismiss(StatsAlertMetric.BatteryLow)
        assertTrue(dismissed.read(DeviceStats.Empty).dismissed.isEmpty())
        assertTrue(dismissed.read(low, false).dismissed.isEmpty())
        val disabled = limits.map { it.copy(enabled = false) }
        assertTrue(dismissed.evaluate(low, disabled, true).active.isEmpty())
    }
    @Test fun unknownDismissalDoesNotSuppressFutureAlert() {
        val state = StatsAlertUiState().dismiss(StatsAlertMetric.BatteryLow)
        assertEquals(StatsAlertMetric.BatteryLow, state.read(low).banner)
    }
    @Test fun allAlertTilesMapToTheCorrectMetric() {
        val all = StatsAlertMetric.entries.toSet()
        assertEquals(StatsAlertMetric.BatteryTemperatureHigh, alertForTile("thermal", all))
        assertEquals(StatsAlertMetric.BatteryTemperatureHigh, alertForTile("battery temperature", all))
        assertEquals(StatsAlertMetric.StorageHigh, alertForTile("storage", all))
        assertEquals(StatsAlertMetric.RamHigh, alertForTile("ram", all))
        assertNull(alertForTile("wifi", all))
    }
    @Test fun graphsStartHiddenAndOldConfigurationKeepsThemHidden() {
        listOf(HubConfig(), HubConfig.decode("system:true:battery,ram:;power:true:charging:")).forEach { config ->
            assertTrue(config.hiddenTiles["system"].orEmpty().containsAll(HubCatalog.systemHistoryTiles.keys))
            assertTrue(config.hiddenTiles["power"].orEmpty().containsAll(HubCatalog.powerHistoryTiles.keys))
        }
    }
    @Test fun optionalGraphVisibilityAndOrderSurviveRoundTrip() {
        val config = HubConfig().showTile("power", "battery history", true).move("power", "battery history", -5)
        val restored = HubConfig.decode(config.encode())
        assertFalse("battery history" in restored.hiddenTiles["power"].orEmpty())
        assertEquals("battery history", restored.tiles("power").first())
        assertTrue("temperature history" in restored.hiddenTiles["power"].orEmpty())
    }
    @Test fun historyUsesActual24HourWindowAndRejectsFutureSamples() {
        val now = StatsHistoryWindowMillis * 2
        val points = listOf(StatsArchivePoint(now + 1, 90.0), StatsArchivePoint(now, 80.0),
            StatsArchivePoint(now - StatsHistoryWindowMillis, 70.0), StatsArchivePoint(0, 60.0))
        assertEquals(listOf(70.0, 80.0), historyWindow(points, now).map { it.value })
        assertTrue(historyWindow(emptyList(), now).isEmpty())
    }
}
