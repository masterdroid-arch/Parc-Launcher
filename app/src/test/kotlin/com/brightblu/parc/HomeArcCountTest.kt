package com.brightblu.parc

import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.LayoutCounts
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.RingLayoutOptions
import com.brightblu.parc.ring.module.ModuleCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** T174: Home shows the arcs the setting says, whatever the stats hub size; A4 sits inside the module card. */
class HomeArcCountTest {
    private val screens = listOf(Triple(384f, 853f, 8f), Triple(360f, 780f, 4f), Triple(411f, 891f, 8f))

    @Test fun fourArcsShowFourWhateverTheHubSize() {
        // The count handed to the geometry is the stored count; no hub size, visibility or layer takes part.
        val counts = LayoutCounts(arcs = 4).normalized()
        for ((w, h, _) in screens) {
            val layout = RingLayout(w, h, options = RingLayoutOptions(arcsShown = counts.arcs, iconsPerArc = counts.icons))
            assertEquals(4, layout.arcs.size)
            assertEquals("A4", layout.arcs.last().id)
            assertTrue(layout.arcs.last().slotCapacity > 0)
        }
        assertEquals(3, RingLayout(384f, 853f, options = RingLayoutOptions(arcsShown = 3)).arcs.size)
    }

    @Test fun a4SlotCentresAreInsideTheCardWithTwelveDpToTheEdge() {
        // 12 dp on the reference screen (the card's screen); 8 dp (4 dp on 360 wide) on the other sizes, as ModuleCardTest.
        for ((w, h, margin) in screens) {
            val edge = if (w == 384f) 12f else margin
            val l = RingLayout(w, h)
            val card = ModuleCard.rect(l.originXDp, l.originYDp, w)
            var seen = 0
            for (i in 0 until l.count) {
                if (l.arcIndex(i) != 3) continue
                seen++
                val x = l.slotXDp(i); val y = l.slotYDp(i)
                assertTrue("A4 slot $i ($x,$y) on ${w}x$h card=$card",
                    x >= card.left + edge && x <= card.right - edge && y >= card.top + edge && y <= card.bottom - edge)
            }
            assertTrue("A4 has slots on ${w}x$h", seen >= 3)
        }
    }

    @Test fun a4SlotCentresKeepSixtyDpPitch() {
        val l = RingLayout(384f, 853f)
        val a4 = (0 until l.count).filter { l.arcIndex(it) == 3 }
        for (i in a4.indices) for (j in i + 1 until a4.size) {
            val d = hypot(l.slotXDp(a4[i]) - l.slotXDp(a4[j]), l.slotYDp(a4[i]) - l.slotYDp(a4[j]))
            assertTrue("A4 pitch $d", d >= 60f - 0.01f)
        }
    }

    @Test fun savedA4ItemsAreNeverMovedByTheFourArcRule() {
        val a3 = ArcSpec("A3", 2, 240f, 180f, 270f, 8, Layer.Home)
        val a4 = ArcSpec("A4", 3, 300f, 180f, 270f, 10, Layer.Home)
        val outer = Slot.App(AppId("outer/app"))
        val layout = ParcLayout(arcs = listOf(ArcAssignment(a3, emptyMap()), ArcAssignment(a4, mapOf(4 to outer))))
        assertEquals(layout, LayoutCounts.migrate(layout, 4))
    }
}
