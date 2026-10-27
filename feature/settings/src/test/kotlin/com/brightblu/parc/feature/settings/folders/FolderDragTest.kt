package com.brightblu.parc.feature.settings.folders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drag rules of the card: a full arc, an empty slot, and a drop on a pinned app. Reorder moves
 * the dragged app to the target slot and shifts the apps between the two positions by one.
 */
class FolderDragTest {

    private val a = testId("a")
    private val b = testId("b")
    private val c = testId("c")
    private val d = testId("d")

    // region canDrop

    @Test
    fun `slots outside the grid are refused`() {
        val grid = FolderGrid(listOf(4, 6), listOf(a, b))
        assertEquals(
            DropVerdict.Rejected(DropRejection.OutOfRange),
            FolderDrag.canDrop(grid, -1, 2),
        )
        assertEquals(
            DropVerdict.Rejected(DropRejection.OutOfRange),
            FolderDrag.canDrop(grid, 0, 10),
        )
    }

    @Test
    fun `a drop on the slot the drag started from is refused`() {
        val grid = FolderGrid(listOf(4), listOf(a, b))
        assertEquals(DropVerdict.Rejected(DropRejection.SameSlot), FolderDrag.canDrop(grid, 1, 1))
    }

    @Test
    fun `an empty source is refused`() {
        val grid = FolderGrid(listOf(4), listOf(a))
        assertEquals(DropVerdict.Rejected(DropRejection.EmptySource), FolderDrag.canDrop(grid, 3, 0))
    }

    @Test
    fun `a drop on a pinned app is refused`() {
        val grid = FolderGrid(listOf(4), listOf(a, b))
        assertEquals(
            DropVerdict.Rejected(DropRejection.PinnedTarget),
            FolderDrag.canDrop(grid, 0, 1, pinned = setOf(b)),
        )
    }

    @Test
    fun `a pinned app can still be dragged and can be dropped on an empty slot`() {
        val grid = FolderGrid(listOf(4), listOf(a, b))
        assertEquals(DropVerdict.Allowed, FolderDrag.canDrop(grid, 1, 0, pinned = setOf(b)))
        assertEquals(DropVerdict.Allowed, FolderDrag.canDrop(grid, 0, 2, pinned = setOf(a)))
    }

    @Test
    fun `an ordinary move is allowed`() {
        val grid = FolderGrid(listOf(4), listOf(a, b, c))
        assertEquals(DropVerdict.Allowed, FolderDrag.canDrop(grid, 0, 2))
    }

    // endregion

    // region reorder

    @Test
    fun `a move inside a full arc keeps every app and the size`() {
        val grid = FolderGrid(listOf(4), listOf(a, b, c, d))
        val moved = FolderDrag.reorder(grid, 0, 3)
        assertEquals(listOf(b, c, d, a), moved.appIds)
        assertEquals(4, moved.appIds.size)
        assertEquals(grid.appIds.toSet(), moved.appIds.toSet())
    }

    @Test
    fun `a move upward shifts the apps it passes`() {
        val grid = FolderGrid(listOf(4, 4), listOf(a, b, c, d))
        assertEquals(listOf(c, a, b, d), FolderDrag.reorder(grid, 2, 0).appIds)
    }

    @Test
    fun `a drop on the first empty slot of a later arc leaves the packed order alone`() {
        val grid = FolderGrid(listOf(4, 4), listOf(a, b, c, d))
        val moved = FolderDrag.reorder(grid, 3, 4)
        assertEquals(listOf(a, b, c, d), moved.appIds)
        assertEquals(1, moved.arcIndexOf(4))
    }

    @Test
    fun `a drop on an empty slot lands at the end of the packed list`() {
        val grid = FolderGrid(listOf(4, 6, 8), listOf(a, b, c))
        val moved = FolderDrag.reorder(grid, 0, 17)
        assertEquals(listOf(b, c, a), moved.appIds)
        assertEquals(3, moved.appIds.size)
        assertEquals(b, moved.appAt(0))
        assertEquals(a, moved.appAt(2))
    }

    @Test
    fun `a drop on a pinned app throws`() {
        val grid = FolderGrid(listOf(4), listOf(a, b))
        assertThrows(IllegalArgumentException::class.java) {
            FolderDrag.reorder(grid, 0, 1, pinned = setOf(b))
        }
    }

    @Test
    fun `reorder refuses an empty source and an out-of-range target`() {
        val grid = FolderGrid(listOf(4), listOf(a))
        assertThrows(IllegalArgumentException::class.java) { FolderDrag.reorder(grid, 3, 0) }
        assertThrows(IllegalArgumentException::class.java) { FolderDrag.reorder(grid, 0, 9) }
    }

    @Test
    fun `reorder leaves the source grid untouched`() {
        val grid = FolderGrid(listOf(4), listOf(a, b, c))
        FolderDrag.reorder(grid, 0, 2)
        assertEquals(listOf(a, b, c), grid.appIds)
    }

    // endregion

    // region dropTargetSlot

    @Test
    fun `the drag target follows the row height and is clamped to the grid`() {
        assertEquals(1, FolderDrag.dropTargetSlot(from = 0, dragYDp = 56f, rowHeightDp = 56f, slots = 18))
        assertEquals(0, FolderDrag.dropTargetSlot(from = 0, dragYDp = -56f, rowHeightDp = 56f, slots = 18))
        assertEquals(17, FolderDrag.dropTargetSlot(from = 0, dragYDp = 5_000f, rowHeightDp = 56f, slots = 18))
        assertEquals(0, FolderDrag.dropTargetSlot(from = 17, dragYDp = -5_000f, rowHeightDp = 56f, slots = 18))
    }

    @Test
    fun `a small drag stays on the same row and a half row rounds away`() {
        assertEquals(3, FolderDrag.dropTargetSlot(from = 3, dragYDp = 20f, rowHeightDp = 56f, slots = 18))
        assertEquals(4, FolderDrag.dropTargetSlot(from = 3, dragYDp = 40f, rowHeightDp = 56f, slots = 18))
    }

    @Test
    fun `a non-finite or empty drag cannot move past the grid`() {
        assertEquals(2, FolderDrag.dropTargetSlot(from = 2, dragYDp = Float.NaN, rowHeightDp = 56f, slots = 18))
        assertEquals(-1, FolderDrag.dropTargetSlot(from = 2, dragYDp = 56f, rowHeightDp = 56f, slots = 0))
        assertThrows(IllegalArgumentException::class.java) {
            FolderDrag.dropTargetSlot(from = 2, dragYDp = 56f, rowHeightDp = 0f, slots = 18)
        }
    }

    @Test
    fun `every refused drop has a message for the notice line`() {
        DropRejection.entries.forEach { assertTrue(it.message.isNotBlank()) }
    }

    // endregion
}
