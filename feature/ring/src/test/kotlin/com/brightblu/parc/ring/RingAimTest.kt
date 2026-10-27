package com.brightblu.parc.ring

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.SlotPosition
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class RingAimTest {
    private val layout = RingLayout(384f, 853f)

    private fun reach(band: Int): Float {
        val width = (Metrics.aimBandEndDp - Metrics.aimBandStartDp) / 4f
        return Metrics.aimBandStartDp + band * width + width / 2f
    }

    @Test fun everyHomeSlotIsReachable() {
        for (flat in 0 until layout.count) {
            val arc = layout.arcs[layout.arcIndex(flat)]
            val index = layout.slotIndex(flat)
            val angle = RingAim.normalizeDegrees(arc.slotAngle(index))
            assertEquals(SlotPosition(arc.id, index), layout.aim(angle, reach(layout.arcIndex(flat))))
        }
    }

    @Test fun everyNeighbourPairChoosesEuclideanNearestProjection() {
        for ((band, arc) in layout.arcs.withIndex()) for (i in 0 until arc.slotCapacity - 1) {
            val a = arc.slotAngle(i)
            val b = arc.slotAngle(i + 1)
            for (fraction in listOf(0.2f, 0.45f, 0.55f, 0.8f)) {
                val angle = a + (b - a) * fraction
                val expected = if (fraction < 0.5f) i else i + 1
                assertEquals(SlotPosition(arc.id, expected), layout.aim(angle, reach(band)))
                assertTrue(RingAim.distance(angle, arc.slotAngle(expected), arc.radiusDp) <
                    RingAim.distance(angle, arc.slotAngle(if (expected == i) i + 1 else i), arc.radiusDp))
            }
        }
    }

    @Test fun mirroredLayoutReachesEverySlot() {
        val mirror = RingLayout(384f, 853f, options = RingLayoutOptions(mirrored = true))
        for (flat in 0 until mirror.count) {
            val arc = mirror.arcs[mirror.arcIndex(flat)]
            val index = mirror.slotIndex(flat)
            val angle = RingAim.normalizeDegrees(arc.slotAngle(index))
            assertEquals(SlotPosition(arc.id, index), mirror.aim(angle, reach(mirror.arcIndex(flat))))
        }
    }

    @Test fun deadZoneAndBandEdges() {
        assertNull(layout.aim(200f, 0f))
        assertNull(layout.aim(200f, 19.999f))
        assertEquals("A1", layout.aim(200f, 20f)?.arcId)
        assertEquals("A1", layout.aim(200f, 49.999f)?.arcId)
        assertEquals("A2", layout.aim(200f, 50f)?.arcId)
        assertEquals("A3", layout.aim(200f, 80f)?.arcId)
        assertEquals("A4", layout.aim(200f, 110f)?.arcId)
        assertEquals("A4", layout.aim(200f, 140f)?.arcId)
        assertEquals("A4", layout.aim(200f, 500f)?.arcId)
        assertNull(layout.aim(Float.NaN, 80f))
        assertNull(layout.aim(200f, Float.POSITIVE_INFINITY))
    }

    @Test fun hysteresisRetainsPreviousSlotNearMidpoint() {
        val arc = layout.arcs[0] // A1, four slots, uniform pitch
        assertTrue(arc.slotCapacity >= 2)
        val a0 = RingAim.normalizeDegrees(arc.slotAngle(0))
        val a1 = RingAim.normalizeDegrees(arc.slotAngle(1))
        val pitch = RingAim.circularDistance(a0, a1)
        val mid = a0 + pitch / 2f
        val pos0 = SlotPosition("A1", 0)
        val pos1 = SlotPosition("A1", 1)
        // Just past the midpoint the nearest slot is 1 without hysteresis, but a previous
        // selection on slot 0 keeps it within the 15 % band.
        assertEquals(pos1, layout.aim(mid + pitch * 0.05f, reach(0)))
        assertEquals(pos0, layout.aim(mid + pitch * 0.05f, reach(0), pos0))
        // Well past the hysteresis band, even a previous slot 0 switches to slot 1.
        assertEquals(pos1, layout.aim(mid + pitch * 0.4f, reach(0), pos0))
    }

    @Test fun hysteresisDoesNotCrossBands() {
        // A previous selection on A1 must not affect aiming on A2 even at the same angle.
        val arc = layout.arcs[1]
        val angle = RingAim.normalizeDegrees(arc.slotAngle(0))
        val result = layout.aim(angle, reach(1), SlotPosition("A1", 0))
        assertEquals("A2", result?.arcId)
    }

    @Test fun bandMathAndAngleHelpers() {
        assertEquals(-1, RingAim.band(4, 0f))
        assertEquals(0, RingAim.band(4, 20f))
        assertEquals(3, RingAim.band(4, 140f))
        assertEquals(3, RingAim.band(4, 10_000f))
        assertEquals(-1, RingAim.band(0, 80f))
        // Recents uses two arcs: a wider band each.
        assertEquals(0, RingAim.band(2, 60f))
        assertEquals(1, RingAim.band(2, 90f))
        assertEquals(1, RingAim.band(2, 500f))
        assertEquals(0f, RingAim.normalizeDegrees(360f), 0f)
        assertEquals(270f, RingAim.normalizeDegrees(-90f), 0f)
        assertEquals(2f, RingAim.circularDistance(359f, 1f), 0.001f)
        assertEquals(180f, RingAim.circularDistance(0f, 180f), 0.001f)
    }
}
