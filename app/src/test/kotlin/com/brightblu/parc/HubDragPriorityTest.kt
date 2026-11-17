package com.brightblu.parc

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** M13 review: a reorder drag takes priority over the Home gestures. (1.2.1: the settings exit swipe test was deleted with that gesture.) */
class HubDragPriorityTest {
    @Before @After fun reset() = HubDragPriority.resetForTest()

    @Test fun ownsWhileRunningAndAfterItEndedForAGestureThatBeganBefore() {
        val down = HubDragPriority.lifts
        assertFalse(HubDragPriority.owns(down))
        HubDragPriority.begin()
        assertTrue(HubDragPriority.owns(down))
        HubDragPriority.end()
        assertFalse(HubDragPriority.active)
        assertTrue(HubDragPriority.owns(down)) // the release of that same finger still counts
        assertFalse(HubDragPriority.owns(HubDragPriority.lifts)) // a new gesture is free again
    }

    @Test fun endNeverGoesBelowZero() {
        HubDragPriority.end(); HubDragPriority.end()
        HubDragPriority.begin()
        assertTrue(HubDragPriority.active)
    }
}
