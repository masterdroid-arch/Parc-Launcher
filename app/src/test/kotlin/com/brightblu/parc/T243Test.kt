package com.brightblu.parc

import com.brightblu.parc.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T243: an edit drag reorders the arc, neighbours make room, and empty-arc drags turn it. */
class T243Test {
    private fun source(path: String) = File("src/main/kotlin/com/brightblu/parc/$path").readText()
    private fun ringSource(path: String) = File("../feature/ring/src/main/kotlin/com/brightblu/parc/ring/$path").readText()

    private val a = AppId("test/A@0")
    private val b = AppId("test/B@0")
    private val c = AppId("test/C@0")
    private val d = AppId("test/D@0")

    private fun arc(id: String, capacity: Int) = ArcSpec(id, 0, 120f, 180f, 270f, capacity, Layer.Home)
    private fun layout(a1: Map<Int, Slot> = emptyMap(), a2: Map<Int, Slot> = emptyMap()) = ParcLayout(
        arcs = listOf(ArcAssignment(arc("A1", 4), a1), ArcAssignment(arc("A2", 5), a2)))
    private fun at(arc: String, index: Int) = SlotPosition(arc, index)
    private fun slots(layout: ParcLayout, arc: String) =
        layout.arcs.first { it.arc.id == arc }.slots

    @Test fun aSameArcDragIsAReorderNotASwap() {
        val before = layout(a1 = mapOf(0 to Slot.App(a), 1 to Slot.App(b), 2 to Slot.App(c), 3 to Slot.App(d)))
        val after = SlotOrder.move(before, at("A1", 0), at("A1", 2))!!
        assertEquals(mapOf(0 to Slot.App(b), 1 to Slot.App(c), 2 to Slot.App(a), 3 to Slot.App(d)), slots(after, "A1"))
        assertEquals(setOf(a, b, c, d), slots(after, "A1").values.filterIsInstance<Slot.App>().map { it.id }.toSet())
    }

    @Test fun draggingLeftAlsoMakesRoomInsteadOfSwapping() {
        val before = layout(a1 = mapOf(0 to Slot.App(a), 1 to Slot.App(b), 2 to Slot.App(c), 3 to Slot.App(d)))
        val after = SlotOrder.move(before, at("A1", 3), at("A1", 1))!!
        assertEquals(mapOf(0 to Slot.App(a), 1 to Slot.App(d), 2 to Slot.App(b), 3 to Slot.App(c)), slots(after, "A1"))
    }

    @Test fun droppingIntoAnEmptySpotMovesTheHoleToTheOldPlace() {
        val before = layout(a1 = mapOf(0 to Slot.App(a), 2 to Slot.App(b)))
        val after = SlotOrder.move(before, at("A1", 0), at("A1", 1))!!
        assertEquals(mapOf(1 to Slot.App(a), 2 to Slot.App(b)), slots(after, "A1"))
        assertFalse(slots(after, "A1").containsKey(0))
    }

    @Test fun aCrossArcDragLeavesTheOldHoleAndOpensOneAtTheTarget() {
        val before = layout(a1 = mapOf(0 to Slot.App(a), 1 to Slot.App(b)),
            a2 = mapOf(0 to Slot.App(c), 1 to Slot.App(d)))
        val after = SlotOrder.move(before, at("A1", 0), at("A2", 1))!!
        assertEquals(mapOf(1 to Slot.App(b)), slots(after, "A1"))
        assertEquals(mapOf(0 to Slot.App(c), 1 to Slot.App(a), 2 to Slot.App(d)), slots(after, "A2"))
    }

    @Test fun anImpossibleMoveChangesNothing() {
        val before = layout(a1 = mapOf(0 to Slot.App(a)))
        assertNull(SlotOrder.move(before, at("A9", 0), at("A1", 1)))
        assertNull(SlotOrder.move(before, at("A1", 2), at("A1", 1)))
        assertNull(SlotOrder.move(before, at("A1", 0), at("A1", 0)))
        assertNull(SlotOrder.move(before, at("A1", -1), at("A1", 1)))
    }

    @Test fun onlyARotatingArcsEndZoneTurnsAndTheMirrorKeepsItsDirection() {
        // Arc angles run 180 (first) to 270 (last): past either end is outside that sweep.
        assertEquals(1, ArcEndTurn.zone(283f, 180f, 270f, overflowing = true))
        assertEquals(-1, ArcEndTurn.zone(167f, 180f, 270f, overflowing = true))
        assertEquals(0, ArcEndTurn.zone(225f, 180f, 270f, overflowing = true))
        assertEquals(0, ArcEndTurn.zone(283f, 180f, 270f, overflowing = false))
        // Mirrored geometry descends from 360 to 270; "past the last" is now below 270.
        assertEquals(1, ArcEndTurn.zone(257f, 360f, 270f, overflowing = true))
        assertEquals(-1, ArcEndTurn.zone(13f, 360f, 270f, overflowing = true))
        assertEquals(0, ArcEndTurn.zone(Float.NaN, 180f, 270f, overflowing = true))
    }

    @Test fun theRendererTurnsEmptyArcsAndPreviewsTheReorder() {
        val ring = ringSource("CanvasRingRenderer.kt")
        assertFalse(ring.contains("if (!onHub && !editEnabled) drawing?.rotationArc"))
        assertTrue(ring.contains("if (!onHub && !session.dragging.value)"))
        assertTrue(ring.contains("ArcEndTurn.zone"))
        assertTrue(ring.contains("nudgeSnapped"))
        assertTrue(ring.contains("prepareDragPreview(dragged, drop, dragReveal)"))
        assertTrue(ring.contains("dragReveal.animateTo(1f, Springs.press)"))
        assertTrue(ring.contains("drawing?.dropPosition(x, y, session.rotation.aligned)"))
        val edit = source("RingEditSession.kt")
        assertTrue(edit.contains("SlotOrder.move(before, from, to)"))
        assertFalse(edit.contains("repository.move(from, to)"))
    }
}
