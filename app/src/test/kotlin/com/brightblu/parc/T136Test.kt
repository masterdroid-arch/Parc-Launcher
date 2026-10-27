package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T136: Quick toggles tile states, torch handling and hub page insertion. */
class T136Test {
    private class FakeTorch(var id: String? = "0", var result: TorchResult = TorchResult.Done) : TorchHardware {
        val calls = mutableListOf<Pair<String, Boolean>>()
        override fun flashCameraId() = id
        override fun setTorch(cameraId: String, on: Boolean): TorchResult { calls += cameraId to on; return result }
    }

    @Test fun torchSwitchesOnAndOff() {
        val hw = FakeTorch()
        assertEquals(TorchState.On, TorchLogic.toggle(hw, TorchState.Off))
        assertEquals(TorchState.Off, TorchLogic.toggle(hw, TorchState.On))
        assertEquals(listOf("0" to true, "0" to false), hw.calls)
    }

    @Test fun phoneWithoutFlashStaysMissingAndNeverCallsTheCamera() {
        val hw = FakeTorch(id = null)
        assertEquals(TorchState.Missing, TorchLogic.toggle(hw, TorchState.Off))
        assertTrue(hw.calls.isEmpty())
        val tile = QuickToggleTiles.tile("torch", QuickToggleInputs(torch = TorchState.Missing))!!
        assertEquals("No torch", tile.state)
        assertFalse(tile.on == true)
    }

    @Test fun busyCameraSaysSoAndIsRetriedOnTheNextTap() {
        val hw = FakeTorch(result = TorchResult.Busy)
        assertEquals(TorchState.Busy, TorchLogic.toggle(hw, TorchState.Off))
        val busy = QuickToggleTiles.tile("torch", QuickToggleInputs(torch = TorchState.Busy))!!
        assertEquals("Camera in use", busy.state)
        assertTrue(busy.hint.contains("Close the camera app"))
        hw.result = TorchResult.Done
        // Busy is not "on", so the next tap tries to switch the torch on.
        assertEquals(TorchState.On, TorchLogic.toggle(hw, TorchState.Busy))
        assertEquals("0" to true, hw.calls.last())
    }

    @Test fun cameraWithoutTorchAfterAllBecomesMissing() {
        assertEquals(TorchState.Missing, TorchLogic.toggle(FakeTorch(result = TorchResult.Missing), TorchState.Off))
    }

    @Test fun torchCallbacksOnlyFollowOurCamera() {
        assertEquals(TorchState.On, TorchLogic.onCallback(TorchState.Off, "0", "0", true))
        assertEquals(TorchState.Off, TorchLogic.onCallback(TorchState.On, "0", "0", false))
        assertEquals(TorchState.Busy, TorchLogic.onCallback(TorchState.On, "0", "0", null))
        assertEquals(TorchState.On, TorchLogic.onCallback(TorchState.On, "0", "1", false))
        assertEquals(TorchState.Missing, TorchLogic.onCallback(TorchState.Off, null, "0", true))
    }

    @Test fun everyCatalogTileHasAStateAndAnAction() {
        val tiles = QuickToggleTiles.tiles(QuickToggleCatalog.tiles, QuickToggleInputs())
        assertEquals(QuickToggleCatalog.tiles, tiles.map { it.id })
        tiles.forEach {
            assertTrue(it.label.isNotBlank()); assertTrue(it.state.isNotBlank()); assertTrue(it.hint.isNotBlank())
            assertTrue(it.description.startsWith(it.label))
        }
        assertNull(QuickToggleTiles.tile("unknown", QuickToggleInputs()))
    }

    @Test fun dndSwitchesWithAccessAndOtherwiseOpensTheAccessScreen() {
        val allowed = QuickToggleTiles.tile("do not disturb", QuickToggleInputs(dndAccess = true, dndOn = true))!!
        assertEquals(QuickAction.ToggleDnd, allowed.action)
        assertEquals("On", allowed.state)
        assertEquals(true, allowed.on)
        val missing = QuickToggleTiles.tile("do not disturb", QuickToggleInputs(dndAccess = false, dndOn = false))!!
        assertEquals(QuickAction.AllowDnd, missing.action)
        assertEquals("Off", missing.state)
        assertTrue(missing.description.contains("needs Do Not Disturb access"))
    }

    @Test fun platformLockedTilesOpenTheirScreenAndSaySoInTheirDescription() {
        val inputs = QuickToggleInputs(wifiOn = true, bluetoothOn = false, airplaneOn = null, autoRotateOn = true)
        val expected = mapOf(
            "wi-fi" to SystemScreen.WifiPanel, "bluetooth" to SystemScreen.BluetoothSettings,
            "mobile data" to SystemScreen.InternetPanel, "airplane mode" to SystemScreen.AirplaneSettings,
            "auto-rotate" to SystemScreen.DisplaySettings,
        )
        expected.forEach { (id, screen) ->
            val tile = QuickToggleTiles.tile(id, inputs)!!
            assertEquals(QuickAction.Open(screen), tile.action)
            assertTrue(tile.description.contains("Android does not let apps switch this"))
        }
        assertEquals("On", QuickToggleTiles.tile("wi-fi", inputs)!!.state)
        assertEquals("Off", QuickToggleTiles.tile("bluetooth", inputs)!!.state)
        assertEquals("Not shown", QuickToggleTiles.tile("airplane mode", inputs)!!.state)
        assertNull(QuickToggleTiles.tile("mobile data", inputs)!!.on)
        assertEquals(QuickAction.Open(SystemScreen.BatterySaverSettings), QuickToggleTiles.tile("battery saver", inputs)!!.action)
        assertEquals(QuickAction.Open(SystemScreen.DisplaySettings), QuickToggleTiles.tile("screen timeout", inputs)!!.action)
    }

    @Test fun screenTimeoutReadsAsWords() {
        assertEquals("30 s", QuickToggleTiles.timeout(30_000))
        assertEquals("2 min", QuickToggleTiles.timeout(120_000))
        assertEquals("1 h", QuickToggleTiles.timeout(3_600_000))
        assertEquals("Not shown", QuickToggleTiles.timeout(null))
        assertEquals("Not shown", QuickToggleTiles.timeout(-1))
    }

    @Test fun compactShowsOneRowOfFour() {
        val tiles = QuickToggleTiles.tiles(QuickToggleCatalog.tiles, QuickToggleInputs())
        assertEquals(4, QuickToggleTiles.shown(tiles, HubSize.Compact).size)
        assertEquals(4, QuickToggleTiles.columns(HubSize.Compact))
        assertEquals(tiles, QuickToggleTiles.shown(tiles, HubSize.Standard))
        assertEquals(tiles, QuickToggleTiles.shown(tiles, HubSize.Tall))
    }

    @Test fun demoUsesFixedSampleStates() {
        assertTrue(QuickToggleCatalog.PageId in DemoHubPages)
        val tiles = QuickToggleTiles.tiles(QuickToggleCatalog.tiles, QuickToggleInputs.Demo).associateBy { it.id }
        assertEquals("Off", tiles.getValue("torch").state)
        assertEquals("On", tiles.getValue("do not disturb").state)
        assertEquals("On", tiles.getValue("wi-fi").state)
        assertEquals("1 min", tiles.getValue("screen timeout").state)
    }

    @Test fun newUsersGetThePageHidden() {
        assertTrue(QuickToggleCatalog.PageId in HubCatalog.pages)
        assertFalse(QuickToggleCatalog.PageId in HubConfig().pages)
        assertEquals(QuickToggleCatalog.tiles, HubConfig().tiles(QuickToggleCatalog.PageId))
    }

    @Test fun oldSavedConfigGainsTheHiddenPageAfterSoundAndKeepsEverythingElse() {
        // A config saved before T136: every earlier page, Notes shown, Power hidden.
        val old = "clock:true::;system:true:battery,thermal:;power:false:charging:;network:true:traffic:;apps:true:new apps:;" +
            "sound:true:media:;timers:true:timers:;agenda:true:next events:;media:true:now playing:;focus:true::;notes:true::;comms:true::"
        val config = HubConfig.decode(old)
        assertEquals(config.orderedPages.indexOf("sound") + 1, config.orderedPages.indexOf(QuickToggleCatalog.PageId))
        assertTrue(QuickToggleCatalog.PageId in config.hiddenPages)
        assertEquals(listOf("clock", "system", "network", "apps", "sound", "timers", "agenda", "media", "focus", "notes", "comms"), config.pages)
        val shown = config.showPage(QuickToggleCatalog.PageId, true).showTile(QuickToggleCatalog.PageId, "mobile data", false)
            .move(QuickToggleCatalog.PageId, "wi-fi", -99)
        val restored = HubConfig.decode(shown.encode())
        assertTrue(QuickToggleCatalog.PageId in restored.pages)
        assertEquals("wi-fi", restored.tiles(QuickToggleCatalog.PageId).first())
        assertEquals(setOf("mobile data"), restored.hiddenTiles[QuickToggleCatalog.PageId])
    }

    @Test fun pageAndTileNamesReadAsWords() {
        assertEquals("Quick toggles", hubPageName(QuickToggleCatalog.PageId))
        assertEquals("Clock", hubPageName("clock"))
        assertEquals("Quick toggles", hubPageTitle(false, QuickToggleCatalog.PageId))
        assertEquals("Wi-Fi", hubTileLabel(QuickToggleCatalog.PageId, "wi-fi"))
        assertEquals("Do Not Disturb", hubTileLabel(QuickToggleCatalog.PageId, "do not disturb"))
        assertEquals("Wifi link", hubTileLabel("network", "wifi link"))
        // Tile ids are stored inside the ':' ';' ',' separated config text.
        QuickToggleCatalog.tiles.forEach { id -> assertTrue(id.none { it in ":;," }) }
    }
}
