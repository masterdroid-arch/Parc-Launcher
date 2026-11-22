package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T330: the stats hub's full-screen view: the setting, the gestures that open and close it, and the page it keeps. */
class T330Test {
    @Test
    fun theSettingIsOnByDefaultAndOnlyTheOptOutIsWritten() {
        val config = HubConfig()
        assertTrue(config.fullScreenEnabled)
        // A document that never met the setting reads as on, and the default document does not grow a row.
        assertFalse(config.encode().contains(HubConfig.FullRow))
        assertTrue(HubConfig.decode(config.encode()).fullScreenEnabled)
        assertTrue(HubConfig.decode("system:true:battery:").fullScreenEnabled)
        assertTrue(HubConfig.decode("").fullScreenEnabled)
    }

    @Test
    fun turningTheSettingOffSurvivesSaveAndOldReadersSkipTheExtraRow() {
        val off = HubConfig().copy(fullScreenEnabled = false)
        assertTrue(off.encode().contains(HubConfig.FullRow + "off"))
        assertFalse(HubConfig.decode(off.encode()).fullScreenEnabled)
        // The extra row has no four parts, so the page rows are unchanged by it.
        assertEquals(HubConfig().pages, HubConfig.decode(off.encode()).pages)
        assertFalse(HubConfig.decode(HubConfig().encode() + ";~full:off").fullScreenEnabled)
        // Garbage never turns the setting off.
        assertTrue(HubConfig.decode(HubConfig().encode() + ";~full:maybe").fullScreenEnabled)
    }

    @Test
    fun theSettingRowLivesUnderTheHubSettingsAndReadsBothWays() {
        val def = SettingsRegistry.byId.getValue("HubFullScreen")
        assertEquals(SettingsPage.HubPages, def.page)
        assertEquals("Stats hub", def.card)
        assertEquals(1, def.read!!(SettingsSnapshot(hubFullScreen = true)))
        assertEquals(0, def.read!!(SettingsSnapshot(hubFullScreen = false)))
        assertTrue(def.write!!(SettingsSnapshot(hubFullScreen = true), 1).hubFullScreen)
        assertFalse(def.write!!(SettingsSnapshot(hubFullScreen = false), 0).hubFullScreen)
        assertEquals("On", def.value(SettingsSnapshot(hubFullScreen = true)))
        assertEquals("Off", def.value(SettingsSnapshot(hubFullScreen = false)))
        // It is offered only where it can act: with the stats hub on.
        assertTrue(def.shown(SettingsSnapshot(statsVisible = true)))
        assertFalse(def.shown(SettingsSnapshot(statsVisible = false)))
        // The words match the view's own Expand control.
        assertEquals(HubFullScreen.ExpandLabel, "Expand")
    }

    @Test
    fun theGripPullOpensOnlyUpwardAndOnlyWhileTheSettingIsOn() {
        assertTrue(HubFullScreen.opensOnGripPull(-HubFullScreen.OpenPullDp, enabled = true))
        assertTrue(HubFullScreen.opensOnGripPull(-120f, enabled = true))
        assertFalse(HubFullScreen.opensOnGripPull(-HubFullScreen.OpenPullDp + 0.1f, enabled = true))
        assertFalse(HubFullScreen.opensOnGripPull(-200f, enabled = false))
        // Downward is never an open.
        assertFalse(HubFullScreen.opensOnGripPull(120f, enabled = true))
        assertFalse(HubFullScreen.opensOnGripPull(Float.NaN, enabled = true))
    }

    @Test
    fun swipingDownClosesFromTheTopOfAPage() {
        assertTrue(HubFullScreen.closesOnPageSwipe(atTop = true, dxDp = 8f, dyDp = HubFullScreen.CloseSwipeDp))
        assertTrue(HubFullScreen.closesOnPageSwipe(atTop = true, dxDp = -20f, dyDp = 140f))
        assertFalse("only from the top", HubFullScreen.closesOnPageSwipe(atTop = false, dxDp = 0f, dyDp = 200f))
        assertFalse("a short drag is a scroll", HubFullScreen.closesOnPageSwipe(atTop = true, dxDp = 0f, dyDp = HubFullScreen.CloseSwipeDp - 1f))
        assertFalse("sideways is a page swipe", HubFullScreen.closesOnPageSwipe(atTop = true, dxDp = 120f, dyDp = 100f))
        assertTrue(HubFullScreen.closesOnGripPull(HubFullScreen.CloseSwipeDp))
        assertFalse(HubFullScreen.closesOnGripPull(HubFullScreen.CloseSwipeDp - 1f))
    }

    @Test
    fun openingAndClosingKeepsTheSelectedPageAndTheSavedSize() {
        val pages = listOf("today", "device", "notes")
        assertEquals("device", HubFullScreen.pageAfterToggle("device", pages))
        // A page hidden while the view was open falls back to the first tab instead of showing nothing.
        assertEquals("today", HubFullScreen.pageAfterToggle("device", listOf("today", "notes")))
        assertEquals("", HubFullScreen.pageAfterToggle("today", emptyList()))
        // Home's own size comes back exactly as it was: the full-screen view only reports Tall while it is open.
        assertEquals(HubSize.Tall, HubFullScreen.sizeFor(true, HubSize.Compact))
        assertEquals(HubSize.Tall, HubFullScreen.sizeFor(true, HubSize.Standard))
        assertEquals(HubSize.Compact, HubFullScreen.sizeFor(false, HubSize.Compact))
        assertEquals(HubSize.Standard, HubFullScreen.sizeFor(false, HubSize.Standard))
    }

    @Test
    fun theFullScreenViewShowsEveryTileAndMayGrowPastTheCardsLimit() {
        val tiles = listOf("battery", "thermal", "ram", "storage")
        assertEquals(tiles, HubPanelLayout.visibleTiles(tiles, HubFullScreen.sizeFor(true, HubSize.Compact), 200f, 1f))
        assertEquals(1, HubPanelLayout.visibleTiles(tiles, HubFullScreen.sizeFor(false, HubSize.Compact), 200f, 1f).size)
        assertEquals(Metrics.hubFullTileMaxHeightDp, HubPanelLayout.tileHeightDp(2_000f, 2, Metrics.hubFullTileMaxHeightDp))
        assertEquals(Metrics.hubTileMaxHeightDp, HubPanelLayout.tileHeightDp(2_000f, 2))
    }

    @Test
    fun theFullScreenPanelUsesThePageGutterAndTheCardInset() {
        val inset = ParcSpacing.hubFullScreen()
        assertEquals(Metrics.screenGutterDp, inset.startDp)
        assertEquals(Metrics.screenGutterDp, inset.endDp)
        assertEquals(Metrics.cardInsetDp, inset.topDp)
        assertEquals(Metrics.cardInsetDp, inset.bottomDp)
        // The compact card keeps its 16 dp inset on every side.
        assertEquals(ParcSpacing.hubCard().startDp, ParcSpacing.hubFullScreen().topDp)
    }

    @Test
    fun theControlsHaveWordsForTalkBack() {
        assertEquals("Open full-screen hub", HubFullScreen.ExpandDescription)
        assertEquals("Close full-screen hub", HubFullScreen.CloseDescription)
        assertEquals("Full-screen hub. Tap Close, swipe down or press Back to return", HubFullScreen.GripDescription)
        assertEquals("Full-screen hub", HubFullScreen.ContainerDescription)
        assertEquals("Expand", HubFullScreen.ExpandLabel)
        assertEquals("Close", HubFullScreen.CloseLabel)
    }
}
