package com.brightblu.parc

import org.junit.Assert.*
import org.junit.Test

class HubConfigTest {
    @Test fun defaultsIncludeThreePagesAndTheDeviceSections() {
        // T219: Focus is the hub chip's front door, so it is not auto-pinned for a new user.
        assertEquals(listOf(TodayPage.Id, DevicePage.Id, "notes"), HubConfig().pages)
        // T200: the arcade is in the catalog but invisible until a game is unlocked.
        assertEquals(HubCatalog.pages - HubCatalog.defaultHiddenPages - com.brightblu.parc.arcade.ArcadePage.Id, legacyHubConfig().pages)
        assertEquals(HubCatalog.deviceTiles, HubConfig().tiles(DevicePage.Id))
        assertEquals(listOf("Power", "Network", "Storage", "Thermal"), HubCatalog.deviceSections.keys.toList())
    }
    @Test fun reorderClampsAndKeepsEveryTileOnce() {
        val config = HubConfig().move(DevicePage.Id, "storage", -99)
        assertEquals("storage", config.tiles(DevicePage.Id).first())
        assertEquals(HubCatalog.deviceTiles.toSet(), config.tiles(DevicePage.Id).toSet())
        assertEquals("storage", config.move(DevicePage.Id, "storage", 99).tiles(DevicePage.Id).last())
    }
    @Test fun hiddenTileRemainsInItsOrderedPosition() {
        val config = HubConfig().move(DevicePage.Id, "wifi link", -99).showTile(DevicePage.Id, "wifi link", false)
        assertTrue("wifi link" in config.hiddenTiles[DevicePage.Id].orEmpty())
        assertEquals("wifi link", config.showTile(DevicePage.Id, "wifi link", true).tiles(DevicePage.Id).first())
    }
    @Test fun savedConfigFromBeforeMediaGainsVisibleMediaPageBeforeComms() {
        val old = "system:true:battery:;power:false:charging:;network:true:traffic:;apps:true:new apps:;sound:true:media:;comms:true::"
        val config = HubConfig.decode(old)
        assertEquals(listOf("today", "device", "apps", "controls", "timers", "agenda", "media", "focus", "comms"), config.pages)
        assertEquals(listOf("now playing"), config.tiles("media"))
        assertEquals("media", restoredHubPage("media", config))
        assertEquals(DevicePage.Id, restoredHubPage("media", config.showPage("media", false)))
        assertEquals(config.encode(), HubConfig.decode(config.encode()).encode())
    }
    @Test fun savedConfigFromBeforeAgendaGainsVisibleAgendaPageBeforeMedia() {
        val old = "system:true:battery:;power:true:charging:;network:true:traffic:;apps:true:new apps:;sound:true:media:;media:true:now playing:;comms:true::"
        val config = HubConfig.decode(old)
        assertEquals(listOf("today", "device", "apps", "controls", "timers", "agenda", "media", "focus", "comms"), config.pages)
        assertEquals(config.pages.indexOf("media") - 1, config.pages.indexOf("agenda"))
        assertEquals(listOf("next events"), config.tiles("agenda"))
        assertTrue("agenda" !in config.hiddenPages)
        assertEquals("agenda", restoredHubPage("agenda", config))
        assertEquals(DevicePage.Id, restoredHubPage("agenda", config.showPage("agenda", false)))
        // The remembered page of an old config is mapped to its merged page.
        assertEquals(ControlsPage.Id, restoredHubPage("sound", config))
        assertEquals(config.encode(), HubConfig.decode(config.encode()).encode())
    }
    @Test fun agendaPageAndItsTileCanBeHiddenFromTheEditor() {
        val config = HubConfig().showTile("agenda", "next events", false).showPage("agenda", false)
        assertTrue("agenda" !in config.pages)
        assertEquals(setOf("next events"), config.hiddenTiles["agenda"])
        val restored = HubConfig.decode(config.encode())
        assertTrue("agenda" !in restored.pages)
        assertEquals(setOf("next events"), restored.hiddenTiles["agenda"])
    }
    @Test fun allPagesCanBeHiddenAndRestored() {
        val config = HubCatalog.pages.fold(HubConfig()) { c, p -> c.showPage(p, false) }
        assertTrue(config.pages.isEmpty())
        assertEquals(listOf(ControlsPage.Id), config.showPage("sound", true).pages)
    }
    @Test fun persistenceRoundTripPreservesConfiguration() {
        val config = HubConfig().move(DevicePage.Id, "uptime", -3).showTile(DevicePage.Id, "battery", false).showPage("power", false)
        val restored = HubConfig.decode(config.encode())
        assertEquals(config.pages, restored.pages)
        assertEquals(config.tiles(DevicePage.Id), restored.tiles(DevicePage.Id))
        assertEquals(config.hiddenTiles[DevicePage.Id], restored.hiddenTiles[DevicePage.Id])
        assertEquals(config.encode(), restored.encode())
    }
    @Test fun unknownIdsDuplicatesAndMalformedRowsAreIgnored() {
        val config = HubConfig.decode("future:false:foo:foo;system:true:wifi,wifi,future:future,ram;broken")
        assertEquals("wifi link", config.tiles(DevicePage.Id).first())
        assertEquals(HubCatalog.deviceTiles.size, config.tiles(DevicePage.Id).size)
        assertTrue("ram" in config.hiddenTiles[DevicePage.Id].orEmpty())
        assertEquals(HubConfig(), HubConfig().move(DevicePage.Id, "future", 1).showPage("future", false).showTile(DevicePage.Id, "future", false))
    }
}
