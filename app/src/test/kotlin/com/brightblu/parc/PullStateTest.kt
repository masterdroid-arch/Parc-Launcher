package com.brightblu.parc

import org.junit.Assert.*
import org.junit.Test

class PullStateTest {
    private val pull = PullState()
    private fun start(hub: Boolean = false, top: Boolean = true) =
        pull.start(100f, 100f, 0, fromHub = hub, atScrollTop = top)

    @Test fun emptySpaceProgressIsCappedAndDimmingReachesPointSix() {
        start()
        assertFalse(pull.move(100f, 155f))
        assertEquals(0.5f, pull.progress, 0.001f)
        assertEquals(0.8f, pull.hubAlpha, 0.001f)
        assertTrue(pull.move(100f, 210f))
        assertEquals(1f, pull.progress, 0f)
        assertEquals(0.6f, pull.hubAlpha, 0.001f)
        assertFalse(pull.move(100f, 400f))
        assertEquals(1f, pull.progress, 0f)
        assertTrue(pull.release(100f, 400f, 2_000))
        assertEquals(1f, pull.progress, 0f)
        assertFalse(pull.release(100f, 400f, 2_100))
    }

    @Test fun hubNeeds220DpFingerTravelAndCannotFling() {
        start(hub = true)
        assertFalse(pull.move(100f, 210f))
        assertEquals(0.5f, pull.progress, 0f)
        assertFalse(pull.release(100f, 319f, 10))
        start(hub = true)
        assertTrue(pull.move(100f, 320f))
        assertTrue(pull.release(100f, 320f, 2_000))
    }

    @Test fun panelStartBelowTopNeverAcquiresSearchEvenAfterReachingTop() {
        start(hub = true, top = false)
        assertFalse(pull.move(100f, 700f))
        assertEquals(0f, pull.progress, 0f)
        assertFalse(pull.release(100f, 700f, 1_000))
        start(hub = true)
        pull.panelScrolled(1f)
        assertFalse(pull.move(100f, 700f))
        assertFalse(pull.release(100f, 700f, 1_000))
    }

    @Test fun actualScrollingInEitherDirectionCancelsButZeroConsumptionDoesNot() {
        for (consumed in listOf(-1f, 1f)) {
            start(hub = true)
            pull.move(100f, 200f)
            pull.panelScrolled(consumed)
            assertEquals(0f, pull.progress, 0f)
            assertFalse(pull.release(100f, 400f, 1_000))
        }
        start(hub = true)
        pull.panelScrolled(0f)
        assertTrue(pull.release(100f, 320f, 1_000))
    }

    @Test fun retreatBelowThresholdCancelsAndThresholdTickOccursOnlyOnce() {
        start()
        assertTrue(pull.move(100f, 210f))
        assertFalse(pull.move(100f, 155f))
        assertFalse(pull.move(100f, 210f))
        assertFalse(pull.release(100f, 209f, 1_000))
        assertEquals(0f, pull.progress, 0f)
        start()
        assertTrue(pull.move(100f, 210f))
    }

    @Test fun emptySpaceFlingRequires60DpAndOver1200DpPerSecond() {
        start()
        assertFalse(pull.release(100f, 159f, 10))
        start()
        assertFalse(pull.release(100f, 160f, 50))
        start()
        assertTrue(pull.release(100f, 160f, 49))
    }

    @Test fun verticalDominanceUsesFingerTravelIncludingInHub() {
        start()
        pull.move(174f, 210f)
        assertEquals(0f, pull.progress, 0f)
        assertFalse(pull.release(174f, 210f, 1_000))
        start(hub = true)
        assertTrue(pull.move(240f, 320f))
        assertTrue(pull.release(240f, 320f, 1_000))
    }

    @Test fun blockedControlKeepoutMultitouchAndInvalidPathsNeverCommit() {
        pull.start(100f, 100f, 0, blocked = true)
        assertFalse(pull.move(100f, 400f))
        assertFalse(pull.release(100f, 400f, 100))
        pull.start(100f, 100f, 0, onControl = true)
        assertFalse(pull.release(100f, 400f, 100))
        pull.start(100f, 56f, 0)
        assertFalse(pull.move(100f, 400f))
        assertFalse(pull.release(100f, 400f, 100))
        start()
        pull.move(100f, 200f)
        pull.cancel()
        assertEquals(0f, pull.progress, 0f)
        assertFalse(pull.release(100f, 400f, 100))
        start()
        assertFalse(pull.release(Float.NaN, Float.POSITIVE_INFINITY, 100))
    }
}
