package com.brightblu.parc.ring.module

import com.brightblu.parc.model.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T391: a frost for a floating panel is cached apart from the same frost for a hub card, and carries its tint floor. */
class FloatingFrostTest {
    private fun request(min: Int) = FrostRequest(360, 800, 4, 20, 100, 80, 10, true, 0xB38FA6C9.toInt(), 0xFF05070C.toInt(),
        0xFF8FA6C9.toInt(), 7, 2, 0xFFFFFFFF.toInt(), minTintAlpha = min)

    @Test fun theFloorIsPartOfTheCacheKey() {
        assertEquals(request(0).key, request(0).key)
        assertNotEquals(request(0).key, request(Metrics.floatingPanelMinTintAlpha).key)
    }

    @Test fun theFloorIsAtLeastNinetyTwoPercent() {
        assertTrue(Metrics.floatingPanelMinTintAlpha >= (0.92f * 255f).toInt())
    }
}
