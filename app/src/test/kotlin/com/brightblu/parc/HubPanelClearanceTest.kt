package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.ring.module.ModuleCard
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** T165: the stats panel (dots included) stays at least 8 dp above the module card on every size and screen. */
class HubPanelClearanceTest {
    private val screens = listOf(384f to 853f, 360f to 780f, 411f to 891f)
    // The hub origin sits 68 dp above the screen bottom (card bottom is 12 dp from the bottom).
    private fun cardTop(width: Float, height: Float) = ModuleCard.rect(width - 36f, height - 80f, width).top
    private fun panelBottom(size: HubSize, arcBottom: Float, cardTop: Float, clamp: Boolean): Float {
        val top = HubPanelLayout.topDp(24f)
        val bottom = if (clamp) HubPanelLayout.bottomDp(arcBottom, cardTop) else arcBottom
        return top + HubPanelLayout.heightDp(size, bottom - top)
    }

    @Test fun panelClearsCardOnEverySizeAndScreen() {
        for ((w, h) in screens) {
            val card = cardTop(w, h)
            // Slots sit 8 dp inside the card, so the arc bottom bound (slot top - 12) is 4 dp above the card; sparse arcs reach lower.
            for (arcBottom in listOf(card - 4f, card + 40f, card + 200f)) for (size in HubSize.entries) {
                val gap = card - panelBottom(size, arcBottom, card, clamp = true)
                assertTrue("$size ${w}x$h arcBottom=$arcBottom gap=$gap", gap >= Metrics.moduleCardPanelGapDp)
            }
        }
    }

    @Test fun oldGeometryFailsTheGap() {
        val card = cardTop(384f, 853f)
        assertEquals(416f, card)
        assertTrue(card - panelBottom(HubSize.Standard, card - 4f, card, clamp = false) < Metrics.moduleCardPanelGapDp)
    }
}
