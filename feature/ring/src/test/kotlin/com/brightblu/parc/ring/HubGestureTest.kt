package com.brightblu.parc.ring

import org.junit.Assert.*
import org.junit.Test

class HubGestureTest {
    @Test fun aimCancelsLongPressAndSurvivesItsDeadline() {
        val g = HubGesture()
        g.down(true, 100)
        g.move(20f)
        assertTrue(g.aiming)
        assertFalse(g.longPress(450))
        assertFalse(g.longPress(2000))
        assertFalse(g.tap(80f, 2000))
    }

    @Test fun tapInsideDeadZoneBeforeDeadlineToggles() {
        val g = HubGesture()
        g.down(true, 0)
        g.move(19f)
        assertTrue(g.tap(19f, 349))
        assertFalse(g.tap(19f, 350))
        assertFalse(g.longPress(349))
        assertTrue(g.longPress(350))
        assertFalse(g.tap(0f, 351))
        g.move(90f)
        assertFalse(g.aiming)
    }

    @Test fun returningToDeadZoneAllowsQuickTapButStillDisarmsHold() {
        val g = HubGesture()
        g.down(true, 0)
        g.move(100f)
        g.move(0f)
        assertTrue(g.aiming)
        assertTrue(g.tap(0f, 200))
        assertFalse(g.longPress(350))
        assertEquals(-1, RingAim.band(4, 0f))
        assertTrue(RingAim.isBack(30f, 0f))
        g.cancel()
        assertFalse(g.aiming)
        assertFalse(g.tap(0f, 210))
    }

    @Test fun releaseDispatchesAppFolderBackAndDeadZoneAfterLongAim() {
        val g = HubGesture()
        for (slot in listOf(0, 7, RingAim.BACK)) {
            g.down(true, 0)
            g.move(100f)
            assertFalse(g.longPress(350))
            assertEquals(slot, g.release(100f, 2000, slot))
            assertEquals(-1, g.release(0f, 2100, slot))
        }
        g.down(true, 0)
        assertEquals(HubGesture.TAP, g.release(0f, 100, -1))
        g.down(true, 0)
        assertTrue(g.longPress(350))
        assertEquals(-1, g.release(0f, 400, -1))
    }

    @Test fun pressesOutsideHubCannotArmHoldOrTap() {
        val g = HubGesture()
        g.down(false, 0)
        g.move(90f)
        assertFalse(g.aiming)
        assertFalse(g.longPress(400))
        assertFalse(g.tap(0f, 10))
    }

    @Test fun realFingerPressTimingsToggleWithStationaryAndJitteredRelease() {
        val g = HubGesture()
        for (duration in listOf(60L, 120L, 150L, 250L, 349L)) {
            g.down(true, 1000)
            g.move(0f)
            assertEquals(HubGesture.TAP, g.release(0f, 1000 + duration, -1, 0f))
            g.down(true, 1000)
            // Required adb jitter is 6 x 4 pixels at 450 dpi.
            val jitterDp = kotlin.math.hypot(6f, 4f) / (450f / 160f)
            g.move(jitterDp)
            assertEquals(HubGesture.TAP, g.release(jitterDp, 1000 + duration, -1, jitterDp))
        }
    }

    @Test fun expandedHitAreaAllowsSmallTravelOutsideDeadZone() {
        assertTrue(com.brightblu.parc.model.Metrics.hubTouchDp / 2f >= 36f)
        val g = HubGesture()
        g.down(true, 0)
        g.move(30f)
        assertEquals(HubGesture.TAP, g.release(34f, 150, 7, 4f))
        g.down(true, 0)
        g.move(30f)
        assertEquals(7, g.release(34f, 150, 7, 12f))
        g.down(true, 0)
        g.move(60f)
        // Returning close to an off-centre DOWN still aims after a long path.
        assertEquals(7, g.release(30f, 150, 7, 64f))
    }

    @Test fun releaseBoundariesCancellationAndReturnFromAim() {
        val g = HubGesture()
        g.down(true, 0)
        g.move(60f)
        assertEquals(HubGesture.TAP, g.release(20f, 349, 7, 50f))
        assertEquals(-1, g.release(0f, 349, 7, 0f)) // one result per stream
        g.down(true, 0)
        assertEquals(-1, g.release(0f, 350, 7, 0f))
        g.down(true, 0)
        g.move(60f)
        assertEquals(-1, g.release(0f, 400, 7, 0f))
        g.down(true, 0)
        g.cancel()
        assertEquals(-1, g.release(0f, 120, 7, 0f))
        g.down(false, 0)
        assertEquals(-1, g.release(0f, 150, 7, 0f))
    }

    @Test fun instantAndShortPressesAcrossTheHitRadiusAreTaps() {
        for (duration in listOf(0L, 40L, 80L, 120L, 250L)) {
            for (reach in listOf(0f, 18f, 25f, 34f)) {
                for (jitter in listOf(0f, 1f, 6f)) {
                    val g = HubGesture()
                    g.down(true, 5000)
                    if (jitter > 0f) g.move(reach + jitter)
                    assertEquals("duration=$duration reach=$reach jitter=$jitter",
                        HubGesture.TAP, g.release(reach + jitter, 5000 + duration, -1, jitter))
                }
            }
        }
    }

    @Test fun releaseTimestampEarlierThanDownIsStillAnInstantTap() {
        val g = HubGesture()
        g.down(true, 1000)
        assertEquals(HubGesture.TAP, g.release(0f, 990, -1, 0f))
    }

    @Test fun edgeHoldUsesDwellThenRepeatAndRestartsOnZoneChange() {
        val h = EdgeHold()
        assertEquals(0, h.step(3, 100))
        assertEquals(0, h.step(3, 399))
        assertEquals(1, h.step(3, 400))
        assertEquals(0, h.step(3, 849))
        assertEquals(1, h.step(3, 850))
        assertEquals(0, h.step(-3, 900))
        assertEquals(-1, h.step(-3, 1200))
        assertEquals(0, h.step(4, 1300))
        assertEquals(1, h.step(4, 1600))
        assertEquals(0, h.step(0, 1700))
        assertEquals(0, h.step(4, 2000))
        h.reset()
        assertEquals(0, h.step(4, 2300))
    }

    @Test fun onlyOverflowingArcOuterBandBeyondEndAnglesRotates() {
        // A3 reach band 80..110, outer 18 dp starts at 92.
        assertEquals(0, EdgeHold.zone(2, 4, 91f, 285f, 180f, 270f, true))
        assertEquals(3, EdgeHold.zone(2, 4, 100f, 283f, 180f, 270f, true))
        assertEquals(-3, EdgeHold.zone(2, 4, 100f, 167f, 180f, 270f, true))
        assertEquals(0, EdgeHold.zone(2, 4, 100f, 282f, 180f, 270f, true))
        assertEquals(0, EdgeHold.zone(2, 4, 100f, 290f, 180f, 270f, false))
        assertEquals(0, EdgeHold.zone(2, 4, 120f, 290f, 180f, 270f, true))
        assertEquals(3, EdgeHold.zone(2, 4, 100f, 77f, 180f, 90f, true))
        assertEquals(-3, EdgeHold.zone(2, 4, 100f, 13f, 360f, 270f, true))
        assertEquals(3, EdgeHold.zone(2, 4, 100f, 257f, 360f, 270f, true))
    }
}
