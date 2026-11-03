package com.brightblu.parc.ring.module

import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.RingLayoutOptions
import org.junit.Assert.*
import org.junit.Test

class ModuleCardTest {
    /** width, height, left margin the slots must keep (4 dp on the 360 dp wide screen). */
    private val screens = listOf(Triple(384f, 853f, 8f), Triple(360f, 780f, 4f), Triple(411f, 891f, 8f))

    @Test fun cardRectangleOnReferenceScreen() {
        val layout = RingLayout(384f, 853f)
        assertEquals(773f, layout.originYDp, 0f)
        assertEquals(CardRect(12f, 416f, 372f, 841f), ModuleCard.rect(layout.originXDp, layout.originYDp, 384f))
    }

    @Test fun cardKeepsEdgeInsetsOnOtherScreens() {
        for ((w, h, _) in screens) {
            val l = RingLayout(w, h)
            val r = ModuleCard.rect(l.originXDp, l.originYDp, w)
            assertEquals(w - 12f, r.right, 0f); assertEquals(h - 12f, r.bottom, 0f)
            assertEquals(425f, r.height, 0f)
        }
    }

    @Test fun everyHomeSlotInsideCardWithMargin() {
        for ((w, h, leftMargin) in screens) for (icons in listOf(0, 4, 8, 20, 40)) for (arcs in listOf(3, 4)) {
            val l = RingLayout(w, h, options = RingLayoutOptions(iconsPerArc = icons, arcsShown = arcs))
            val r = ModuleCard.rect(l.originXDp, l.originYDp, w)
            for (i in 0 until l.count) {
                val x = l.slotXDp(i); val y = l.slotYDp(i)
                val where = "slot $i at ($x,$y) on ${w}x$h icons=$icons card=$r"
                assertTrue(where, x >= r.left + leftMargin && x <= r.right - 8f)
                assertTrue(where, y >= r.top + 8f && y <= r.bottom - 8f)
            }
        }
    }

    @Test fun emptyCardAreaIsNotTakenByTheModule() {
        val l = RingLayout(384f, 853f)
        assertTrue(l.hitTest(20f, 430f) < 0)
        assertFalse(ModuleCard.takesTouch(l, 20f, 430f))
        assertFalse(ModuleCard.takesTouch(l, 120f, 600f))
        assertTrue(ModuleCard.takesTouch(l, l.originXDp, l.originYDp))
        assertTrue(ModuleCard.takesTouch(l, l.slotXDp(0), l.slotYDp(0)))
    }

    @Test fun leanDependsOnlyOnProgress() {
        assertEquals(1f, ModuleLean.alpha(0f), 0f); assertEquals(0.7f, ModuleLean.alpha(1f), 1e-6f)
        assertEquals(0f, ModuleLean.shiftDp(0f), 0f); assertEquals(-6f, ModuleLean.shiftDp(1f), 0f)
        assertEquals(0.7f, ModuleLean.alpha(5f), 1e-6f)
    }
}
