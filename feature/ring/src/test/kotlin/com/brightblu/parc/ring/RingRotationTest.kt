package com.brightblu.parc.ring

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class RingRotationTest {
    private val layout = RingLayout(384f, 853f)

    private fun arc(id: String = "A1", radius: Float = 120f, capacity: Int = 4): ArcSpec {
        // Uniform slot angles from 180 to 270 like the real layout.
        val angles = List(capacity) { 180f + it * 90f / (capacity - 1).coerceAtLeast(1) }
        return ArcSpec(id, 0, radius, angles.first(), angles.last(), capacity, Layer.Home,
            originXDp = 336f, originYDp = 797f, slotAngles = angles)
    }

    @Test fun rotatesOnlyWhenMoreItemsThanCapacity() {
        assertTrue(RingRotation.rotates(5, 4))
        assertFalse(RingRotation.rotates(4, 4))
        assertFalse(RingRotation.rotates(3, 4))
        assertFalse(RingRotation.rotates(3, 0))
    }

    @Test fun wrapSnapAndWindowPosition() {
        assertEquals(0f, RingRotation.wrap(0f, 4), 0f)
        assertEquals(3f, RingRotation.wrap(-1f, 4), 0f)
        assertEquals(1f, RingRotation.wrap(5f, 4), 0f)
        assertEquals(0f, RingRotation.wrap(Float.NaN, 4), 0f)
        assertEquals(2f, RingRotation.snap(2.3f), 0f)
        assertEquals(3f, RingRotation.snap(2.6f), 0f)
        assertEquals(2f, RingRotation.windowPosition(2, 0f, 16, 7), 0f)
        // Item 1 under offset 2 sits one slot below the window bottom: the canonical ring residue
        // (15 = -1 mod 16), which the alpha ramp reads as the slot it is sliding out of.
        assertEquals(15f, RingRotation.windowPosition(1, 2f, 16, 7), 0f)
        assertEquals(0f, RingRotation.edgeAlpha(RingRotation.windowPosition(1, 2f, 16, 7), 7), 0f)
    }

    @Test fun windowPositionIsTheCanonicalRingResidueForEveryOffset() {
        // 16 items through a 7-slot window: whatever the settled offset, the window shows exactly
        // the seven items offset..offset+6 (mod 16) and nothing else (T37).
        for (offset in 0 until 16) {
            val shown = (0 until 16).mapNotNull { i ->
                val p = RingRotation.windowPosition(i, offset.toFloat(), 16, 7)
                if (RingRotation.edgeAlpha(p, 7) > 0f) round(p).toInt() to i else null
            }.sortedBy { it.first }
            assertEquals("offset=$offset", (0 until 7).map { it to (offset + it) % 16 }, shown)
        }
        // The same residue holds for an even ring, where the old ±count/2 tie broke the fold.
        for (offset in 0 until 8) {
            val shown = (0 until 8).mapNotNull { i ->
                val p = RingRotation.windowPosition(i, offset.toFloat(), 8, 7)
                if (RingRotation.edgeAlpha(p, 7) > 0f) round(p).toInt() to i else null
            }.sortedBy { it.first }
            assertEquals("even ring offset=$offset", (0 until 7).map { it to (offset + it) % 8 }, shown)
        }
    }

    @Test fun theItemLeavingTheWindowBottomSlidesOutBeforeTheRingWrapsItToTheTop() {
        // 8 items, 4 slots: with a half-slot offset the departing item is just below slot 0 and the
        // arriving one just above slot 3, both fading, and no drawn position leaves the window.
        val arc = arc(capacity = 4)
        val departing = RingRotation.windowPosition(0, 0.5f, 8, 4)
        assertEquals(-0.5f, departing, 0.001f)
        assertEquals(0.5f, RingRotation.edgeAlpha(departing, 4), 0.001f)
        assertEquals(180f - 15f, RingRotation.windowAngleDeg(arc, 0, 0.5f, 8), 0.001f)
        val arriving = RingRotation.windowPosition(4, 0.5f, 8, 4)
        assertEquals(3.5f, arriving, 0.001f)
        assertEquals(270f + 15f, RingRotation.windowAngleDeg(arc, 4, 0.5f, 8), 0.001f)
    }

    @Test fun windowPositionIsIdentityForNonRotatingArcs() {
        // A non-rotating arc keeps the stored (sparse) window index untouched.
        assertEquals(3f, RingRotation.windowPosition(3, 0f, 2, 4), 0f)
        assertEquals(0f, RingRotation.windowPosition(0, 0f, 2, 4), 0f)
    }

    @Test fun everyOverflowingArcHasIndependentDenseCyclicOrder() {
        val slots = layout.arcs.flatMap { arc ->
            (0 until 9).map { i -> RingSlot(SlotPosition(arc.id, i * 2), Slot.Folder(FolderId("${arc.id}/$i")), "$i") }
        }
        for (arc in layout.arcs) {
            val onArc = slots.filter { it.position.arcId == arc.id }
            assertEquals((0 until 9).toList(), onArc.map { ringItemIndex(it, slots, arc.slotCapacity) })
            for (offset in 0..18) {
                assertEquals(arc.slotCapacity, onArc.count {
                    RingRotation.edgeAlpha(RingRotation.windowPosition(ringItemIndex(it, slots, arc.slotCapacity),
                        offset.toFloat(), 9, arc.slotCapacity), arc.slotCapacity) > 0f
                })
            }
        }
        val offsets = floatArrayOf(0f, 1f, 2f, 3f)
        val before = layout.arcs.mapIndexed { i, arc -> RingRotation.visibleRange(offsets[i].toInt(), 9, arc.slotCapacity) }
        offsets[2]++
        val after = layout.arcs.mapIndexed { i, arc -> RingRotation.visibleRange(offsets[i].toInt(), 9, arc.slotCapacity) }
        assertEquals(before[0], after[0]); assertEquals(before[1], after[1]); assertEquals(before[3], after[3])
        assertNotEquals(before[2], after[2])
        assertEquals(3f, RingRotation.windowPosition(3, 100f, 4, 4), 0f)
    }

    @Test fun edgeFadeAndScaleAreOneInsideAndZeroBeyondTheWindow() {
        val c = 4
        assertEquals(1f, RingRotation.edgeAlpha(0f, c), 0f)
        assertEquals(1f, RingRotation.edgeAlpha(3f, c), 0f)
        assertEquals(0.5f, RingRotation.edgeAlpha(-0.5f, c), 0f)
        assertEquals(0.5f, RingRotation.edgeAlpha(3.5f, c), 0f)
        assertEquals(0f, RingRotation.edgeAlpha(-1.2f, c), 0f)
        assertEquals(0f, RingRotation.edgeAlpha(4.2f, c), 0f)
        assertEquals(1f, RingRotation.edgeScale(0f, c), 0f)
        assertEquals(Metrics.rotateEdgeScaleMin, RingRotation.edgeScale(-1f, c), 0.001f)
        assertEquals(Metrics.rotateEdgeScaleMin, RingRotation.edgeScale(4f, c), 0.001f)
        assertTrue(RingRotation.edgeScale(-0.5f, c) in Metrics.rotateEdgeScaleMin..1f)
    }

    @Test fun windowAngleKeepsEveryItemOnItsWindowSlot() {
        val a = arc(capacity = 4) // angles 180, 210, 240, 270
        assertEquals(180f, RingRotation.windowAngleDeg(a, 0, 0f, 4), 0.001f)
        assertEquals(270f, RingRotation.windowAngleDeg(a, 3, 0f, 4), 0.001f)
        // Ring of 5 through 4 slots: item 4 is one slot above the top, item 5 wraps to the bottom.
        assertEquals(300f, RingRotation.windowAngleDeg(a, 4, 0f, 5), 0.001f)
        assertEquals(180f, RingRotation.windowAngleDeg(a, 5, 0f, 5), 0.001f)
        // Under a half-slot offset the item that wraps is reported below the bottom, never past the top.
        assertEquals(165f, RingRotation.windowAngleDeg(a, 0, 0.5f, 5), 0.001f)
        val (rx, ry) = RingRotation.slotPoint(a, 0, 0f, 4)
        assertEquals(336f - 120f, 336f + rx, 0.001f) // angle 180 -> left
        assertEquals(797f, 797f + ry, 0.001f)
    }

    @Test fun visibleRangeCyclicallyWrapsAndFallsBackToAllWhenWithinCapacity() {
        assertEquals(listOf(0, 1, 2, 3), RingRotation.visibleRange(0, 4, 4))
        assertEquals(listOf(0, 1, 2), RingRotation.visibleRange(0, 3, 4))
        assertEquals(emptyList<Int>(), RingRotation.visibleRange(0, 0, 4))
        assertEquals(listOf(2, 3, 0), RingRotation.visibleRange(2, 4, 3))
        assertEquals(listOf(0, 1, 2, 3), RingRotation.visibleRange(3, 4, 4)) // full ring: no wrap
        assertEquals(listOf(0, 1, 2, 3), RingRotation.visibleRange(1, 4, 7)) // count ≤ capacity: no wrap
        assertEquals(listOf(1, 2, 3), RingRotation.visibleRange(1, 4, 3)) // cyclic wrap of capacity 3
    }

    @Test fun hitTestFindsTheRotatedItemNotTheOriginalSlot() {
        // A1 with 4 slots (no rotation) is the baseline: tapping slot 1 hits item 1.
        val a = arc(capacity = 4)
        val arcs = listOf(a)
        val arcIndexOf = IntArray(4) { 0 }
        val itemIndexOf = IntArray(4) { it }
        val offsets = FloatArray(1) { 0f }
        val counts = IntArray(1) { 5 }
        assertEquals(1, RingRotation.nearest(336f + RingRotation.slotPoint(a, 1, 0f, 4).first,
            797f + RingRotation.slotPoint(a, 1, 0f, 4).second, arcs, arcIndexOf, itemIndexOf, offsets, counts))

        // Rotate offset by 1: the item now at window slot 1 is item 2.
        offsets[0] = 1f
        val (x2, y2) = RingRotation.slotPoint(a, 2, 1f, 5) // item 2 -> p = 1 -> angle 210
        val hit = RingRotation.nearest(336f + x2, 797f + y2, arcs, arcIndexOf, itemIndexOf, offsets, counts)
        assertEquals(2, hit)
        // The original slot-1 location now shows item 2, not item 1.
        assertNotEquals(1, hit)
    }

    @Test fun hitTestRejectsFarTapsAndOffWindowItems() {
        val a = arc(capacity = 4)
        val arcs = listOf(a)
        val arcIndexOf = IntArray(4) { 0 }
        val itemIndexOf = IntArray(4) { it }
        val offsets = FloatArray(1) { 0f }
        val counts = IntArray(1) { 4 }
        assertEquals(-1, RingRotation.nearest(336f, 797f, arcs, arcIndexOf, itemIndexOf, offsets, counts))
        assertEquals(-1, RingRotation.nearest(0f, 0f, arcs, arcIndexOf, itemIndexOf, offsets, counts))
        assertEquals(-1, RingRotation.nearest(Float.NaN, 0f, arcs, arcIndexOf, itemIndexOf, offsets, counts))
    }

    /**
     * The window slot an item occupies in the drawn layout: derived from the production placement
     * function, so a raw placement regression (drawing `itemIndex - offset`) fails here (T37).
     */
    private fun drawnSlot(arc: ArcSpec, item: Int, offset: Float, count: Int): Int {
        val p = (RingRotation.windowAngleDeg(arc, item, offset, count) - arc.slotAngle(0)) /
            RingRotation.pitchDeg(arc)
        return if (RingRotation.edgeAlpha(p, arc.slotCapacity) > 0f) round(p).toInt() else -1
    }

    @Test fun everyOffsetFillsTheWholeWindowWhileTheRingHoldsEnoughItems() {
        for (capacity in intArrayOf(3, 4, 5, 7)) {
            for (count in (capacity + 1)..(2 * capacity)) {
                val a = arc(capacity = capacity)
                for (offset in 0 until count) {
                    val drawn = IntArray(count) { drawnSlot(a, it, offset.toFloat(), count) }
                    val visible = drawn.filter { it >= 0 }
                    assertEquals("count=$count capacity=$capacity offset=$offset", capacity, visible.size)
                    assertEquals("one item per slot, count=$count offset=$offset",
                        (0 until capacity).toList(), visible.sorted())
                    val bySlot = IntArray(capacity) { -1 }
                    for (item in drawn.indices) if (drawn[item] >= 0) bySlot[drawn[item]] = item
                    // The full window is exactly the cyclic range the offset selects.
                    assertEquals(RingRotation.visibleRange(offset, count, capacity), bySlot.toList())
                }
            }
        }
    }

    @Test fun aOneSlotRotationMovesEveryVisibleItemByExactlyOneSlot() {
        for (capacity in intArrayOf(3, 4, 5, 7)) {
            for (count in (capacity + 1)..(2 * capacity)) {
                val a = arc(capacity = capacity)
                for (offset in 0 until count) {
                    val before = IntArray(count) { drawnSlot(a, it, offset.toFloat(), count) }
                    val after = IntArray(count) { drawnSlot(a, it, (offset + 1).toFloat(), count) }
                    var moved = 0
                    for (item in 0 until count) {
                        if (before[item] >= 0 && after[item] >= 0) {
                            // Every item that stays visible moves down exactly one slot.
                            assertEquals("item=$item count=$count offset=$offset",
                                before[item] - 1, after[item])
                            moved++
                        }
                    }
                    assertEquals("count=$count offset=$offset", capacity - 1, moved)
                    // Exactly one item leaves the bottom and one enters at the top per step.
                    assertEquals(1, (0 until count).count { before[it] >= 0 && after[it] < 0 })
                    assertEquals(1, (0 until count).count { before[it] < 0 && after[it] >= 0 })
                    // The bottom slot of the window is always occupied.
                    assertTrue(before.any { it == 0 })
                }
            }
        }
    }
}
