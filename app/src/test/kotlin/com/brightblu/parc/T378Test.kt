package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T378: hub scrolling and page density — the edge thumb, whole-row density and end clearance. */
class T378Test {
    private fun source(path: String) = File("src/main/kotlin/com/brightblu/parc/$path").readText()

    @Test fun progressAndViewportShareAreBoundedFractions() {
        assertEquals(0f, HubScrollThumb.fraction(0, 0), 0f)
        assertEquals(0f, HubScrollThumb.fraction(40, 0), 0f)
        assertEquals(0f, HubScrollThumb.fraction(0, 400), 0f)
        assertEquals(0.5f, HubScrollThumb.fraction(200, 400), 0f)
        assertEquals(1f, HubScrollThumb.fraction(400, 400), 0f)
        assertEquals(1f, HubScrollThumb.fraction(900, 400), 0f)
        assertEquals(1f, HubScrollThumb.viewportFraction(0, 0), 0f)
        assertEquals(1f, HubScrollThumb.viewportFraction(400, 0), 0f)
        assertEquals(0.5f, HubScrollThumb.viewportFraction(400, 400), 0f)
        assertEquals(0.25f, HubScrollThumb.viewportFraction(200, 600), 0f)
    }

    @Test fun thumbHeightIsTheVisibleShareWithinItsLimits() {
        // 800 dp of content in a 400 dp viewport: half the track, then grown to the minimum.
        assertEquals(200f, HubScrollThumb.heightDp(400f, 0.5f), 0f)
        assertEquals(Metrics.hubScrollThumbMinDp, HubScrollThumb.heightDp(400f, 0.01f), 0f)
        assertEquals(400f, HubScrollThumb.heightDp(400f, 1f), 0f)
        assertEquals(0f, HubScrollThumb.heightDp(0f, 0.5f), 0f)
        // A track shorter than the minimum can only grow to the track itself.
        assertEquals(Metrics.hubScrollThumbMinDp / 2f,
            HubScrollThumb.heightDp(Metrics.hubScrollThumbMinDp / 2f, 0.2f), 0f)
        assertEquals(Metrics.hubScrollThumbMinDp, HubScrollThumb.heightDp(52f, 0f), 0f)
    }

    @Test fun thumbTravelsFromTheFirstRowToTheLastWithoutLeavingTheTrack() {
        val track = 400f
        val thumb = HubScrollThumb.heightDp(track, 0.5f)
        assertEquals(0f, HubScrollThumb.topDp(track, thumb, 0f), 0f)
        assertEquals(100f, HubScrollThumb.topDp(track, thumb, 0.5f), 0f)
        assertEquals(track - thumb, HubScrollThumb.topDp(track, thumb, 1f), 0f)
        assertEquals(0f, HubScrollThumb.topDp(track, thumb, -1f), 0f)
        assertEquals(track - thumb, HubScrollThumb.topDp(track, thumb, 2f), 0f)
        // The whole thumb always stays between 0 and the track height.
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val top = HubScrollThumb.topDp(track, thumb, progress)
            assertTrue(top >= 0f)
            assertTrue(top + thumb <= track + 0.001f)
        }
    }

    @Test fun lazyThumbAccountsForClippedCellsAndMultipleColumns() {
        fun tallCell(progress: Boolean, forward: Boolean = true, backward: Boolean = true) =
            HubScrollThumb.lazyReading(progress, forward, backward, 1, 1, 0, -100, 300, 0, 200)
        assertEquals(0.5f, tallCell(true), 0f)
        assertEquals(0.5f, tallCell(false), 0f)
        assertEquals(0f, tallCell(true, backward = false), 0f)
        assertEquals(1f, tallCell(true, forward = false), 0f)
        // Four rows of three cells. The middle two rows are on screen.
        assertEquals(0.5f, HubScrollThumb.lazyReading(true, true, true, 12, 6, 3, 0, 200, 0, 200), 0f)
        assertEquals(0.5f, HubScrollThumb.lazyReading(false, true, true, 12, 6, 3, 0, 200, 0, 200), 0f)
        assertEquals(1f, HubScrollThumb.lazyReading(false, false, false, 0, 0, 0, 0, 0, 0, 0), 0f)
    }

    @Test fun thumbVisibilityKeepsTheSettleBeatAtTheEndOnly() {
        assertTrue(HubScrollThumb.visible(canScrollForward = true, settling = false, canScrollBackward = true))
        assertTrue(HubScrollThumb.visible(canScrollForward = false, settling = true, canScrollBackward = true))
        assertFalse(HubScrollThumb.visible(canScrollForward = false, settling = false, canScrollBackward = true))
        assertFalse("a page that fits never shows it", HubScrollThumb.visible(false, true, false))
    }

    @Test fun standardDensityCountsTheGapsBetweenRows() {
        // 4 rows at 56 dp with three 12 dp gaps need 260 dp; cell-only maths would claim 5.
        assertEquals(4, HubPanelLayout.rowsThatFit(260f, 56f))
        assertEquals(3, HubPanelLayout.rowsThatFit(259f, 56f))
        assertEquals(5, HubPanelLayout.rowsThatFit(329f, 56f))
        assertEquals(0, HubPanelLayout.rowsThatFit(0f, 56f))
        val plan = HubPanelLayout.density(260f, 8, HubSize.Standard, 1f)
        assertEquals(4, plan.rows)
        assertEquals(56f, plan.tileHeightDp, 0.001f)
        // The whole plan, gaps included, fits the panel.
        assertTrue(plan.tileHeightDp * plan.rows + Metrics.hudCellGapDp * (plan.rows - 1) <= 260f + 0.001f)
    }

    @Test fun standardDensityGrowsRowsToFillThePanelButNeverOverflows() {
        for (available in listOf(180f, 260f, 400f, 600f)) for (scale in listOf(1f, 1.3f, 2f)) {
            val plan = HubPanelLayout.density(available, 12, HubSize.Standard, scale)
            assertTrue("rows=$plan", plan.rows >= 1)
            assertTrue("height=$plan", plan.tileHeightDp >= Metrics.hudCellHeightDp)
            assertTrue("height=$plan", plan.tileHeightDp <= Metrics.hubTileMaxHeightDp)
            assertTrue("overflow at $available/$scale: $plan",
                plan.tileHeightDp * plan.rows + Metrics.hudCellGapDp * (plan.rows - 1) <= available + 0.001f)
            // Larger text never claims more rows than normal text at the same panel height.
            val normal = HubPanelLayout.density(available, 12, HubSize.Standard, 1f)
            assertTrue(plan.rows <= normal.rows)
        }
    }

    @Test fun compactKeepsOneRowAndTallKeepsEveryTileInOrder() {
        val tiles = listOf("main", "a", "b", "history")
        val compact = HubPanelLayout.density(80f, tiles.size, HubSize.Compact, 1.3f)
        assertEquals(1, compact.rows)
        assertEquals(listOf("main"), HubPanelLayout.visibleTiles(tiles, HubSize.Compact, 80f, 1.3f))
        val tall = HubPanelLayout.density(240f, tiles.size, HubSize.Tall, 1.3f)
        assertEquals(tiles.size, tall.rows)
        assertEquals(tiles, HubPanelLayout.visibleTiles(tiles, HubSize.Tall, 240f, 1.3f))
        assertEquals(0, HubPanelLayout.density(200f, 0, HubSize.Standard, 1f).rows)
        assertTrue(HubPanelLayout.density(240f, 400, HubSize.Tall, 1f).tileHeightDp >= Metrics.hudCellHeightDp)
    }

    @Test fun endPaddingClearsThePartOfTheFadeThatStillMasksText() {
        val viewport = 400f
        for (rows in listOf(100f, 240f, 400f, 700f, 2_400f)) {
            val content = rows + HubPageScroll.endPaddingDp
            val gap = HubPageScroll.gapAboveFadeAtEndDp(content, viewport)
            assertTrue("rows=$rows gap=$gap", gap >= Metrics.hubScrollEndGapDp - 0.001f)
        }
        assertTrue(HubPageScroll.endPaddingDp > HubPageScroll.fadeOpaqueDp)
    }

    @Test fun theChipIsGoneAndTheThumbOwnsTheTrailingEdge() {
        val pages = source("HubPages.kt")
        assertFalse(pages.contains("More below"))
        assertFalse(pages.contains("hubScrollHint"))
        assertTrue(pages.contains("HubScrollThumbBar"))
        assertTrue(pages.contains("Metrics.hubScrollEdgeGutterDp"))
        assertTrue(pages.contains("progressBarRangeInfo"))
        assertTrue(pages.contains("CustomAccessibilityAction"))
        // The old grip and the full-screen gestures are untouched.
        assertTrue(pages.contains("HubFullScreen.opensOnGripPull"))
        assertTrue(pages.contains("HubFullScreen.closesOnGripPull"))
        assertTrue(pages.contains("onExpandFullScreen"))
    }

    @Test fun lazyHubListsUseTheSameEndPaddingAsScrollingPages() {
        val library = source("LibraryPage.kt")
        assertTrue(library.contains("contentPadding = PaddingValues(bottom = HubPageScroll.endPaddingDp.dp)"))
        val comms = source("CommsPage.kt")
        assertTrue(comms.contains("contentPadding = PaddingValues(bottom = HubPageScroll.endPaddingDp.dp)"))
        // Every scrolling hub page keeps going through the one helper, so clearance cannot drift.
        for (file in listOf("TodayPage.kt", "NotesHubPage.kt", "StepsPage.kt", "RhythmPage.kt", "CommonplacePage.kt")) {
            assertTrue("$file should use hubPageScroll", source(file).contains("hubPageScroll"))
        }
    }

    @Test fun theThumbReadsTheScrollInDrawAndStaysInsideItsGutter() {
        val pages = source("HubPages.kt")
        assertTrue(pages.contains(".drawBehind {"))
        assertTrue(pages.contains("HubScrollThumb.heightDp"))
        assertTrue(pages.contains("HubScrollThumb.topDp"))
        assertTrue(pages.contains("alpha = fade"))
        assertTrue(Metrics.hubScrollEdgeGutterDp > Metrics.hubScrollThumbWidthDp)
        assertEquals(Metrics.hubScrollEdgeGutterDp, Metrics.hubScrollThumbWidthDp + 2 * Metrics.hubScrollThumbInsetDp, 0.001f)
        assertTrue(Metrics.hubScrollThumbMinDp > Metrics.hubScrollThumbWidthDp)
        assertTrue(Metrics.hubScrollThumbSettleMs >= 400L)
        assertTrue(Metrics.hubScrollThumbAlpha in 0.3f..0.8f)
    }
}
