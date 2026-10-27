package com.brightblu.parc

import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingLayout
import org.junit.Assert.*
import org.junit.Test

class LongPressMenuTest {
    private val geometry = RingLayout(384f, 853f)

    @Test fun menuPlacesToTheRightAndCentresVerticallyWhenSpaceAllows() {
        assertEquals(MenuPlacement(108f, 280f),
            placeMenu(100f, 400f, 220f, 240f, 384f, 853f))
    }

    @Test fun menuFlipsLeftNearTheRightEdge() {
        assertEquals(MenuPlacement(112f, 280f),
            placeMenu(340f, 400f, 220f, 240f, 384f, 853f))
    }

    @Test fun menuStaysRightOfANearLeftAnchor() {
        assertEquals(MenuPlacement(18f, 280f),
            placeMenu(10f, 400f, 220f, 240f, 384f, 853f))
    }

    @Test fun menuClampsInsideTopAndBottomKeepouts() {
        assertEquals(56f, placeMenu(100f, 10f, 220f, 240f, 384f, 853f).yDp)
        assertEquals(557f, placeMenu(100f, 850f, 220f, 240f, 384f, 853f).yDp)
    }

    @Test fun menuNeverLeavesTheScreenEvenWhenNeitherSideFits() {
        val placement = placeMenu(150f, 400f, 220f, 240f, 300f, 853f)
        assertTrue(placement.xDp >= Metrics.keepOutLeftDp)
        assertTrue(placement.xDp + 220f <= 300f - Metrics.keepOutRightDp)
    }

    @Test fun firstPinnedVacancyFillsInnerArcsInOrder() {
        val arcs = geometry.arcs
        fun assignment(arc: ArcSpec, slots: Map<Int, Slot>) = ArcAssignment(arc, slots)
        val empty = ParcLayout(arcs = arcs.map { ArcAssignment(it, emptyMap()) })
        assertEquals(SlotPosition("A1", 0), firstPinnedVacancy(empty))

        val a1 = arcs[0]
        val a1Full = ParcLayout(arcs = listOf(
            assignment(a1, (0 until a1.slotCapacity).associateWith { Slot.App(AppId("a$it")) }),
            assignment(arcs[1], emptyMap()),
            assignment(arcs[2], emptyMap()),
            assignment(arcs[3], emptyMap()),
        ))
        assertEquals(SlotPosition("A2", 0), firstPinnedVacancy(a1Full))

        val a2 = arcs[1]
        val bothFull = ParcLayout(arcs = listOf(
            assignment(a1, (0 until a1.slotCapacity).associateWith { Slot.App(AppId("a$it")) }),
            assignment(a2, (0 until a2.slotCapacity).associateWith { Slot.App(AppId("b$it")) }),
            assignment(arcs[2], emptyMap()),
            assignment(arcs[3], emptyMap()),
        ))
        assertNull(firstPinnedVacancy(bothFull))
    }

    @Test fun pinPositionKeepsHomeArcAppsInPlaceAndMovesOthersToVacancy() {
        val empty = ParcLayout(arcs = geometry.arcs.map { ArcAssignment(it, emptyMap()) })
        assertEquals(SlotPosition("A1", 1), pinPosition(empty, SlotPosition("A1", 1)))
        assertEquals(SlotPosition("A1", 0), pinPosition(empty, SlotPosition("social/A1", 0)))
        assertEquals(SlotPosition("A1", 0), pinPosition(empty, SlotPosition("R1", 0)))
    }
}
