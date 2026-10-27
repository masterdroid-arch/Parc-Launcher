package com.brightblu.parc.ring

import com.brightblu.parc.model.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * T65: keep-out sources. A phone without gesture insets (3-button navigation) must keep the
 * exact reference geometry; a phone that reports gesture or cutout insets wider than the
 * Metrics tokens must move the field instead of letting slots sit in the system strip.
 */
class RingInsetsTest {
    @Test fun noSourcesKeepsReferenceGeometry() {
        assertEquals(RingInsets(), widestInsets())
        val combined = widestInsets(RingInsets(), RingInsets(), RingInsets(), RingInsets())
        assertEquals(RingLayout(384f, 853f).arcs, RingLayout(384f, 853f, combined).arcs)
    }

    @Test fun widestEdgeWinsPerSideIndependentOfOrder() {
        val a = RingInsets(left = 1f, top = 2f, right = 3f, bottom = 4f)
        val b = RingInsets(left = 5f, top = 0f, right = 0f, bottom = 8f)
        for (sources in listOf(arrayOf(a, b), arrayOf(b, a))) {
            assertEquals(RingInsets(left = 5f, top = 2f, right = 3f, bottom = 8f),
                widestInsets(*sources))
        }
    }

    @Test fun threeButtonNavigationFallsBackToTheKeepOutConstants() {
        // Status bar 24, nav bar 48, punch-hole cutout 47, no gesture strips at all.
        val combined = widestInsets(
            RingInsets(top = 24f, bottom = 48f),
            RingInsets(top = 47f),
            RingInsets(),
            RingInsets(),
        )
        assertEquals(RingInsets(top = 47f, bottom = 48f), combined)
        val reference = RingLayout(384f, 853f)
        val layout = RingLayout(384f, 853f, combined)
        assertEquals(reference.arcs, layout.arcs)
        assertEquals(listOf(4, 5, 7, 7), layout.arcs.map { it.slotCapacity })
        assertEquals(336f, layout.originXDp, 0f)
        assertEquals(773f, layout.originYDp, 0f)
    }

    @Test fun gestureNavigationStaysOutOfTheBackStrips() {
        // Pixel-style gesture navigation: 84 px side strips and a 90 px bottom strip at 2.8125 px/dp.
        val side = 84f / 2.8125f
        val bottom = 90f / 2.8125f
        val combined = widestInsets(
            RingInsets(top = 24f, bottom = 24f),
            RingInsets(top = 132f / 2.8125f),
            RingInsets(left = side, right = side),
            RingInsets(bottom = bottom),
        )
        val layout = RingLayout(384f, 853f, combined)
        val leftEdge = max(Metrics.keepOutLeftDp, side) + Metrics.touchTargetDp / 2f
        val rightEdge = 384f - max(Metrics.keepOutRightDp, side)
        for (i in 0 until layout.count) {
            assertTrue(layout.slotXDp(i) >= leftEdge - 0.001f)
            assertTrue(layout.slotXDp(i) <= rightEdge + 0.001f)
            assertTrue(layout.slotYDp(i) <= 853f - max(Metrics.keepOutBottomDp, bottom) + 0.001f)
        }
        assertTrue(layout.arcs.all { it.slotCapacity >= 4 })
        for (arc in layout.arcs) {
            for (slot in 1 until arc.slotCapacity) {
                val pitch = arc.radiusDp *
                    Math.toRadians(abs(arc.slotAngle(slot) - arc.slotAngle(slot - 1)).toDouble())
                assertTrue("${arc.id} slot $slot pitch=$pitch", pitch >= Metrics.minSlotPitchDp - 0.001)
            }
        }
    }

    @Test fun wideMandatoryBottomRaisesTheHub() {
        val layout = RingLayout(384f, 853f, widestInsets(RingInsets(bottom = 90f)))
        assertEquals(853f - 90f - 24f, layout.originYDp, 0f)
        for (i in 0 until layout.count) {
            assertTrue(layout.slotYDp(i) <= 853f - 90f + 0.001f)
        }
        assertEquals(336f, layout.originXDp, 0f)
    }

    @Test fun mirroredLayoutReadsTheNearGestureEdge() {
        val leftStrip = widestInsets(RingInsets(left = 60f))
        val mirrored = RingLayout(384f, 853f, leftStrip, RingLayoutOptions(mirrored = true))
        assertEquals(60f, mirrored.originXDp, 0f)
        val rightStrip = widestInsets(RingInsets(right = 60f))
        assertEquals(384f - 60f, RingLayout(384f, 853f, rightStrip).originXDp, 0f)
    }
}
