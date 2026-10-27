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
        for (page in listOf("power", "network")) {
            val tiles = HubCatalog.tiles(page)
            val config = HubConfig().move(page, tiles.last(), -99).showTile(page, tiles.first(), false)
            val restored = HubConfig.decode(config.encode())
            assertEquals(tiles.last(), restored.tiles(page).first())
            assertTrue(tiles.first() in restored.hiddenTiles[page].orEmpty())
            assertEquals(tiles, HubConfig.decode("$page:true::").tiles(page))
        }
    }
}
