package com.brightblu.parc.ring

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class RingHudBoundsTest {
    private val caption = 20f

    private fun app(index: Int, arc: String) =
        RingSlot(SlotPosition(arc, index), Slot.App(AppId("app/$arc/$index")), "App $index")

    private fun folder(index: Int, arc: String) =
        RingSlot(SlotPosition(arc, index), Slot.Folder(FolderId("$arc-$index")), "Folder")

    private fun home(layout: RingLayout, slots: List<RingSlot>, rotations: Map<String, Float> = emptyMap()) =
        RingScenes.home(layout, slots, "09:41", rotations)

    /** Nine folders on A4 with overflow: the outer arc rotates, every window position is taken. */
    private fun populated(layout: RingLayout): RingState {
        val slots = mutableListOf<RingSlot>()
        for (i in 0 until 4) slots += app(i, "A1")
        for (i in 0 until 8) slots += app(i, "A2")
        for (i in 0 until 7) slots += folder(i, "A3")
        for (i in 0 until 9) slots += folder(i, "A4")
        return home(layout, slots)
    }

    /** The old personal seed: A4 holds only the gear, a folder and a link, so its top is empty. */
    private fun sparseOuter(layout: RingLayout): RingState = home(layout, listOf(
        app(0, "A1"), app(1, "A1"), app(0, "A2"), app(1, "A2"),
        folder(0, "A3"), folder(1, "A3"), folder(2, "A3"), folder(3, "A3"),
        folder(4, "A3"), folder(5, "A3"), folder(6, "A3"),
        folder(0, "A4"), folder(1, "A4"),
    ))

    private fun drawnTopDp(scene: RingState, iconSize: Float): Float {
        val counts = scene.arcs.associate { arc -> arc.id to scene.slots.count { it.position.arcId == arc.id } }
        var top = Float.POSITIVE_INFINITY
        for (slot in scene.slots) {
            val arc = scene.arcs.first { it.id == slot.position.arcId }
            val count = counts.getValue(arc.id)
            val offset = scene.rotations[arc.id] ?: 0f
            var item = slot.position.index
            if (count > arc.slotCapacity) item = scene.slots.count {
                it.position.arcId == arc.id && it.position.index < slot.position.index
            }
            val position = RingRotation.windowPosition(item, offset, count, arc.slotCapacity)
            if (RingRotation.edgeAlpha(position, arc.slotCapacity) <= 0f) continue
            val angle = RingRotation.windowAngleDeg(arc, item, offset, count)
            val y = arc.originYDp + arc.radiusDp * sin(Math.toRadians(angle.toDouble())).toFloat()
            val extent = if (slot.slot is Slot.Folder) iconSize / 2 + Metrics.labelGapDp + caption
                else maxOf(Metrics.touchTargetDp / 2, iconSize / 2)
            top = minOf(top, y - extent)
        }
        return top
    }

    @Test fun everyVisibleSlotIsCoveredAtTheThreeReferenceSizes() {
        for ((w, h) in listOf(384f to 853f, 360f to 800f, 411f to 914f)) {
            for (mirrored in listOf(false, true)) for (aligned in listOf(false, true)) {
                val layout = RingLayout(w, h, RingInsets(top = 32f, bottom = 28f),
                    RingLayoutOptions(mirrored = mirrored, alignAcrossArcs = aligned))
                val scene = populated(layout)
                val bottom = RingHudBounds.bottomDp(scene, caption, Metrics.iconDp)
                assertTrue(bottom + Metrics.hubArcMarginDp <= drawnTopDp(scene, Metrics.iconDp) + .001f)
                for (icon in listOf(36f, 44f, 52f)) {
                    val sized = RingHudBounds.bottomDp(scene, caption, icon)
                    assertTrue(sized + Metrics.hubArcMarginDp <= drawnTopDp(scene, icon) + .001f)
                }
                assertEquals(bottom - 120f, RingHudBounds.heightDp(120f, bottom), .001f)
            }
        }
    }

    @Test fun populatedNineFoldersOnA4ReservesTheOuterArc() {
        val layout = RingLayout(384f, 853f)
        val full = RingHudBounds.bottomDp(populated(layout), caption)
        // A4's top window position is a folder: centre 773 - 315 plus its icon/caption reserve.
        assertEquals(773f - 315f - (Metrics.iconDp / 2 + Metrics.labelGapDp + caption) - Metrics.hubArcMarginDp,
            full, .5f)
    }

    @Test fun emptyOuterPositionsUncoverTheNextDrawnArc() {
        val layout = RingLayout(384f, 853f)
        val sparse = RingHudBounds.bottomDp(sparseOuter(layout), caption)
        val full = RingHudBounds.bottomDp(populated(layout), caption)
        // The highest drawn element is now A3's top folder, 60 dp lower than A4's empty position.
        assertEquals(773f - Metrics.homeRadius3Dp - (Metrics.iconDp / 2 + Metrics.labelGapDp + caption) -
            Metrics.hubArcMarginDp, sparse, .5f)
        assertTrue(sparse > full + 55f)
    }

    @Test fun tileViewportRowsFollowTheDrawnTop() {
        // The hub header and dot row sit inside the bound; the tile viewport is what is left.
        val headerAndDots = Metrics.hubHeaderHeightDp + Metrics.hubDotsHeightDp
        val rowHeight = Metrics.hudCellHeightDp + Metrics.hudCellGapDp
        fun rows(w: Float, h: Float): Int {
            val bottom = RingHudBounds.bottomDp(sparseOuter(RingLayout(w, h)), caption)
            val content = RingHudBounds.heightDp(116f, bottom) - headerAndDots
            return ((content + Metrics.hudCellGapDp) / rowHeight).toInt()
        }
        assertEquals(4, rows(384f, 853f))
        assertEquals(3, rows(360f, 800f))
        assertTrue("411x914 should fit five rows", rows(411f, 914f) >= 5)
    }

    @Test fun staleOffsetsDoNotMoveANonOverflowingArc() {
        val layout = RingLayout(384f, 853f)
        val slots = mutableListOf<RingSlot>()
        for (i in 0 until 4) slots += folder(i, "A4") // four items in a seven-slot window: no rotation
        val atRest = home(layout, slots)
        assertEquals(RingHudBounds.bottomDp(atRest, caption),
            RingHudBounds.bottomDp(atRest, caption, rotations = mapOf("A4" to 3f)), .001f)
    }

    @Test fun noVisibleSlotFallsBackToTheCompleteSweep() {
        val layout = RingLayout(384f, 853f)
        val empty = RingScenes.home(layout, emptyList())
        // The old conservative rule: the outer arc's sweep (top minus the touch radius), not the screen.
        assertEquals(773f - Metrics.homeRadius4Dp - Metrics.touchTargetDp / 2f - Metrics.hubArcMarginDp,
            RingHudBounds.bottomDp(empty, caption), .5f)
        assertEquals(0f, RingHudBounds.bottomDp(RingState(emptyList(), emptyList(), 0f), caption), 0f)
        assertEquals(0f, RingHudBounds.heightDp(400f, 300f), 0f)
    }

    @Test fun largerTextAndATallerBottomInsetReduceTheAvailableHeight() {
        val regular = sparseOuter(RingLayout(384f, 853f))
        val raised = sparseOuter(RingLayout(384f, 853f, RingInsets(bottom = 120f)))
        assertTrue(RingHudBounds.bottomDp(raised, caption) < RingHudBounds.bottomDp(regular, caption))
        assertEquals(22f, RingHudBounds.bottomDp(regular, 44f) - RingHudBounds.bottomDp(regular, 66f), .001f)
        assertEquals(22f, RingHudBounds.bottomDp(regular, 44f, 36f) -
            RingHudBounds.bottomDp(regular, 66f, 36f), .001f)
    }
}
