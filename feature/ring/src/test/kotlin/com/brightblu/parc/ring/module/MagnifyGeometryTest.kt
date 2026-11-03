package com.brightblu.parc.ring.module

import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Magnify
import com.brightblu.parc.model.Springs
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.RingLayoutOptions
import com.brightblu.parc.ring.RingScenes
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class MagnifyGeometryTest {
    @Test fun scaleAndPushPerSetting() {
        assertEquals(1f, MagnifyGeometry.scale(Magnify.Off), 0f)
        assertEquals(0f, MagnifyGeometry.pushDp(Magnify.Off), 0f)
        assertEquals(1.2f, MagnifyGeometry.scale(Magnify.Subtle), 0f)
        assertEquals(8f, MagnifyGeometry.pushDp(Magnify.Subtle), 0f)
        assertEquals(1.45f, MagnifyGeometry.scale(Magnify.DockLike), 0f)
        assertEquals(16f, MagnifyGeometry.pushDp(Magnify.DockLike), 0f)
    }

    @Test fun progressBlendsFromRestToFull() {
        for (m in Magnify.entries) {
            assertEquals(1f, MagnifyGeometry.scaleAt(m, 0f), 0f)
            assertEquals(MagnifyGeometry.scale(m), MagnifyGeometry.scaleAt(m, 1f), 1e-6f)
            assertEquals(0f, MagnifyGeometry.pushX(m, 200f, 0f), 0f)
        }
    }

    @Test fun offHasNoPush() {
        for (angle in listOf(0f, 45f, 90f, 200f, 270f)) {
            assertEquals(0f, MagnifyGeometry.pushX(Magnify.Off, angle), 0f)
            assertEquals(0f, MagnifyGeometry.pushY(Magnify.Off, angle), 0f)
        }
    }

    @Test fun pushIsRadialAtZeroFortyFiveAndNinetyDegrees() {
        for (m in listOf(Magnify.Subtle, Magnify.DockLike)) {
            val len = MagnifyGeometry.pushDp(m)
            assertEquals(len, MagnifyGeometry.pushX(m, 0f), 1e-4f); assertEquals(0f, MagnifyGeometry.pushY(m, 0f), 1e-4f)
            val d = len * Math.sqrt(0.5).toFloat()
            assertEquals(d, MagnifyGeometry.pushX(m, 45f), 1e-4f); assertEquals(d, MagnifyGeometry.pushY(m, 45f), 1e-4f)
            assertEquals(0f, MagnifyGeometry.pushX(m, 90f), 1e-4f); assertEquals(len, MagnifyGeometry.pushY(m, 90f), 1e-4f)
        }
    }

    @Test fun pushLengthEqualsTheSettingAtEveryAngle() {
        for (m in Magnify.entries) for (a in -360..720 step 7) {
            val angle = a.toFloat()
            assertEquals(MagnifyGeometry.pushDp(m), hypot(MagnifyGeometry.pushX(m, angle), MagnifyGeometry.pushY(m, angle)), 1e-3f)
        }
    }

    @Test fun pushPointsAwayFromTheHub() {
        // Slot at angle a sits at (cos a, sin a) from the hub; the push has a positive dot product with that vector.
        for (a in 180..270 step 5) {
            val rad = Math.toRadians(a.toDouble())
            val dot = MagnifyGeometry.pushX(Magnify.DockLike, a.toFloat()) * cos(rad) +
                MagnifyGeometry.pushY(Magnify.DockLike, a.toFloat()) * sin(rad)
            assertEquals(16.0, dot, 1e-3)
        }
    }

    private val screens = listOf(Triple(384f, 853f, 0), Triple(360f, 780f, 0), Triple(411f, 891f, 0))

    private fun check(where: String, layoutOrigin: Pair<Float, Float>, card: CardRect, radius: Float, angle: Float) {
        val x = layoutOrigin.first + radius * cos(Math.toRadians(angle.toDouble())).toFloat()
        val y = layoutOrigin.second + radius * sin(Math.toRadians(angle.toDouble())).toFloat()
        val px = MagnifyGeometry.pushedX(Magnify.DockLike, card, x, angle)
        val py = MagnifyGeometry.pushedY(Magnify.DockLike, card, y, angle)
        val why = "$where slot at ($x,$y) angle $angle r $radius card $card"
        assertTrue(why, px >= card.left + 4f - 1e-3f && px <= card.right - 4f + 1e-3f)
        assertTrue(why, py >= card.top + 4f - 1e-3f && py <= card.bottom - 4f + 1e-3f)
        // Where there is room the push is the full radial vector.
        val fx = x + MagnifyGeometry.pushX(Magnify.DockLike, angle); val fy = y + MagnifyGeometry.pushY(Magnify.DockLike, angle)
        if (fx in card.left + 4f..card.right - 4f && fy in card.top + 4f..card.bottom - 4f) {
            assertEquals(why, fx, px, 1e-4f); assertEquals(why, fy, py, 1e-4f)
        }
    }

    @Test fun dockLikeSlotStaysInsideTheCardForEveryHomeSlotPosition() {
        for ((w, h, _) in screens) for (arcs in listOf(3, 4)) for (mirrored in listOf(false, true)) {
            val layout = RingLayout(w, h, options = RingLayoutOptions(arcsShown = arcs, mirrored = mirrored))
            val card = ModuleCard.rect(layout.originXDp, layout.originYDp, w)
            for (arc in layout.arcs) for (s in 0 until arc.slotCapacity)
                check("home ${w}x$h arcs=$arcs mirrored=$mirrored ${arc.id}", layout.originXDp to layout.originYDp, card, arc.radiusDp, arc.slotAngle(s))
        }
    }

    @Test fun dockLikeSlotStaysInsideTheCardForEveryFolderSlotPosition() {
        val folder = Folder(FolderId("f"), "F")
        for ((w, h, _) in screens) for (mirrored in listOf(false, true)) for (icons in listOf(0, 4, 8, 20)) {
            val layout = RingLayout(w, h, options = RingLayoutOptions(mirrored = mirrored, iconsPerArc = icons))
            val card = ModuleCard.rect(layout.originXDp, layout.originYDp, w)
            val scene = RingScenes.folder(layout, folder, emptyList())
            for (arc in scene.arcs) for (s in 0 until arc.slotCapacity)
                check("folder ${w}x$h mirrored=$mirrored icons=$icons ${arc.id}", layout.originXDp to layout.originYDp, card, arc.radiusDp, arc.slotAngle(s))
        }
    }

    @Test fun clampedPushIsZeroAtRestAndFullInTheOpen() {
        val card = CardRect(0f, 0f, 360f, 425f)
        // Slot 3 dp from the card edge: rest is exactly where it was, even though the end point is clamped inward.
        assertEquals(3f, MagnifyGeometry.pushedX(Magnify.DockLike, card, 3f, 180f, 0f), 0f)
        assertEquals(4f, MagnifyGeometry.pushedX(Magnify.DockLike, card, 3f, 180f, 1f), 1e-4f)
        assertEquals(100f - 16f, MagnifyGeometry.pushedX(Magnify.DockLike, card, 100f, 180f, 1f), 1e-3f)
        assertEquals(100f - 8f, MagnifyGeometry.pushedX(Magnify.DockLike, card, 100f, 180f, 0.5f), 1e-3f)
        assertEquals(200f + 16f, MagnifyGeometry.pushedY(Magnify.DockLike, card, 200f, 90f, 1f), 1e-3f)
        assertEquals(50f, MagnifyGeometry.pushedY(Magnify.Off, null, 50f, 90f, 1f), 0f)
    }

    @Test fun motionGrowsOverSpringPressAndSettlesAtOne() {
        val m = MagnifyMotion()
        m.focus(3)
        var t = 0
        while (m.active && t < 600) { m.step(1f / 60f, Springs.press, 1f, 1f); t++ }
        assertEquals(1f, m.progress(3), 0f)
        assertEquals(0f, m.progress(2), 0f)
        assertTrue("settles inside 600 ms", t < 36)
    }

    @Test fun movingToANeighbourShrinksTheOldOneWithoutAPop() {
        val m = MagnifyMotion()
        m.focus(1)
        repeat(60) { m.step(1f / 60f, Springs.press, 1f, 1f) }
        m.focus(2)
        assertEquals(1f, m.progress(1), 0f)
        assertEquals(0f, m.progress(2), 0f)
        m.step(1f / 60f, Springs.press, 1f, 1f)
        assertTrue(m.progress(1) < 1f && m.progress(1) > 0f)
        assertTrue(m.progress(2) > 0f)
        repeat(60) { m.step(1f / 60f, Springs.press, 1f, 1f) }
        assertEquals(0f, m.progress(1), 0f); assertEquals(1f, m.progress(2), 0f)
        assertFalse(m.active)
    }

    @Test fun returningToTheShrinkingSlotKeepsItsValue() {
        val m = MagnifyMotion()
        m.focus(1)
        repeat(60) { m.step(1f / 60f, Springs.press, 1f, 1f) }
        m.focus(2)
        repeat(3) { m.step(1f / 60f, Springs.press, 1f, 1f) }
        val before = m.progress(1)
        m.focus(1)
        assertEquals(before, m.progress(1), 0f)
        assertEquals(1, m.subject)
    }

    @Test fun letGoSpringsBackAndAnimatorScaleZeroJumps() {
        val m = MagnifyMotion()
        m.focus(4)
        m.step(0.1f, Springs.press, 1f, 0f)
        assertEquals(1f, m.progress(4), 0f)
        m.focus(MagnifyMotion.NONE)
        m.step(0.1f, Springs.press, 1f, 0f)
        assertEquals(0f, m.progress(4), 0f)
        assertFalse(m.active)
    }
}
