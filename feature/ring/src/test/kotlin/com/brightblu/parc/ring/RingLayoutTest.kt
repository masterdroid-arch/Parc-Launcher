package com.brightblu.parc.ring

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class RingLayoutTest {
    @Test fun referenceCapacitiesAndClipping() {
        val layout = RingLayout(384f, 853f)
        assertEquals(336f, layout.originXDp, 0f)
        assertEquals(773f, layout.originYDp, 0f)
        assertEquals(listOf(4, 5, 7, 7), layout.arcs.map { it.slotCapacity })
        assertEquals(listOf(120f, 190f, 255f, 315f), layout.arcs.map { it.radiusDp })
        assertEquals(23, layout.count)
        assertEquals(202.03f, layout.arcs.last().startAngle, 0.1f)
        assertEquals(270f, layout.arcs.last().endAngle, 0.001f)
        assertEquals(44f, layout.slotXDp(16), 0.001f)
    }

    @Test fun screenSizesInsetsMirroringAndSpokes() {
        for ((w, h) in listOf(384f to 853f, 360f to 800f, 411f to 914f)) {
            for (insets in listOf(RingInsets(), RingInsets(24f, 80f, 32f, 72f),
                RingInsets(40f, 600f, 40f, 80f))) {
                for (mirrored in listOf(false, true)) for (aligned in listOf(false, true)) {
                    val layout = RingLayout(w, h, insets, RingLayoutOptions(mirrored, aligned))
                    val left = max(if (mirrored) Metrics.keepOutRightDp else Metrics.keepOutLeftDp,
                        insets.left) + if (mirrored) 0f else Metrics.touchTargetDp / 2f
                    val right = w - max(if (mirrored) Metrics.keepOutLeftDp else Metrics.keepOutRightDp,
                        insets.right) - if (mirrored) Metrics.touchTargetDp / 2f else 0f
                    for (i in 0 until layout.count) {
                        assertTrue(layout.slotXDp(i) >= left - 0.001f)
                        assertTrue(layout.slotXDp(i) <= right + 0.001f)
                        assertTrue(layout.slotYDp(i) >= max(Metrics.keepOutTopDp, insets.top) - 0.001f)
                        assertTrue(layout.slotYDp(i) <= h - max(Metrics.keepOutBottomDp, insets.bottom) + 0.001f)
                        assertEquals(i, layout.hitTest(layout.slotXDp(i), layout.slotYDp(i)))
                        assertEquals(i, layout.nearestSlot(layout.slotXDp(i), layout.slotYDp(i)))
                    }
                    val shared = RingLayout(w, h, options = RingLayoutOptions(mirrored, true))
                        .arcs.first().slotAngles
                    for (arc in layout.arcs) {
                        for (i in 1 until arc.slotCapacity) {
                            val pitch = arc.radiusDp * Math.toRadians(abs(arc.slotAngle(i) - arc.slotAngle(i - 1)).toDouble())
                            assertTrue("$w/$h $arc pitch=$pitch", pitch >= Metrics.minSlotPitchDp - 0.001)
                        }
                        if (aligned && arc.slotCapacity > 4) for (angle in arc.slotAngles) assertTrue(shared.any { abs(it - angle) < 0.001f })
                    }
                }
            }
        }
    }

    @Test fun mirrorIsExactAndNarrowScreenLosesSlots() {
        val normal = RingLayout(384f, 853f)
        val mirror = RingLayout(384f, 853f, options = RingLayoutOptions(mirrored = true))
        for (i in 0 until normal.count) {
            assertEquals(384f - normal.slotXDp(i), mirror.slotXDp(i), 0.001f)
            assertEquals(normal.slotYDp(i), mirror.slotYDp(i), 0.001f)
        }
        assertTrue(RingLayout(360f, 800f).count < normal.count)
        assertEquals(listOf(4, 4, 4, 4), RingLayout(384f, 853f,
            options = RingLayoutOptions(alignAcrossArcs = true)).arcs.map { it.slotCapacity })
    }

    @Test fun targetBoundariesNearestEmptySpaceAndInvalidQueries() {
        val layout = RingLayout(384f, 853f)
        val x = layout.slotXDp(0)
        val y = layout.slotYDp(0)
        assertEquals(0, layout.hitTest(x - 27.9f, y))
        assertEquals(-1, layout.hitTest(x - 28.1f, y))
        assertEquals(-1, layout.hitTest(0f, 0f))
        assertEquals(-1, layout.hitTest(layout.originXDp, layout.originYDp))
        // At radius 160, A2 (radius 190) is nearer than A1 (radius 120).
        assertEquals(4, layout.nearestSlot(x - 40f, y))
        assertEquals(-1, layout.hitTest(Float.NaN, 0f))
        assertEquals(-1, layout.nearestSlot(0f, Float.POSITIVE_INFINITY))
        val empty = RingLayout(384f, 853f, RingInsets(left = 200f, top = 772f))
        assertEquals(0, empty.count)
        assertEquals(-1, empty.nearestSlot(0f, 0f))
        assertEquals(-1, empty.hitTest(0f, 0f))
    }

    @Test fun geometryContractRoundTripsAndLegacyDefaults() {
        val arcs = RingLayout(384f, 853f).arcs
        val snapshot = ParcLayout(arcs = arcs.map { ArcAssignment(it, emptyMap()) })
        assertEquals(snapshot, LayoutJson.decode(LayoutJson.encode(snapshot)))
        val legacy = LayoutJson.decode("""{"arcs":[{"arc":{"id":"A1","index":0,"radiusDp":120.0,"startAngle":180.0,"endAngle":270.0,"slotCapacity":4,"layer":"Home"},"slots":{}}]}""").arcs.first().arc
        assertEquals(0f, legacy.originXDp, 0f)
        assertEquals(210f, legacy.slotAngle(1), 0f)
        assertFalse(AppEntry(AppId("pkg/App@0"), "App", "pkg", false, 0L).suspended)
    }

    @Test fun raisedFieldKeepsPitchAndAtLeastFourSlotsOnTargetScreens() {
        for ((w, h) in listOf(384f to 853f, 360f to 800f, 411f to 914f)) {
            for (aligned in listOf(false, true)) {
                val layout = RingLayout(w, h, options = RingLayoutOptions(alignAcrossArcs = aligned))
                assertEquals(h - Metrics.keepOutBottomDp - 24f, layout.originYDp, 0f)
                assertTrue(layout.arcs.all { it.slotCapacity >= 4 })
                for (i in 0 until layout.count) {
                    assertTrue(layout.slotYDp(i) + Metrics.iconDp / 2 <= h - 24f)
                }
            }
        }
    }

    @Test fun malformedDimensionsRejected() {
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { RingLayout(width, 853f) }
        }
        assertThrows(IllegalArgumentException::class.java) { RingInsets(left = -1f) }
        assertThrows(IllegalArgumentException::class.java) { RingLayout(60f, 100f) }
    }
}
