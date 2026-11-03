package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T166: the spacing system is a set of numbers (Metrics) and a few pure helpers (ParcSpacing) that the four blocks
 * (ParcPage, ParcCardContent, ParcSettingsRow, ParcSectionHeader) are built from. These tests pin the numbers the card names
 * and the arithmetic the screens rely on, on the three screens the card lists.
 */
class SpacingSystemTest {
    private val screens = listOf(384f to 853f, 360f to 780f, 411f to 891f)

    @Test fun tokensMatchTheCard() {
        assertEquals(20f, Metrics.screenGutterDp, 0f)
        assertEquals(16f, Metrics.cardInsetDp, 0f)
        assertEquals(12f, Metrics.rowGapDp, 0f)
        assertEquals(24f, Metrics.sectionGapDp, 0f)
        assertEquals(8f, Metrics.sectionHeaderGapDp, 0f)
        assertEquals(48f, Metrics.minTouchDp, 0f)
        assertEquals(12f, Metrics.actionEdgeGapDp, 0f)
        assertEquals(56f, Metrics.touchTargetDp, 0f) // the module keeps 56
        assertEquals(56f, Metrics.settingsRowMinHeightDp, 0f)
        assertEquals(20f, Metrics.settingsRowInsetDp, 0f)
    }

    @Test fun theGutterIsTheDesignTextGutter() {
        assertEquals(Metrics.textGutterDp, Metrics.screenGutterDp, 0f)
        assertEquals(Metrics.screenGutterDp, Metrics.settingsRowInsetDp, 0f)
    }

    @Test fun everyTouchSizeIsAtLeastTheMinimum() {
        listOf(Metrics.touchTargetDp, Metrics.menuRowHeightDp, Metrics.commsActionDp, Metrics.focusControlDp,
            Metrics.settingsRowMinHeightDp).forEach { assertTrue("$it", it >= Metrics.minTouchDp) }
        assertEquals(48f, ParcSpacing.touchDp(20f), 0f)
        assertEquals(64f, ParcSpacing.touchDp(64f), 0f)
    }

    @Test fun pageTopClearsTheStatusBarByACardInsetAndNeverTheKeepOut() {
        assertEquals(56f, ParcSpacing.pageTopDp(0f), 0f)
        assertEquals(56f, ParcSpacing.pageTopDp(24f), 0f)
        assertEquals(46.9f + 16f, ParcSpacing.pageTopDp(46.9f), 0.001f) // 1080 x 2400 at 450 dpi
        assertEquals(96f, ParcSpacing.pageTopDp(80f), 0f)               // a tall cutout
        assertEquals(56f, ParcSpacing.pageTopDp(-5f), 0f)
    }

    @Test fun pageBottomClearsTheGestureStripAndTheThreeButtonBar() {
        assertEquals(56f, ParcSpacing.pageBottomDp(0f), 0f)
        assertEquals(56f, ParcSpacing.pageBottomDp(24f), 0f)  // gesture navigation on the emulator
        assertEquals(72f, ParcSpacing.pageBottomDp(48f), 0f)  // three buttons: a section gap above the bar
        assertEquals(124f, ParcSpacing.pageBottomDp(100f), 0f)
    }

    @Test fun pageSidesAreTheGutterPlusAnyCutout() {
        val plain = ParcSpacing.page(statusTopDp = 46.9f, navBottomDp = 24f)
        assertEquals(20f, plain.startDp, 0f)
        assertEquals(20f, plain.endDp, 0f)
        val cut = ParcSpacing.page(statusTopDp = 0f, navBottomDp = 0f, startInsetDp = 30f, endInsetDp = 12f)
        assertEquals(50f, cut.startDp, 0f)
        assertEquals(32f, cut.endDp, 0f)
        assertEquals(20f, ParcSpacing.page(0f, 0f, -8f, -8f).startDp, 0f)
    }

    @Test fun aPageLeavesRoomForContentOnEveryScreen() {
        screens.forEach { (w, h) ->
            val p = ParcSpacing.page(statusTopDp = 46.9f, navBottomDp = 48f)
            assertTrue(w - p.startDp - p.endDp >= 280f)
            assertTrue(h - p.topDp - p.bottomDp >= 600f)
        }
    }

    @Test fun cardContentIsTheCardMinusOneInsetOnEachSide() {
        assertEquals(320f, ParcSpacing.cardContentWidthDp(352f), 0f)
        assertEquals(40f, ParcSpacing.cardContentHeightDp(72f), 0f)
        assertEquals(0f, ParcSpacing.cardContentWidthDp(20f), 0f)
        assertEquals(72f, ParcSpacing.cardHeightDp(40f), 0f)
    }

    @Test fun aSettingsRowIsAtLeast56AndTwelveAboveAndBelow() {
        assertEquals(56f, ParcSpacing.settingsRowHeightDp(20f), 0f)  // one line of text
        assertEquals(56f, ParcSpacing.settingsRowHeightDp(32f), 0f)  // a switch
        assertEquals(64f, ParcSpacing.settingsRowHeightDp(40f), 0f)  // a 40 dp sample: 12 + 40 + 12
        assertEquals(100f, ParcSpacing.settingsRowHeightDp(76f), 0f)
    }

    @Test fun theFirstAndLastRowOfACardSitOneCardInsetFromItsEdge() {
        assertEquals(4f, ParcSpacing.rowCardVerticalPaddingDp(), 0f)
        assertEquals(Metrics.cardInsetDp, Metrics.rowGapDp + ParcSpacing.rowCardVerticalPaddingDp(), 0f)
    }

    @Test fun aRowKeepsTwentyAtBothEndsAndAGapBetweenItsParts() {
        // The page gutter takes 20 dp each side of a 384 dp screen, so a card is 344 dp wide.
        val card = 384f - 2 * Metrics.screenGutterDp
        assertEquals(304f, ParcSpacing.settingsTextWidthDp(card), 0f)                        // title only
        assertEquals(304f - 52f - 12f, ParcSpacing.settingsTextWidthDp(card, trailingDp = 52f), 0f) // a switch
        assertEquals(304f - 40f - 12f - 52f - 12f, ParcSpacing.settingsTextWidthDp(card, 40f, 52f), 0f)
        assertEquals(0f, ParcSpacing.settingsTextWidthDp(60f, 40f, 52f), 0f)
    }

    @Test fun aSettingsTitleKeepsAReadableWidthWithASwitchOnTheSmallestScreen() {
        val card = 360f - 2 * Metrics.screenGutterDp
        assertTrue(ParcSpacing.settingsTextWidthDp(card, leadingDp = Metrics.settingsSampleDp, trailingDp = 52f) >= 150f)
    }

    @Test fun aSectionHeaderIsTwentyFourAboveAndEightBelow() {
        assertEquals(24f, ParcSpacing.sectionHeaderTopDp(), 0f)
        assertEquals(32f, ParcSpacing.sectionHeaderBlockDp(), 0f)
        // Inside a container that already puts a row gap between its blocks, the header takes that gap out of its own.
        assertEquals(12f, ParcSpacing.sectionHeaderTopDp(Metrics.rowGapDp), 0f)
        assertEquals(24f, Metrics.rowGapDp + ParcSpacing.sectionHeaderTopDp(Metrics.rowGapDp) , 0f)
        assertEquals(0f, ParcSpacing.sectionHeaderTopDp(40f), 0f)
    }

    @Test fun aCardWithoutAHeaderStartsARowGapBelowTheBlockAbove() {
        assertEquals(12f, ParcSpacing.groupLeadDp(), 0f)
        assertEquals(0f, ParcSpacing.groupLeadDp(Metrics.rowGapDp), 0f)
    }

    @Test fun anActionStaysAnActionEdgeGapFromTheEdgeOfItsCardOrScreen() {
        // A 48 dp box with a 24 dp glyph in its middle: the glyph is 12 + 12 from the edge.
        assertEquals(24f, ParcSpacing.actionGlyphInsetDp(48f, 24f), 0f)
        // The Back row: the touch area starts one action edge gap from the edge and its word on the gutter.
        assertEquals(Metrics.screenGutterDp, Metrics.actionEdgeGapDp + Metrics.itemGapDp, 0f)
        assertTrue(Metrics.actionEdgeGapDp <= Metrics.cardInsetDp)
    }

    @Test fun theStatsPanelKeepsACardInsetOnlyWhenItIsDrawnAsACard() {
        assertEquals(16f, ParcSpacing.panelHorizontalInsetDp(true), 0f)
        assertEquals(16f, ParcSpacing.panelVerticalInsetDp(true), 0f) // T158: 16 dp on all four sides
        assertEquals(0f, ParcSpacing.panelHorizontalInsetDp(false), 0f)
        assertEquals(0f, ParcSpacing.panelVerticalInsetDp(false), 0f)
        // The header row (56) and the grip row (48) already put 16 dp or more between their content and the panel edge.
        val headerSlack = (Metrics.touchTargetDp - Metrics.hubHeaderHeightDp) / 2f
        assertTrue(ParcSpacing.panelVerticalInsetDp(true) + headerSlack >= Metrics.cardInsetDp)
    }

    @Test fun aStatsCellKeepsACardInsetUnlessItIsATile() {
        assertEquals(16f, ParcSpacing.cellHorizontalInsetDp(false), 0f)
        assertEquals(16f, ParcSpacing.cellVerticalInsetDp(false), 0f)
        assertEquals(8f, ParcSpacing.cellHorizontalInsetDp(true), 0f)
        assertEquals(12f, ParcSpacing.cellVerticalInsetDp(true), 0f)
    }

    @Test fun cellsAreCardsExceptThreeQuickTogglesInARow() {
        screens.forEach { (w, _) ->
            val content = w - 2 * Metrics.panelGutterDp - 2 * ParcSpacing.panelHorizontalInsetDp(true)
            val twoUp = (content - Metrics.hudCellGapDp) / 2f
            val threeUp = (content - 2 * Metrics.hudCellGapDp) / 3f
            assertTrue("two across on $w dp is a card", twoUp >= Metrics.narrowCardDp)
            assertTrue("three across on $w dp is a tile", threeUp < Metrics.narrowCardDp)
        }
    }

    @Test fun sevenPageDotsShareTheRowWhenTheyDoNotFit() {
        val inside = 384f - 2 * Metrics.panelGutterDp - 2 * ParcSpacing.panelHorizontalInsetDp(true) // 328 (T158: 12 dp gutter)
        assertEquals(inside / 7f, ParcSpacing.dotWidthDp(inside, 7), 0.001f)
        assertTrue(7 * ParcSpacing.dotWidthDp(inside, 7) <= inside + 0.001f)
        assertEquals(48f, ParcSpacing.dotWidthDp(352f, 7), 0f)  // no card: seven fit at 48 dp
        assertEquals(48f, ParcSpacing.dotWidthDp(320f, 5), 0f)
        assertEquals(48f, ParcSpacing.dotWidthDp(320f, 0), 0f)
    }

    @Test fun theModuleCardAndTheArcsAreUntouchedByTheSpacingTokens() {
        assertEquals(360f, Metrics.moduleCardWidthDp, 0f)
        assertEquals(357f, Metrics.moduleCardAboveOriginDp, 0f)
        assertEquals(68f, Metrics.moduleCardBelowOriginDp, 0f)
        assertEquals(60f, Metrics.minSlotPitchDp, 0f)
        assertEquals(56f, Metrics.keepOutTopDp, 0f)
        assertEquals(56f, Metrics.keepOutBottomDp, 0f)
        assertFalse(Metrics.screenGutterDp == Metrics.panelGutterDp) // the panel keeps its own gutter next to the module card
    }
}
