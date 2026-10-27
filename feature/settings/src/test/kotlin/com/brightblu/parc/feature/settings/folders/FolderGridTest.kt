package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The packed slot view of a folder: capacities, empty slots and the append/reorder guards. */
class FolderGridTest {

    private val a = testId("a")
    private val b = testId("b")

    @Test
    fun `capacity is the sum of the arcs and the first free slot follows the apps`() {
        val grid = FolderGrid(listOf(4, 6, 8), listOf(a, b))
        assertEquals(18, grid.capacity)
        assertEquals(2, grid.firstFreeSlot())
        assertTrue(grid.hasFreeSlot)
        assertEquals(listOf(4, 6, 8), grid.arcCapacities)
    }

    @Test
    fun `arc index counts across arcs and rejects slots outside the grid`() {
        val grid = FolderGrid(listOf(4, 6, 8), emptyList())
        assertEquals(0, grid.arcIndexOf(0))
        assertEquals(0, grid.arcIndexOf(3))
        assertEquals(1, grid.arcIndexOf(4))
        assertEquals(1, grid.arcIndexOf(9))
        assertEquals(2, grid.arcIndexOf(10))
        assertEquals(2, grid.arcIndexOf(17))
        assertEquals(-1, grid.arcIndexOf(18))
        assertEquals(-1, grid.arcIndexOf(-1))
    }

    @Test
    fun `appAt and isEmptySlot agree at the packed boundary`() {
        val grid = FolderGrid(listOf(4, 6, 8), listOf(a, b))
        assertEquals(a, grid.appAt(0))
        assertEquals(b, grid.appAt(1))
        assertEquals(null, grid.appAt(2))
        assertFalse(grid.isEmptySlot(1))
        assertTrue(grid.isEmptySlot(2))
        assertFalse(grid.isEmptySlot(18))
        assertTrue(grid.contains(a))
        assertFalse(grid.contains(testId("z")))
    }

    @Test
    fun `a folder whose apps exceed the grid has no free slot and still reports them`() {
        val grid = FolderGrid(listOf(2), listOf(a, b, testId("c")))
        assertEquals(3, grid.appIds.size)
        assertFalse(grid.hasFreeSlot)
        assertEquals(-1, grid.firstFreeSlot())
        assertEquals(testId("c"), grid.appAt(2))
    }

    @Test
    fun `appended ignores a duplicate and refuses a full grid`() {
        val grid = FolderGrid(listOf(2), listOf(a))
        assertEquals(listOf(a, b), grid.appended(b).appIds)
        assertEquals(grid, grid.appended(a))
        val full = grid.appended(b)
        assertFalse(full.hasFreeSlot)
        assertEquals(full, full.appended(testId("c")))
    }

    @Test
    fun `removed drops the app and keeps the rest in order`() {
        val c = testId("c")
        val grid = FolderGrid(listOf(4), listOf(a, b, c))
        assertEquals(listOf(a, c), grid.removed(b).appIds)
        assertEquals(grid, grid.removed(testId("z")))
    }

    @Test
    fun `reordered accepts a permutation and rejects anything else`() {
        val grid = FolderGrid(listOf(4), listOf(a, b))
        assertEquals(listOf(b, a), grid.reordered(listOf(b, a)).appIds)
        assertThrows(IllegalArgumentException::class.java) { grid.reordered(listOf(a)) }
        assertThrows(IllegalArgumentException::class.java) { grid.reordered(listOf(a, a)) }
    }

    @Test
    fun `construction rejects duplicated apps, empty arcs and negative capacity`() {
        assertThrows(IllegalArgumentException::class.java) {
            FolderGrid(listOf(4), listOf(a, a))
        }
        assertThrows(IllegalArgumentException::class.java) { FolderGrid(emptyList(), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            FolderGrid(listOf(-1), emptyList())
        }
    }

    @Test
    fun `a zero-capacity arc is allowed, because a small screen can leave an arc with no slot`() {
        val grid = FolderGrid(listOf(4, 0), listOf(a))
        assertEquals(4, grid.capacity)
        assertEquals(0, grid.arcIndexOf(3))
        assertEquals(-1, grid.arcIndexOf(4))
    }

    @Test
    fun `the grid never mutates the caller's lists`() {
        val apps = listOf<AppId>(a)
        val capacities = listOf(4)
        val grid = FolderGrid(capacities, apps)
        grid.appended(b)
        grid.removed(a)
        assertEquals(listOf(a), apps)
        assertEquals(listOf(4), capacities)
    }
}
