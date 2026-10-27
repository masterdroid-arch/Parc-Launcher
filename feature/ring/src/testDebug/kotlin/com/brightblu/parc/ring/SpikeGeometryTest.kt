package com.brightblu.parc.ring

import com.brightblu.parc.model.Metrics
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SpikeGeometryTest {
    private val geometry = SpikeGeometry()

    @Test fun referenceGeometryAndPitch() {
        assertEquals(336f, geometry.originX, 0f)
        assertEquals(797f, geometry.originY, 0f)
        assertEquals(listOf(4, 5, 7, 7), geometry.arcs.map { it.slotCapacity })
        assertEquals(listOf(120f, 190f, 255f, 315f), geometry.arcs.map { it.radiusDp })
        assertEquals(23, geometry.count)
        for (arc in geometry.arcs) {
            val pitch = arc.radiusDp * Math.toRadians(
                ((arc.endAngle - arc.startAngle) / (arc.slotCapacity - 1)).toDouble())
            assertTrue(pitch >= Metrics.minSlotPitchDp)
        }
        for (x in geometry.x) assertTrue(x >= 44f)
    }

    @Test fun everySlotRoundTripsThroughPolarHitTesting() {
        for (i in 0 until geometry.count) {
            assertEquals(i, geometry.hit(geometry.x[i], geometry.y[i]))
            assertEquals(i, geometry.hit(geometry.x[i] + 2f, geometry.y[i] + 2f))
        }
    }

    @Test fun emptySpaceAndTargetBoundaries() {
        assertEquals(-1, geometry.hit(0f, 0f))
        assertEquals(-1, geometry.hit(geometry.originX, geometry.originY))
        assertTrue(geometry.hubHit(geometry.originX + Metrics.hubTouchDp / 2f - 1f, geometry.originY))
        assertFalse(geometry.hubHit(geometry.originX + Metrics.hubTouchDp / 2f + 1f, geometry.originY))
        val index = 1
        val dx = geometry.x[index] - geometry.originX
        val dy = geometry.y[index] - geometry.originY
        val radius = hypot(dx, dy)
        assertEquals(-1, geometry.hit(geometry.originX + dx / radius * (radius + 29f),
            geometry.originY + dy / radius * (radius + 29f)))
    }
}
