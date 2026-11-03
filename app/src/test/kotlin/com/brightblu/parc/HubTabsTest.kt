package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hub config of before T158 for a user who never changed it: every page but the late additions on, catalog order. */
internal fun legacyHubConfig() = HubConfig(hiddenPages = HubCatalog.defaultHiddenPages, pageOrder = HubCatalog.pages)

/** T158: the hub card's tabs, the migration of saved hub configs, and the card's inset constants. */
class HubTabsTest {
    private val four = listOf("clock", "power", "focus", "notes")

    // ---- Default tabs and migration -------------------------------------------------------------------

    @Test fun aNewUserSeesClockPowerFocusNotesInThatOrder() {
        assertEquals(four, HubConfig().pages)
        assertEquals(four, HubConfig.decode("").pages)
    }

    @Test fun everyOtherPageStartsHiddenAndComesAfterTheFour() {
        val config = HubConfig()
        assertEquals(four, config.orderedPages.take(4))
        assertEquals(HubCatalog.pages.toSet(), config.orderedPages.toSet())
        (HubCatalog.pages - four.toSet()).forEach { assertTrue("$it hidden", it in config.hiddenPages) }
    }

    @Test fun unreadableDocumentsCountAsNoSavedChoice() {
        assertEquals(four, HubConfig.decode("broken;also:broken").pages)
        assertEquals(four, HubConfig.decode("future:true:a:b").pages)
    }

    @Test fun anOldConfigKeepsItsPagesAndOrderAndGainsNoDefaultTab() {
        // the owner's shape: Timers, Agenda and Comms enabled, Power hidden, Focus and Notes as the old defaults left them.
        val old = "clock:true::;system:true:battery:;power:false:charging:;network:true:traffic:;apps:true:new apps:;" +
            "sound:true:media:;timers:true:timers:;agenda:true:next events:;media:false:now playing:;focus:true::;notes:false::;comms:true::"
        val config = HubConfig.decode(old)
        assertEquals(listOf("clock", "system", "network", "apps", "sound", "timers", "agenda", "focus", "comms"), config.pages)
        assertEquals(listOf("clock", "system", "power", "network", "apps", "sound", "timers", "agenda", "media", "focus", "notes", "comms").filter { it in HubCatalog.pages },
            config.orderedPages.filter { it in HubCatalog.pages && it != QuickToggleCatalog.PageId && it != WeatherVariant.PageId })
        assertTrue("power" in config.hiddenPages)
        assertTrue("notes" in config.hiddenPages)
    }

    @Test fun nothingIsRemovedOrLostFromAnOldConfig() {
        val old = "clock:true::;timers:true:timers:;agenda:false:next events:;comms:true::"
        val config = HubConfig.decode(old)
        assertTrue(config.pages.containsAll(listOf("clock", "timers", "comms")))
        assertFalse("agenda" in config.pages)
        assertEquals(setOf("agenda"), config.orderedPages.toSet().intersect(config.hiddenPages).intersect(setOf("agenda")))
        // Re-saving keeps the same pages, order and hidden flags.
        val again = HubConfig.decode(config.encode())
        assertEquals(config.pages, again.pages)
        assertEquals(config.orderedPages, again.orderedPages)
        assertEquals(config.hiddenPages, again.hiddenPages)
    }

    @Test fun anOldConfigsPageThatItNeverMetStillUsesTheOldDefaults() {
        // Notes and Quick toggles are new to this document: they start hidden (the old rule), nothing else changes.
        val old = "clock:true::;system:true:battery:;power:true:charging:;network:true:traffic:;apps:true:new apps:;sound:true:media:;timers:true:timers:;agenda:true:next events:;media:true:now playing:;focus:true::;comms:true::"
        val config = HubConfig.decode(old)
        assertTrue("notes" in config.hiddenPages)
        assertTrue("system" in config.pages)
    }

    @Test fun aNewUsersConfigSurvivesASaveAndALoad() {
        val saved = HubConfig().encode()
        val loaded = HubConfig.decode(saved)
        assertEquals(four, loaded.pages)
        assertEquals(HubConfig().orderedPages, loaded.orderedPages)
        assertEquals(saved, loaded.encode())
    }

    @Test fun showingAnExtraTabPutsItAfterTheFourInItsOrder() {
        val config = HubConfig().showPage("timers", true)
        assertEquals(four + "timers", config.pages)
    }

    // ---- Tabs -----------------------------------------------------------------------------------------

    @Test fun theSelectedPillIsSurfaceMixedWithAccent() {
        val surface = 0xFF102030L
        val accent = 0xFFE0A040L
        assertEquals(surface, HubTabs.selectedFill(surface, accent, 0f))
        assertEquals(accent, HubTabs.selectedFill(surface, accent, 1f))
        val mixed = HubTabs.selectedFill(surface, accent)
        assertEquals(0xFFL, mixed ushr 24)
        listOf(16, 8, 0).forEach { shift ->
            val s = ((surface shr shift) and 0xff).toInt(); val a = ((accent shr shift) and 0xff).toInt(); val m = ((mixed shr shift) and 0xff).toInt()
            assertTrue("channel $shift between surface and accent", m in minOf(s, a)..maxOf(s, a))
            assertTrue("channel $shift moved toward accent", m != s)
        }
    }

    @Test fun theStripScrollsOnlyAsFarAsTheSelectedPillNeeds() {
        assertEquals(0f, HubTabs.scrollTarget(10f, 60f, 0f, 300f), 0f)       // already visible
        assertEquals(120f, HubTabs.scrollTarget(360f, 60f, 0f, 300f), 0f)    // off the right edge: right-align it
        assertEquals(50f, HubTabs.scrollTarget(50f, 60f, 200f, 300f), 0f)    // off the left edge: left-align it
        assertEquals(0f, HubTabs.scrollTarget(-5f, 60f, 0f, 300f), 0f)       // never negative
    }

    @Test fun aPillIsHalfTheTouchRowAndItsCornerIsHalfItsHeight() {
        assertEquals(Metrics.menuRowHeightDp / 2f, Metrics.hubTabPillHeightDp, 0f)
        assertTrue(Metrics.menuRowHeightDp >= Metrics.minTouchDp)
        assertTrue(Metrics.hubTabSelectedMix in 0.05f..0.5f)
    }

    // ---- Padding (the owner, 2026-10-27) -------------------------------------------------------------------

    @Test fun everyPageHasSixteenDpOnAllFourSides() {
        val inset = ParcSpacing.hubCard()
        assertEquals(16f, inset.startDp, 0f)
        assertEquals(16f, inset.topDp, 0f)
        assertEquals(16f, inset.endDp, 0f)
        assertEquals(16f, inset.bottomDp, 0f)
        assertEquals(Metrics.cardInsetDp, inset.startDp, 0f)
    }

    @Test fun rowsAreTwelveDpApartAndActionsAreAtLeastTwelveFromTheCardEdge() {
        assertEquals(12f, ParcSpacing.hubRowGapDp(), 0f)
        assertEquals(Metrics.rowGapDp, ParcSpacing.hubRowGapDp(), 0f)
        val inset = ParcSpacing.hubCard()
        assertTrue(inset.startDp >= Metrics.actionEdgeGapDp)
        assertTrue(inset.endDp >= Metrics.actionEdgeGapDp)
        assertTrue(inset.topDp >= Metrics.actionEdgeGapDp)
        assertTrue(inset.bottomDp >= Metrics.actionEdgeGapDp)
    }

    @Test fun theCardKeepsTheModuleCardsEdgeInsetAndCorner() {
        assertEquals(12f, Metrics.panelGutterDp, 0f)
        assertEquals(32f, com.brightblu.parc.model.ShapeTokens().moduleCornerDp, 0f)
    }

    @Test fun theFourTabsFitOneRowAtStandardWidthWithoutScrolling() {
        // 384 dp screen: 12 dp each side of the card, 16 dp inside. Four pills of about a name plus 24 dp of padding each.
        val inside = 384f - 2 * Metrics.panelGutterDp - 2 * ParcSpacing.hubCard().startDp
        val pill = { chars: Int -> chars * Metrics.hubTabNameSp * 0.6f + 2 * Metrics.hubTabPillPadDp + Metrics.hubTabGapDp }
        assertTrue(four.sumOf { pill(it.length).toDouble() } <= inside)
    }
}
