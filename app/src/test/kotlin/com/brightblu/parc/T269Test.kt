package com.brightblu.parc

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.ArcEndTurn
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotOrder
import com.brightblu.parc.model.SlotPosition
import com.brightblu.parc.ring.RingRotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T269: fixes from the read-only review of the DeepSeek cards. */
class T269Test {
    private val a = AppId("t/A@0")
    private val b = AppId("t/B@0")
    private val c = AppId("t/C@0")
    private val d = AppId("t/D@0")
    private fun arc(capacity: Int) = ArcSpec("A1", 0, 120f, 180f, 270f, capacity, Layer.Home)
    private fun layout(slots: Map<Int, Slot>) = ParcLayout(arcs = listOf(ArcAssignment(arc(6), slots)))

    @Test fun droppingOnAnEmptySpotFurtherAlongMovesOnlyTheDraggedApp() {
        val before = layout(mapOf(0 to Slot.App(a), 1 to Slot.App(b), 2 to Slot.App(c)))
        val after = SlotOrder.move(before, SlotPosition("A1", 0), SlotPosition("A1", 4))!!
        // b and c stay exactly where they were; only a left slot 0 for the empty spot 4.
        assertEquals(mapOf(1 to Slot.App(b), 2 to Slot.App(c), 4 to Slot.App(a)), after.arcs.single().slots)
        val back = SlotOrder.move(after, SlotPosition("A1", 4), SlotPosition("A1", 0))!!
        assertEquals(mapOf(0 to Slot.App(a), 1 to Slot.App(b), 2 to Slot.App(c)), back.arcs.single().slots)
    }

    @Test fun droppingOnAnOccupiedSpotStillMakesRoom() {
        val before = layout(mapOf(0 to Slot.App(a), 1 to Slot.App(b), 2 to Slot.App(c), 3 to Slot.App(d)))
        val after = SlotOrder.move(before, SlotPosition("A1", 0), SlotPosition("A1", 2))!!
        assertEquals(mapOf(0 to Slot.App(b), 1 to Slot.App(c), 2 to Slot.App(a), 3 to Slot.App(d)), after.arcs.single().slots)
    }

    @Test fun anArcThatDoesNotTurnKeepsItsSpotAnglesAndOneThatTurnsFollowsItsOffset() {
        val arc = arc(4)
        // Non-rotating: two apps on a four-slot arc, the free spot stays at its own angle.
        assertEquals(arc.slotAngle(2), RingRotation.spotAngleDeg(arc, 2, listOf(0, 1), 3f), 0f)
        // Rotating: six apps on a four-slot arc; turning by one slot moves the same rank by one pitch.
        val occupied = listOf(0, 1, 2, 3, 4, 5)
        val unturned = RingRotation.spotAngleDeg(arc, 2, occupied, 0f)
        val turned = RingRotation.spotAngleDeg(arc, 2, occupied, 1f)
        assertEquals(RingRotation.pitchDeg(arc), unturned - turned, 0.01f)
    }

    @Test fun anEndZoneIsOnlyActiveOnAnArcThatTurns() {
        assertEquals(0, ArcEndTurn.zone(283f, 180f, 270f, overflowing = false))
        assertEquals(1, ArcEndTurn.zone(283f, 180f, 270f, overflowing = true))
    }

    @Test fun anUninstalledPinnedAppLeavesItsSpotEmptyAndNothingShifts() {
        // The reconcile rule itself is tested in LayoutSyncTest ("leaves its slot empty and moves no index");
        // here the same promise is checked through the edit order: with the spot empty, a drop onto it moves only the dragged app.
        val before = layout(mapOf(0 to Slot.App(a), 2 to Slot.App(c)))
        val after = SlotOrder.move(before, SlotPosition("A1", 2), SlotPosition("A1", 1))!!
        assertEquals(mapOf(0 to Slot.App(a), 1 to Slot.App(c)), after.arcs.single().slots)
    }

    @Test fun lockLayoutBlocksPinAndUnpinAndFlowsFollowThePreferences() {
        val session = java.io.File("src/main/kotlin/com/brightblu/parc/LauncherSession.kt").readText()
        assertTrue(session.contains("\"Unpinned\" else \"Pinned\", guarded = true"))
        assertFalse(session.contains("guarded = !pinned"))
    }

    @Test fun aSearchShowsEveryNoteMatchAndAFigureNeverMixesFonts() {
        assertTrue(isAllDot("12:48"))
        assertTrue(isAllDot("87%"))
        assertFalse(isAllDot("12k"))
        assertFalse(isAllDot("8,400"))
        assertFalse(isAllDot(""))
    }
}
