package com.brightblu.parc

import com.brightblu.parc.core.data.stats.ChargingState
import com.brightblu.parc.core.data.stats.CpuCluster
import com.brightblu.parc.core.data.stats.DeviceStats
import com.brightblu.parc.core.data.stats.PlugType
import com.brightblu.parc.core.data.stats.StatsFormat
import com.brightblu.parc.core.data.stats.ThermalStatus
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.brightblu.parc.core.data.stats.StorageUsage
import com.brightblu.parc.core.data.stats.TemperatureSensor
import java.util.Locale

class StatsPresentationTest {

    private val fullStats = DeviceStats(
        batteryPercent = 87,
        chargingState = ChargingState.Charging,
        plug = PlugType.AC,
        batteryTemperatureC = 38.5f,
        batteryCurrentMa = -1205f,
        thermalStatus = ThermalStatus.Moderate,
        thermalHeadroom = 0.42f,
        networkRxBytesPerSecond = 1_234_567L,
        networkTxBytesPerSecond = 456_789L,
        ramUsedPercent = 63,
        cpuClusters = listOf(CpuCluster(listOf(0, 1), 2_000_000), CpuCluster(listOf(2, 3), 1_800_000)),
        cpuTemperatureC = 41.2f,
        gpuTemperatureC = 40.0f,
        skinTemperatureC = 36.8f,
        cpuLoadPercent = 12.4f,
        storageFreePercent = 31,
        storage = StorageUsage(118_000_000_000L, 256_000_000_000L),
        uptimeMillis = 97_200_000L,
        wifiRssiDbm = -45,
        wifiLinkSpeedMbps = 866,
    )

    private val synthSkin = Skin.Midnight.copy(
        colors = Skin.Midnight.colors.copy(
            accent = 0xFF112233L,
            warn = 0xFF445566L,
            accent2 = 0xFF778899L,
        ),
    )

    @Test fun eligibleRequiresEnabledHomeAndClosedSearch() {
        assertFalse(statsEligible(false, Layer.Home, false))
        assertFalse(statsEligible(true, Layer.Folder, false))
        assertFalse(statsEligible(true, Layer.Recents, false))
        assertFalse(statsEligible(true, Layer.Home, true))
        assertTrue(statsEligible(true, Layer.Home, false))
    }

    @Test fun moduleRemainsVisibleWithoutSensorReadingsWhenEligible() {
        assertFalse(statsModuleVisible(false, fullStats))
        assertTrue(statsModuleVisible(true, DeviceStats.Empty))
        assertTrue(statsModuleVisible(true, fullStats))
    }

    @Test fun activeRequiresResumedScreenOnAndEligible() {
        assertTrue(statsActive(true, true, true))
        assertFalse(statsActive(false, true, true))
        assertFalse(statsActive(true, false, true))
        assertFalse(statsActive(true, true, false))
    }

    @Test fun fullSnapshotMapsEveryField() {
        val p = StatsPresentation.of(fullStats)
        assertFalse(p.allUnavailable)
        assertEquals(87, p.batteryPercent)
        assertTrue(p.charging)
        assertEquals("AC", p.plug)
        assertEquals(listOf("CPU", "GPU", "Skin"), p.temperatures.map { it.label })
        assertEquals(41.2f, p.temperatures[0].celsius)
        assertEquals("CPU", p.oneTemperature?.label)
        assertEquals("Moderate", p.thermalChip)
        assertEquals(63, p.ramPercent)
        assertEquals(46, p.storagePercent)
        assertEquals("118.0 / 256.0 GB", p.storageText)
        assertEquals("1.2 MB/s", p.networkDown)
        assertEquals("456.8 KB/s", p.networkUp)
        assertTrue(p.networkAvailable)
        assertEquals(Metrics.hudWifiBars, p.wifiBars)
        assertEquals("866 Mbps", p.wifiLinkSpeed)
        assertEquals("1d 3h 0m", p.uptime)
    }

    @Test fun emptySnapshotHidesEverything() {
        val p = StatsPresentation.of(DeviceStats.Empty)
        assertTrue(p.allUnavailable)
        assertNull(p.batteryPercent)
        assertFalse(p.charging)
        assertNull(p.plug)
        assertTrue(p.temperatures.isEmpty())
        assertNull(p.oneTemperature)
        assertNull(p.thermalChip)
        assertNull(p.ramPercent)
        assertNull(p.storagePercent)
        assertEquals(StatsFormat.Unavailable, p.networkDown)
        assertEquals(StatsFormat.Unavailable, p.networkUp)
        assertFalse(p.networkAvailable)
        assertNull(p.wifiBars)
        assertNull(p.wifiLinkSpeed)
        assertEquals(StatsFormat.Unavailable, p.uptime)
    }

    @Test fun onlyCpuTemperatureYieldsOneAvailableGauge() {
        val p = StatsPresentation.of(DeviceStats(cpuTemperatureC = 41.2f))
        assertEquals(listOf("CPU"), p.temperatures.map { it.label })
        assertEquals("CPU", p.oneTemperature?.label)
    }

    @Test fun oneTemperatureFallsBackToBatteryWhenNoThermalSensors() {
        val p = StatsPresentation.of(DeviceStats(batteryTemperatureC = 33.1f))
        assertTrue(p.temperatures.isEmpty())
        assertEquals("Bat", p.oneTemperature?.label)
        assertEquals(33.1f, p.oneTemperature?.celsius)
    }

    @Test fun temperatureColorShiftsAccentWarnAccent2AtThresholds() {
        assertEquals(synthSkin.colors.accent, temperatureColor(30f, synthSkin))
        assertEquals(synthSkin.colors.accent, temperatureColor(41.9f, synthSkin))
        assertEquals(synthSkin.colors.warn, temperatureColor(42f, synthSkin))
        assertEquals(synthSkin.colors.warn, temperatureColor(54.9f, synthSkin))
        assertEquals(synthSkin.colors.accent2, temperatureColor(55f, synthSkin))
        assertEquals(synthSkin.colors.accent2, temperatureColor(80f, synthSkin))
    }

    @Test fun temperatureFractionIsClampedToUnitRange() {
        assertEquals(0f, temperatureFraction(0f), 0f)
        assertEquals(0.5f, temperatureFraction(45f), 0.0001f)
        assertEquals(1f, temperatureFraction(Metrics.hudTempMaxC), 0f)
        assertEquals(1f, temperatureFraction(120f), 0f)
    }

    @Test fun batterySegmentsFillLeftToRightAndClamp() {
        val full = batterySegments(100)
        val half = batterySegments(50)
        val empty = batterySegments(0)
        val over = batterySegments(130)
        assertEquals(Metrics.hudBatterySegments, full?.size)
        assertTrue(full!!.all { it })
        assertEquals(Metrics.hudBatterySegments, half?.size)
        assertEquals(Metrics.hudBatterySegments / 2, half!!.count { it })
        assertTrue(half.take(Metrics.hudBatterySegments / 2).all { it })
        assertFalse(half.drop(Metrics.hudBatterySegments / 2).any { it })
        assertTrue(empty!!.none { it })
        assertTrue(over!!.all { it })
        assertNull(batterySegments(null))
    }

    @Test fun wifiBarsMapRssiToBarsAndNullWhenAbsent() {
        assertNull(wifiBars(null))
        assertEquals(4, wifiBars(-45))
        assertEquals(3, wifiBars(-55))
        assertEquals(2, wifiBars(-65))
        assertEquals(1, wifiBars(-75))
        assertEquals(0, wifiBars(-90))
    }

    @Test fun chargingPlugAndThermalLabels() {
        assertTrue(isCharging(ChargingState.Charging))
        assertTrue(isCharging(ChargingState.Full))
        assertFalse(isCharging(ChargingState.Discharging))
        assertFalse(isCharging(ChargingState.NotCharging))
        assertFalse(isCharging(null))
        assertEquals("AC", plugLabel(PlugType.AC))
        assertEquals("USB", plugLabel(PlugType.USB))
        assertEquals("Wireless", plugLabel(PlugType.Wireless))
        assertNull(plugLabel(null))
        assertEquals("Moderate", thermalLabel(ThermalStatus.Moderate))
        assertEquals(StatsFormat.Unavailable, thermalLabel(null))
    }

    @Test fun cpuClockUsesHighestClusterAndIsNullWhenNoClock() {
        assertEquals(
            2_000_000,
            cpuClockKhz(listOf(CpuCluster(listOf(0), 1_800_000), CpuCluster(listOf(1), 2_000_000))),
        )
        assertEquals(
            1_800_000,
            cpuClockKhz(listOf(CpuCluster(listOf(0), null), CpuCluster(listOf(1), 1_800_000))),
        )
        assertNull(cpuClockKhz(null))
        assertNull(cpuClockKhz(listOf(CpuCluster(listOf(0), null))))
    }
    @Test fun storageFormattingUsesDecimalGbAndOneDecimalWithRounding() {
        assertEquals("0.0 / 256.0 GB", storageText(StorageUsage(0, 256_000_000_000L)))
        assertEquals("256.0 / 256.0 GB", storageText(StorageUsage(256_000_000_000L, 256_000_000_000L)))
        assertEquals("118.2 / 256.0 GB", storageText(StorageUsage(118_150_000_000L, 256_000_000_000L)))
        assertEquals("118.1 / 256.0 GB", storageText(StorageUsage(118_149_000_000L, 256_000_000_000L)))
        assertNull(storageText(null))
        assertNull(storageText(StorageUsage(0, 0)))
    }

    @Test fun storageFormattingIsIndependentOfDeviceLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5 / 2.0 GB", storageText(StorageUsage(1_500_000_000L, 2_000_000_000L)))
        } finally { Locale.setDefault(original) }
    }

    @Test fun storageUsesUsedBytesAndHidesWhenNullDespiteLegacyFreePercent() {
        assertNull(StatsPresentation.of(DeviceStats(storageFreePercent = 30)).storagePercent)
        assertNull(StatsPresentation.of(DeviceStats(storageFreePercent = 30)).storageText)
        for (percent in listOf(0, 100)) {
            val p = StatsPresentation.of(DeviceStats(storage = StorageUsage(percent.toLong(), 100)))
            assertEquals(percent, p.storagePercent)
            assertFalse(p.allUnavailable)
        }
    }

    @Test fun storageWarningStartsAboveNinetyPercent() {
        assertEquals(synthSkin.colors.accent, storageColor(90, synthSkin))
        assertEquals(synthSkin.colors.warn, storageColor(91, synthSkin))
        assertEquals(synthSkin.colors.warn, storageColor(100, synthSkin))
    }

    @Test fun staleTemperatureFlagShowsTildeWithoutInventingAReading() {
        val p = StatsPresentation.of(DeviceStats(batteryTemperatureC = 25f,
            staleTemperatureSensors = setOf(TemperatureSensor.Cpu, TemperatureSensor.Gpu)))
        assertEquals("CPU ~  GPU ~", p.staleTemperatures)
        assertTrue(p.temperatures.isEmpty())
        assertEquals(25f, p.oneTemperature?.celsius)
        assertNull(StatsPresentation.of(DeviceStats.Empty).staleTemperatures)
    }

}
