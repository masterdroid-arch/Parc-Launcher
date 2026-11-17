package com.brightblu.parc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T186: the pure side of dragging hub pages into a new order. */
class HubReorderTest {
    private val order = listOf("a", "b", "c", "d", "e")

    @Test fun moveFirstToLast() = assertEquals(listOf("b", "c", "d", "e", "a"), HubReorder.moveTo(order, "a", 4))
    @Test fun moveLastToFirst() = assertEquals(listOf("e", "a", "b", "c", "d"), HubReorder.moveTo(order, "e", 0))
    @Test fun moveIsClampedAndUnknownIsIgnored() {
        assertEquals(listOf("b", "c", "d", "e", "a"), HubReorder.moveTo(order, "a", 99))
        assertEquals(order, HubReorder.moveTo(order, "zzz", 1))
    }

    @Test fun hiddenPagesKeepTheirPlaceWhenFirstVisibleGoesLast() {
        // b and d are hidden; the visible tabs a, c, e are reordered to c, e, a.
        val result = HubReorder.reorderVisible(order, listOf("a", "c", "e"), listOf("c", "e", "a"))
        assertEquals(listOf("c", "b", "e", "d", "a"), result)
    }

    @Test fun hiddenPagesKeepTheirPlaceWhenLastVisibleGoesFirst() {
        val result = HubReorder.reorderVisible(order, listOf("a", "c", "e"), listOf("e", "a", "c"))
        assertEquals(listOf("e", "b", "a", "d", "c"), result)
    }

    @Test fun unknownIdsAreIgnoredAndMissingVisibleOnesGoToTheEnd() {
        assertEquals(listOf("c", "b", "a", "d", "e"), HubReorder.reorderVisible(order, listOf("a", "c"), listOf("c", "ghost", "a")))
        assertEquals(listOf("c", "b", "a", "d", "e"), HubReorder.reorderVisible(order, listOf("a", "c"), listOf("c")))
        assertEquals(order, HubReorder.reorderVisible(order, listOf("a", "c"), listOf("a", "c")))
    }

    @Test fun configKeepsHiddenPagesPlaceAndStoresThroughPageOrder() {
        val config = HubConfig(pageOrder = HubCatalog.pages, hiddenPages = setOf(HubCatalog.pages[1]))
        val visible = config.pages
        val moved = config.reorderVisible(visible, visible.drop(1) + visible.first())
        assertEquals(HubCatalog.pages[1], moved.orderedPages[1])
        assertEquals(visible.drop(1) + visible.first(), moved.pages)
        assertEquals(moved.pages, HubConfig.decode(moved.encode()).pages)
    }

    @Test fun movePageToUsesTheFullList() {
        // T200: the arcade only counts as a page once a game is unlocked.
        val config = HubConfig(pageOrder = HubCatalog.pages, arcadeOpen = true)
        assertEquals(HubCatalog.pages.last(), config.movePageTo(HubCatalog.pages.last(), 0).orderedPages.first())
        assertEquals(config, config.movePageTo("nope", 0))
    }

    private val starts = floatArrayOf(0f, 100f, 220f, 300f)
    private val sizes = floatArrayOf(96f, 116f, 76f, 96f)

    @Test fun insertIndexFollowsTheCentreOfTheHeldItem() {
        assertEquals(0, HubReorder.insertIndex(starts, sizes, 2, -300f))
        assertEquals(2, HubReorder.insertIndex(starts, sizes, 2, 0f))
        assertEquals(3, HubReorder.insertIndex(starts, sizes, 0, 400f))
        assertEquals(1, HubReorder.insertIndex(starts, sizes, 0, 120f))
    }

    @Test fun reflowPacksTheNewOrderAndKeepsTheGap() {
        // gap is 4 px between neighbours here
        val s = floatArrayOf(0f, 100f, 220f)
        val z = floatArrayOf(96f, 116f, 76f)
        val moved = HubReorder.reflow(s, z, 0, 2) // order becomes 1, 2, 0
        assertArrayEquals(floatArrayOf(200f, 0f, 120f), moved, 0.01f)
    }

    @Test fun reflowWithNoMoveKeepsTheLayout() {
        val s = floatArrayOf(0f, 100f, 220f)
        val z = floatArrayOf(96f, 116f, 76f)
        assertArrayEquals(s, HubReorder.reflow(s, z, 1, 1), 0.01f)
    }

    @Test fun edgeScrollGoesTowardsTheNearEdgeAndStopsInTheMiddle() {
        assertTrue(HubReorder.edgeScroll(5f, 100f, 0f, 400f, 40f, 10f) < 0f)
        assertTrue(HubReorder.edgeScroll(300f, 390f, 0f, 400f, 40f, 10f) > 0f)
        assertEquals(0f, HubReorder.edgeScroll(150f, 250f, 0f, 400f, 40f, 10f), 0f)
        assertEquals(-10f, HubReorder.edgeScroll(-20f, 60f, 0f, 400f, 40f, 10f), 0.001f)
    }
}
