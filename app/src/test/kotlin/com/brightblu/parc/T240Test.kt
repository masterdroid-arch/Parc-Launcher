package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T240: the page-name strip owns its sideways drag, and a tapped name is centred. */
class T240Test {
    @Test fun aTappedPillEndsInTheMiddleOfTheStrip() {
        // A 60 px pill in a 300 px strip: its centre goes to the strip's centre.
        assertEquals(240f, HubTabs.centreTarget(360f, 60f, 300f, 500f), 0.001f)
        assertEquals(100f, HubTabs.centreTarget(220f, 60f, 300f, 500f), 0.001f)
    }

    @Test fun centringNeverScrollsPastTheContentOrToANegativeOffset() {
        assertEquals(0f, HubTabs.centreTarget(10f, 60f, 300f, 500f), 0f)    // near the start
        assertEquals(500f, HubTabs.centreTarget(760f, 60f, 300f, 500f), 0f) // near the end
        assertEquals(0f, HubTabs.centreTarget(360f, 60f, 300f, 0f), 0f)     // names already fit
        assertEquals(0f, HubTabs.centreTarget(360f, 60f, 300f, -20f), 0f)
    }

    @Test fun centringIgnoresUnmeasuredOrBrokenSizes() {
        assertEquals(0f, HubTabs.centreTarget(Float.NaN, 60f, 300f, 500f), 0f)
        assertEquals(0f, HubTabs.centreTarget(360f, Float.NaN, 300f, 500f), 0f)
        assertEquals(0f, HubTabs.centreTarget(360f, 60f, Float.NaN, 500f), 0f)
        assertEquals(0f, HubTabs.centreTarget(360f, 60f, 300f, Float.NaN), 0f)
        assertEquals(0f, HubTabs.centreTarget(360f, 0f, 300f, 500f), 0f)
        assertEquals(0f, HubTabs.centreTarget(360f, 60f, 0f, 500f), 0f)
    }

    @Test fun aTouchThatStartsOnTheStripBelongsToTheStrip() {
        assertTrue(HubTabGesture.startsOnStrip(0f, 48f))
        assertTrue(HubTabGesture.startsOnStrip(24f, 48f))
        assertTrue(HubTabGesture.startsOnStrip(48f, 48f))
        assertFalse(HubTabGesture.startsOnStrip(48.1f, 48f))
        assertFalse(HubTabGesture.startsOnStrip(-1f, 48f))
        assertFalse(HubTabGesture.startsOnStrip(Float.NaN, 48f))
        assertFalse(HubTabGesture.startsOnStrip(24f, Float.NaN))
        assertFalse(HubTabGesture.startsOnStrip(24f, 0f))
    }

    @Test fun aCentreRequestIsNotWaitedForForever() {
        // T269: a tapped page that never becomes selected (hidden or Focus-filtered) is given up on after a
        // short while, so the strip goes back to following swipes.
        assertTrue(HubTabs.CentreGiveUpMs in 500L..5_000L)
    }
}
