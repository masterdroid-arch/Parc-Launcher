package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T167: every stats page can be scrolled until its last row is clear of the bottom fade. */
class HubPageScrollTest {
    private val slop = 8f

    /** Page viewport: the panel minus the header row, the dots strip (not Compact) and the grip. */
    private fun viewport(size: HubSize, standardPanel: Float): Float {
        val chrome = Metrics.touchTargetDp + (if (size == HubSize.Compact) 0f else Metrics.hubDotsHeightDp) + Metrics.hubGripHeightDp
        return HubPanelLayout.heightDp(size, standardPanel) - chrome
    }

    @Test fun endPaddingIsFadePlusGap() {
        assertEquals(28f, HubPageScroll.endPaddingDp, 0f)
        assertEquals(Metrics.hubFadeHeightDp + Metrics.hubScrollEndGapDp, HubPageScroll.endPaddingDp, 0f)
    }

    @Test fun lastRowIsReachableAboveTheFadeAtEveryHubSize() {
        for (size in HubSize.entries) for (panel in listOf(300f, 380f, 400f)) {
            val port = viewport(size, panel)
            // Fake pages: rows in dp, from tiny to many times the viewport.
            for (rows in listOf(20f, port - 40f, port, port + 1f, port + 120f, port * 3f, 2400f)) {
                val content = rows + HubPageScroll.endPaddingDp
                val gap = HubPageScroll.gapAboveFadeAtEndDp(content, port)
                assertTrue("$size panel=$panel rows=$rows gap=$gap", gap >= Metrics.hubScrollEndGapDp - 0.001f)
            }
        }
    }

    @Test fun withoutEndPaddingTheLastRowSitsUnderTheFade() {
        val port = viewport(HubSize.Standard, 400f)
        // The old layout: content equals the rows, so at the end the row touches the bottom edge.
        val gap = HubPageScroll.gapAboveFadeAtEndDp(port * 2f + HubPageScroll.endPaddingDp, port) // new
        val oldRows = port * 2f
        val oldGap = (port - Metrics.hubFadeHeightDp) - (oldRows - HubPageScroll.maxScrollDp(oldRows, port))
        assertTrue(oldGap < 0f)
        assertTrue(gap >= Metrics.hubScrollEndGapDp)
    }

    @Test fun maxScrollIsZeroWhenContentFits() {
        assertEquals(0f, HubPageScroll.maxScrollDp(100f, 200f), 0f)
        assertEquals(0f, HubPageScroll.maxScrollDp(200f, 200f), 0f)
        assertEquals(50f, HubPageScroll.maxScrollDp(250f, 200f), 0f)
    }

    @Test fun fadeShowsOnlyWhileMoreIsBelow() {
        assertFalse("nothing to scroll", HubPageScroll.fadeVisible(0, 0))
        assertTrue("at the top of a long page", HubPageScroll.fadeVisible(0, 500))
        assertTrue("midway", HubPageScroll.fadeVisible(200, 500))
        assertFalse("at the end", HubPageScroll.fadeVisible(500, 500))
    }

    @Test fun smallMovesAreUndecided() {
        assertEquals(HubDragLock.Undecided, HubPageScroll.lock(3f, 4f, slop))
        assertEquals(HubDragLock.Undecided, HubPageScroll.lock(0f, 0f, slop))
    }

    @Test fun steepDragScrolls() {
        assertEquals(HubDragLock.Vertical, HubPageScroll.lock(4f, 40f, slop))
        assertEquals(HubDragLock.Vertical, HubPageScroll.lock(-6f, -30f, slop))
        assertEquals(HubDragLock.Vertical, HubPageScroll.lock(0f, 9f, slop))
    }

    @Test fun flatDragSwapsPages() {
        assertEquals(HubDragLock.Horizontal, HubPageScroll.lock(40f, 4f, slop))
        assertEquals(HubDragLock.Horizontal, HubPageScroll.lock(-30f, 6f, slop))
        assertEquals(HubDragLock.Horizontal, HubPageScroll.lock(9f, 0f, slop))
    }

    @Test fun diagonalDragScrollsUntilClearlyFlat() {
        assertEquals(HubDragLock.Vertical, HubPageScroll.lock(20f, 20f, slop))
        assertEquals(HubDragLock.Vertical, HubPageScroll.lock(24f, 20f, slop))
        assertEquals(HubDragLock.Horizontal, HubPageScroll.lock(26f, 20f, slop))
        assertEquals(HubDragLock.Vertical, HubPageScroll.lock(20f, 24f, slop))
    }
}
