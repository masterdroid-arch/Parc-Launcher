package com.brightblu.parc

import com.brightblu.parc.core.data.power.*
import com.brightblu.parc.core.data.network.MobileNetworkType
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PowerNetworkFormatTest {
    @Test fun powerDirectionUsesTheSourceDirection() {
        assertEquals("↓ 18.6 W", PowerNetworkFormat.watts(ChargingPower(18.6f, PowerDirection.Charging)))
        assertEquals("↑ 0.0 W", PowerNetworkFormat.watts(ChargingPower(0f, PowerDirection.Discharging)))
        assertEquals("—", PowerNetworkFormat.watts(null))
    }
    @Test fun durationsPreserveZeroAndRejectMissingOrNegative() {
        assertEquals("0m", PowerNetworkFormat.duration(0))
        assertEquals("59m", PowerNetworkFormat.duration(3_599_999))
        assertEquals("1h 22m", PowerNetworkFormat.duration(4_920_000))
        assertEquals("—", PowerNetworkFormat.duration(-1))
        assertEquals("—", PowerNetworkFormat.duration(null))
    }
    @Test fun byteUnitsAndMissingValuesAreDistinct() {
        assertEquals("0 B", PowerNetworkFormat.bytes(0))
        assertEquals("999 B", PowerNetworkFormat.bytes(999))
        assertEquals("1.0 KB", PowerNetworkFormat.bytes(1_000))
        assertEquals("1.0 MB", PowerNetworkFormat.bytes(1_000_000))
        assertEquals("2.5 GB", PowerNetworkFormat.bytes(2_500_000_000))
        assertEquals("—", PowerNetworkFormat.bytes(null))
        assertEquals("—", PowerNetworkFormat.bytes(-1))
    }
    @Test fun formattingIsStableAcrossLocales() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1.5 KB", PowerNetworkFormat.bytes(1500))
            assertEquals("↑ 2.5 W", PowerNetworkFormat.watts(ChargingPower(2.5f, PowerDirection.Discharging)))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun healthAndNetworkHaveReadableLabels() {
        assertEquals("Over voltage", PowerNetworkFormat.health(BatteryHealth.OverVoltage))
        assertEquals("Unknown", PowerNetworkFormat.health(BatteryHealth.Unknown))
        assertEquals("—", PowerNetworkFormat.health(null))
        assertEquals("5G NR", PowerNetworkFormat.network(MobileNetworkType.Nr))
        assertEquals("HSPA+", PowerNetworkFormat.network(MobileNetworkType.Hspap))
        assertEquals("—", PowerNetworkFormat.network(null))
    }
    @Test fun newTilesSupportHideReorderAndOldConfigMigration() {
        val tiles = HubCatalog.tiles(DevicePage.Id)
        val config = HubConfig().move(DevicePage.Id, tiles.last(), -99).showTile(DevicePage.Id, tiles.first(), false)
        val restored = HubConfig.decode(config.encode())
        assertEquals(tiles.last(), restored.tiles(DevicePage.Id).first())
        assertTrue(tiles.first() in restored.hiddenTiles[DevicePage.Id].orEmpty())
        assertEquals(tiles, HubConfig.decode("system:true::").tiles(DevicePage.Id))
        // Old power and network rows fold into the same Device page without duplicates.
        val old = HubConfig.decode("power:true:charging:;network:true:traffic:;system:true:battery:")
        assertEquals(HubCatalog.deviceTiles.size, old.tiles(DevicePage.Id).size)
        assertEquals(HubCatalog.deviceTiles.toSet(), old.tiles(DevicePage.Id).toSet())
    }
}
