package com.brightblu.parc

import com.brightblu.parc.HoldToDrag.Decision
import com.brightblu.parc.model.Metrics
import org.junit.Assert.assertEquals
import org.junit.Test

/** 1.2.1: a drag starts only after a press and hold on the handle; every other touch scrolls. */
class HoldToDragTest {
    private val slop = 8f

    @Test fun aQuickTouchNeverStartsADrag() {
        assertEquals(Decision.Wait, HoldToDrag.decide(100, 0f, slop, onHandle = true))
        // Lifted before the hold time: a tap, so it scrolls or clicks as normal.
        assertEquals(Decision.Scroll, HoldToDrag.decide(200, 0f, slop, onHandle = true, released = true))
        assertEquals(Decision.Scroll, HoldToDrag.decide(Metrics.longPressMs - 1, 0f, slop, onHandle = true, released = true))
    }

    @Test fun movingAtOnceIsAScrollEvenOnTheHandle() {
        assertEquals(Decision.Scroll, HoldToDrag.decide(20, 30f, slop, onHandle = true))
        assertEquals(Decision.Scroll, HoldToDrag.decide(0, slop + 0.1f, slop, onHandle = true))
        // A slow swipe that crosses the slop before the hold time is over still scrolls.
        assertEquals(Decision.Scroll, HoldToDrag.decide(Metrics.longPressMs - 10, 40f, slop, onHandle = true))
    }

    @Test fun holdingStillOnTheHandleStartsTheDrag() {
        assertEquals(Decision.Wait, HoldToDrag.decide(Metrics.longPressMs - 1, 0f, slop, onHandle = true))
        assertEquals(Decision.Start, HoldToDrag.decide(Metrics.longPressMs, 0f, slop, onHandle = true))
        assertEquals(Decision.Start, HoldToDrag.decide(Metrics.longPressMs + 200, slop, slop, onHandle = true)) // a little tremor is not movement
    }

    @Test fun theHoldTimeIsThreeHundredAndFiftyMilliseconds() {
        assertEquals(350L, Metrics.longPressMs)
    }

    @Test fun holdingOnTheRowButNotTheHandleNeverStartsADrag() {
        assertEquals(Decision.Scroll, HoldToDrag.decide(Metrics.longPressMs, 0f, slop, onHandle = false))
        assertEquals(Decision.Scroll, HoldToDrag.decide(2000, 0f, slop, onHandle = false))
        assertEquals(Decision.Scroll, HoldToDrag.decide(0, 0f, slop, onHandle = false))
    }
}
