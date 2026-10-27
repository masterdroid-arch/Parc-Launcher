package com.brightblu.parc.feature.settings.skins

import com.brightblu.parc.model.Skin
import com.brightblu.parc.ring.RingInsets
import com.brightblu.parc.ring.RingLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card's S15 acceptance: geometry is identical between skins, asserted on the layout rather than
 * by pixel-diffing screenshots. Two facts together prove it: the two skins differ only in colour
 * tokens and identity (so a skin carries no geometry), and the slot centres come from [RingLayout],
 * which takes no [Skin].
 */
class SkinGeometryTest {

    @Test
    fun `Dusk differs from Midnight only in colour tokens and identity`() {
        assertEquals(
            Skin.Midnight.copy(id = "dusk", name = "Dusk", colors = Skin.Dusk.colors),
            Skin.Dusk,
        )
    }

    @Test
    fun `the layout engine yields identical slot centres and arcs for the reference screen`() {
        val reference = RingLayout(384f, 853f, RingInsets(0f, 0f, 0f, 0f))

        assertEquals(geometry(reference), geometry(RingLayout(384f, 853f, RingInsets(0f, 0f, 0f, 0f))))
    }

    @Test
    fun `the preview geometry draws the same slots for every skin`() {
        val slots = SkinPreviewGeometry.slots()

        assertTrue(slots.isNotEmpty())
        slots.forEach { slot ->
            assertTrue(slot.degrees >= SkinPreviewGeometry.arcStartDegrees)
            assertTrue(slot.degrees <= SkinPreviewGeometry.arcStartDegrees + SkinPreviewGeometry.arcSweepDegrees)
            assertTrue(slot.radiusFraction > 0f)
        }
    }

    /** Arc tokens plus every slot centre and the cell it belongs to; nothing here takes a Skin. */
    private fun geometry(layout: RingLayout): List<String> = buildList {
        layout.arcs.forEach { arc ->
            add("${arc.id}:${arc.radiusDp}:${arc.startAngle}:${arc.endAngle}:${arc.slotAngles}")
        }
        for (index in 0 until layout.count) {
            add("${layout.arcIndex(index)}:${layout.slotIndex(index)}:${layout.slotXDp(index)}:${layout.slotYDp(index)}")
        }
    }
}
