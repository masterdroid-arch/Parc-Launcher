package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T157: the search sheet closes by a drag down, never by a drag up, and never by a drag down that scrolls the results.
 * This is the bug the owner saw on his Redmi: the host closed the sheet on any 48 dp drag down while the list was still using it.
 */
class SheetDismissTest {
    private val far = Metrics.searchSwipeDp + 10f
    private val still = 0f
    private val flick = Metrics.searchFlingVelocityDpPerSecond + 100f

    @Test fun swipeUpNeverDismisses() {
        for (dy in listOf(-1f, -48f, -300f)) {
            assertFalse(SheetDismissRule.dismisses(dy, 0f, still))
            assertFalse("fast up", SheetDismissRule.dismisses(dy, 0f, -5000f))
            assertFalse("a flick value with the wrong sign of distance", SheetDismissRule.dismisses(dy, 0f, flick))
        }
        val swipe = SearchSwipe()
        swipe.start(200f, 600f, anywhere = true)
        for (y in 590 downTo 100 step 10) assertFalse(swipe.move(200f, y.toFloat(), false, -3000f))
        // Up on the list with the list at its end: nothing is left over for the sheet.
        swipe.start(200f, 600f)
        for (i in 1..40) assertFalse(swipe.scroll(-12f, 0f))
    }

    @Test fun swipeDownPastTheThresholdDismisses() {
        assertFalse(SheetDismissRule.dismisses(Metrics.searchSwipeDp - 1f, 0f, still))
        assertTrue(SheetDismissRule.dismisses(Metrics.searchSwipeDp, 0f, still))
        assertTrue(SheetDismissRule.dismisses(far, 10f, still))
    }

    @Test fun sidewaysDragIsNotADismiss() {
        assertFalse(SheetDismissRule.dismisses(far, far, still))
        assertFalse(SheetDismissRule.dismisses(far, far + 20f, flick))
    }

    @Test fun aShortFlickDownDismissesAndAShortSlowDragDoesNot() {
        assertTrue(SheetDismissRule.dismisses(Metrics.searchFlickMinDp, 0f, flick))
        assertFalse(SheetDismissRule.dismisses(Metrics.searchFlickMinDp, 0f, Metrics.searchFlingVelocityDpPerSecond - 1f))
        assertFalse(SheetDismissRule.dismisses(Metrics.searchFlickMinDp - 1f, 0f, flick))
    }

    @Test fun swipeDownWhileTheListIsScrolledDoesNotDismiss() {
        val swipe = SearchSwipe()
        swipe.start(200f, 600f)
        // The list uses the first part of the drag (it consumes), so the gesture is the list's from then on.
        assertFalse(swipe.scroll(0f, 30f))
        assertFalse(swipe.scroll(80f, 0f))
        assertFalse(swipe.scroll(80f, 0f))
        assertFalse("a consumed drag never reaches the rule", swipe.move(200f, 800f, true, flick))
        // Even once the list sits at its end later in the same gesture.
        assertFalse(swipe.move(200f, 900f, false, flick))
    }

    @Test fun swipeDownAtTheEndOfTheListDismissesPastTheThreshold() {
        val swipe = SearchSwipe()
        swipe.start(200f, 600f)
        assertFalse(swipe.scroll(30f, 0f))
        assertTrue(swipe.scroll(30f, 0f))
        assertFalse("only once per gesture", swipe.scroll(30f, 0f))
    }

    @Test fun aDragOffTheListDismisses() {
        val swipe = SearchSwipe()
        swipe.start(200f, 700f)
        assertFalse(swipe.move(200f, 720f, false, still))
        assertTrue(swipe.move(200f, 700f + far, false, still))
        val flicked = SearchSwipe()
        flicked.start(200f, 700f)
        assertTrue(flicked.move(200f, 700f + Metrics.searchFlickMinDp, false, flick))
    }

    @Test fun theBandAboveTheSheetDismissesOnlyWhenTheSheetIsOpen() {
        val closed = SearchSwipe()
        closed.start(200f, Metrics.keepOutTopDp - 10f)
        assertFalse(closed.move(200f, Metrics.keepOutTopDp + far, false, still))
        val open = SearchSwipe()
        open.start(200f, Metrics.keepOutTopDp - 10f, anywhere = true)
        assertTrue(open.move(200f, Metrics.keepOutTopDp + far, false, still))
    }

    @Test fun aPushOnTheHubIsNeverADismiss() {
        val swipe = SearchSwipe()
        swipe.start(336f, 773f, fromHub = true, anywhere = true)
        assertFalse(swipe.move(336f, 900f, false, flick))
        assertFalse(swipe.scroll(100f, 0f))
    }

    @Test fun theSheetTokensAreTheCardNumbers() {
        assertEquals(32f, Metrics.searchSheetCornerDp, 0f)
        assertEquals(56f, Metrics.searchFieldHeightDp, 0f)
        assertEquals(Metrics.searchFieldHeightDp / 2f, Metrics.searchFieldCornerDp, 0f)
        assertEquals(20f, Metrics.searchFieldTextSp, 0f)
        assertEquals(56f, Metrics.searchRowHeightDp, 0f)
        assertEquals(0.35f, Metrics.searchModuleAlpha, 0f)
    }
}
