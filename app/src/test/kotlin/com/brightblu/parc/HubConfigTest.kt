package com.brightblu.parc

import org.junit.Assert.*
import org.junit.Test

class HubConfigTest {
    @Test fun defaultsIncludeFivePagesAndSevenSystemTiles() {
        assertEquals(HubCatalog.pages - HubCatalog.defaultHiddenPages, HubConfig().pages)
        assertEquals(HubCatalog.systemTiles, HubConfig().tiles("system"))
    }
    @Test fun reorderClampsAndKeepsEveryTileOnce() {
        val config = HubConfig().move("system", "storage", -99)
        assertEquals("storage", config.tiles("system").first())
        assertEquals(HubCatalog.systemTiles.toSet(), config.tiles("system").toSet())
        assertEquals("storage", config.move("system", "storage", 99).tiles("system").last())
    }
    @Test fun hiddenTileRemainsInItsOrderedPosition() {
        val config = HubConfig().move("system", "wifi", -99).showTile("system", "wifi", false)
        assertTrue("wifi" in config.hiddenTiles["system"].orEmpty())
        assertEquals("wifi", config.showTile("system", "wifi", true).tiles("system").first())
    }
    @Test fun savedConfigFromBeforeMediaGainsVisibleMediaPageBeforeComms() {
        val old = "system:true:battery:;power:false:charging:;network:true:traffic:;apps:true:new apps:;sound:true:media:;comms:true::"
        val config = HubConfig.decode(old)
        assertEquals(listOf("clock", "system", "network", "apps", "sound", "timers", "agenda", "media", "focus", "comms"), config.pages)
        assertEquals(listOf("now playing"), config.tiles("media"))
        assertEquals("media", restoredHubPage("media", config))
        assertEquals("system", restoredHubPage("media", config.showPage("media", false)))
        assertEquals(config.encode(), HubConfig.decode(config.encode()).encode())
    }
    @Test fun savedConfigFromBeforeAgendaGainsVisibleAgendaPageBeforeMedia() {
        val old = "system:true:battery:;power:true:charging:;network:true:traffic:;apps:true:new apps:;sound:true:media:;media:true:now playing:;comms:true::"
        val config = HubConfig.decode(old)
        assertEquals(listOf("clock", "system", "power", "network", "apps", "sound", "timers", "agenda", "media", "focus", "comms"), config.pages)
        assertEquals(config.pages.indexOf("media") - 1, config.pages.indexOf("agenda"))
        assertEquals(listOf("next events"), config.tiles("agenda"))
        assertTrue("agenda" !in config.hiddenPages)
        assertEquals("agenda", restoredHubPage("agenda", config))
        assertEquals("system", restoredHubPage("agenda", config.showPage("agenda", false)))
        // The remembered page of an old config is untouched.
        assertEquals("sound", restoredHubPage("sound", config))
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
        assertEquals(listOf("sound"), config.showPage("sound", true).pages)
    }
    @Test fun persistenceRoundTripPreservesConfiguration() {
        val config = HubConfig().move("system", "uptime", -3).showTile("system", "battery", false).showPage("power", false)
        val restored = HubConfig.decode(config.encode())
        assertEquals(config.pages, restored.pages)
        assertEquals(config.tiles("system"), restored.tiles("system"))
        assertEquals(config.hiddenTiles["system"], restored.hiddenTiles["system"])
        assertEquals(config.encode(), restored.encode())
    }
    @Test fun unknownIdsDuplicatesAndMalformedRowsAreIgnored() {
        val config = HubConfig.decode("future:false:foo:foo;system:true:wifi,wifi,future:future,ram;broken")
        assertEquals("wifi", config.tiles("system").first())
        assertEquals(HubCatalog.systemTiles.size, config.tiles("system").size)
        assertEquals(HubCatalog.systemHistoryTiles.keys + "ram", config.hiddenTiles["system"])
        assertEquals(HubConfig(), HubConfig().move("system", "future", 1).showPage("future", false).showTile("system", "future", false))
    }
}
