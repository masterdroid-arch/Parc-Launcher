package com.brightblu.parc.ring.module

import com.brightblu.parc.model.*
import com.brightblu.parc.ring.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FolderModuleLayoutTest {
    @Test fun geometryAndMagnifiedCentresForZeroThroughTwentyApps() {
        for (mirrored in listOf(false, true)) for (count in 0..20) {
            val layout = RingLayout(384f, 853f, options = RingLayoutOptions(mirrored = mirrored))
            val arcs = FolderModuleLayout.arcs(layout, FolderId("test"))
            val card = ModuleCard.rect(layout.originXDp, layout.originYDp, layout.widthDp)
            assertEquals(listOf(150f, 250f), arcs.map { it.radiusDp })
            assertEquals(listOf(3, 5), arcs.map { it.slotCapacity })
            val centres = mutableListOf<Triple<Float, Float, Float>>()
            arcs.forEachIndexed { a, arc ->
                val pitch = if (a == 0) 23.1f else 17f
                for (i in 1 until arc.slotCapacity) assertEquals(pitch, abs(arc.slotAngle(i) - arc.slotAngle(i - 1)), 0.0001f)
                val n = if (a == 0) minOf(count, 3) else minOf((count - 3).coerceAtLeast(0), 5)
                repeat(n) { i ->
                    val angle = arc.slotAngle(i)
                    centres += Triple(layout.originXDp + cos(Math.toRadians(angle.toDouble())).toFloat() * arc.radiusDp,
                        layout.originYDp + sin(Math.toRadians(angle.toDouble())).toFloat() * arc.radiusDp, angle)
                }
            }
            // Check every focus choice, including spring travel to and from Dock-like magnify.
            for (focus in -1 until centres.size) for (step in 0..10) {
                val points = centres.mapIndexed { i, (x, y, angle) ->
                    val p = if (i == focus) step / 10f else 0f
                    MagnifyGeometry.pushedX(Magnify.DockLike, card, x, angle, p) to
                        MagnifyGeometry.pushedY(Magnify.DockLike, card, y, angle, p)
                }
                points.forEachIndexed { i, (x, y) ->
                    assertTrue(x >= card.left + 8f && x <= card.right - 8f)
                    assertTrue(y >= card.top + 8f && y <= card.bottom - 8f)
                    for (j in 0 until i) assertTrue(hypot(x - points[j].first, y - points[j].second) >= 60f)
                }
            }
        }
    }
}
