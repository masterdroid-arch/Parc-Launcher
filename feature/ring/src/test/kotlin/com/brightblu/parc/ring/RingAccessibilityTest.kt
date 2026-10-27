package com.brightblu.parc.ring

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class RingAccessibilityTest {
    private val geometry = RingLayout(384f, 853f)
    private fun slot(arc: Int, index: Int, item: Slot = Slot.App(AppId("app/$arc/$index"))) =
        RingSlot(SlotPosition(geometry.arcs[arc].id, index), item, "Label $arc/$index", unreadCount = 3)

    @Test fun sparseUnsortedSlotsReadInnerToOuterThenBySlotIndex() {
        val slots = listOf(slot(3, 4), slot(0, 3), slot(2, 0), slot(0, 0), slot(1, 2))
        val state = RingScenes.home(geometry, slots).copy(arcs = geometry.arcs.reversed())
        val nodes = accessibleRingSlots(state)
        assertEquals(listOf(slots[3], slots[1], slots[4], slots[2], slots[0]), nodes.map { it.slot })
        nodes.forEach { node ->
            val arc = geometry.arcs.first { it.id == node.hit.position.arcId }
            assertEquals(arc.radiusDp, hypot(node.hit.xDp - arc.originXDp, node.hit.yDp - arc.originYDp), 0.001f)
        }
    }

    @Test fun descriptionsUseFullLabelsTypesCountsAndExposeSuspension() {
        val folder = slot(2, 0, Slot.Folder(FolderId("social"))).copy(label = "Music & Media", unreadCount = 12)
        val disabled = slot(0, 1).copy(enabled = false, unreadCount = -1)
        val nodes = accessibleRingSlots(RingScenes.home(geometry, listOf(folder, disabled)))
        assertEquals("Label 0/1, app, 0 unread", nodes[0].description)
        assertFalse(nodes[0].slot.enabled)
        assertEquals("Music & Media, folder, 12 unread", nodes[1].description)
    }

    @Test fun searchAndModalsHideUnderlyingNodesAndTargetsMeetMinimum() {
        val state = RingScenes.home(geometry, listOf(slot(0, 0)))
        assertTrue(accessibleRingSlots(state.copy(searchOpen = true)).isEmpty())
        assertTrue(accessibleRingSlots(state.copy(accessibilityHidden = true)).isEmpty())
        assertTrue(Metrics.touchTargetDp >= 48f)
        assertTrue(Metrics.hubTouchDp >= 48f)
        // The search button is the card's 44 dp touch target, styled like the hub (T35).
        assertEquals(44f, Metrics.searchButtonTouchDp, 0f)
    }

    @Test fun folderNodesExposeOnlyTheVisibleWindowAndMatchHitPayloads() {
        val ranked = List(40) { slot(0, 0).copy(label = "App $it", slot = Slot.App(AppId("app/$it"))) }
        val state = RingScenes.folder(geometry, Folder(FolderId("social"), "Social"), ranked)
        val nodes = accessibleRingSlots(state)
        val a4 = state.arcs.last()
        // A1 4 + A2 6 + A3 8 + the A4 window; the remaining A4 items are off-window and hidden.
        assertEquals(4 + 6 + 8 + a4.slotCapacity, nodes.size)
        assertTrue(nodes.all { it.hit.slot == it.slot.slot && it.hit.position == it.slot.position })
        assertEquals(state.slots.filter { it.position.arcId != a4.id }.map { it.position } +
            (0 until a4.slotCapacity).map { SlotPosition(a4.id, it) }, nodes.map { it.slot.position })
    }
}
