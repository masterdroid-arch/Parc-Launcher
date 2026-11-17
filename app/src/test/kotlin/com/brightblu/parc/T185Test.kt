package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.ledger.LedgerPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T185: saved hub configs that list the old network and screen-time tile ids keep working. */
class T185Test {

    @Test fun oldScreenTimeTilesMapToTheOneTileAndKeepTheirPlace() {
        val old = "apps:true:new apps,screen time 7 days,screen time today,biggest apps:"
        val config = HubConfig.decode(old)

        assertEquals(listOf("new apps", "screen time", "biggest apps"), config.tiles("apps"))
        assertTrue(config.hiddenTiles["apps"].orEmpty().isEmpty())
        val again = HubConfig.decode(config.encode())
        assertEquals(config.tiles("apps"), again.tiles("apps"))
        assertEquals(config.encode(), again.encode())
    }

    @Test fun theNewTileIsHiddenOnlyWhenBothOldTilesWereHidden() {
        val one = HubConfig.decode("apps:true:screen time today,screen time 7 days,new apps:screen time 7 days")
        assertFalse("screen time" in one.hiddenTiles["apps"].orEmpty())

        val both = HubConfig.decode("apps:true:screen time today,screen time 7 days,new apps:screen time today,screen time 7 days")
        assertEquals(setOf("screen time"), both.hiddenTiles["apps"])
        assertEquals(listOf("screen time", "new apps", "biggest apps"), both.tiles("apps"))
    }

    @Test fun oldTodayTotalsTileBecomesDataTotalsInsideDevice() {
        val config = HubConfig.decode("network:true:top apps,today totals,traffic:today totals")

        assertEquals(listOf("top apps", "data totals", "traffic"), config.tiles(DevicePage.Id).take(3))
        assertTrue("data totals" in config.hiddenTiles[DevicePage.Id].orEmpty())
        assertEquals(HubCatalog.deviceTiles.toSet(), config.tiles(DevicePage.Id).toSet())
    }

    @Test fun aConfigThatNeverMetTheTilesGetsThemVisible() {
        val config = HubConfig.decode("apps:true:new apps:")

        assertEquals(listOf("new apps", "screen time", "biggest apps"), config.tiles("apps"))
        assertTrue(config.hiddenTiles["apps"].orEmpty().isEmpty())
        assertEquals(HubCatalog.appsTiles.toSet(), HubConfig().tiles("apps").toSet())
    }

    @Test fun currentTileMapsOnlyTheNamedPage() {
        assertEquals("screen time", HubCatalog.currentTile("apps", "screen time today"))
        assertEquals("screen time today", HubCatalog.currentTile("network", "screen time today"))
        assertEquals("top apps", HubCatalog.currentTile("network", "top apps"))
        assertEquals("traffic", HubCatalog.currentTile("system", "network"))
        assertEquals("battery", HubCatalog.currentTile("power", "charging"))
    }

    @Test fun rememberedPeriodsDecodePerPageAndDefaultWhenMissingOrUnknown() {
        val saved = mapOf("period_network" to "30d", "period_apps" to "from the future")
        val periods = HubPeriods.decode { saved[it] }

        assertEquals(LedgerPeriod.Month, periods["network"])
        assertEquals(LedgerPeriod.Default, periods["apps"])
        assertEquals(LedgerPeriod.Default, HubPeriods.decode { null }["network"])
    }

    @Test fun theRememberedPeriodKeysTravelInTheHubBackup() {
        for (page in HubPeriods.pages) {
            val key = BackupSpecs.hub.key(HubPeriods.keyName(page))
            assertNotNull(key)
            assertTrue(LedgerPeriod.entries.all { it.id.length <= key!!.maxLength })
        }
        assertTrue(AppBackup.HubSpec.key("period_network") != null)
    }
}
