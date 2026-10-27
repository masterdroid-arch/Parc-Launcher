package com.brightblu.parc

import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.RingRotation
import org.junit.Assert.*
import org.junit.Test

/** T35: usage-layer visuals, A2 ring, alphabetical ordering and search-button discoverability. */
class T35Test {
    private val geometry = RingLayout(384f, 853f)
    private val folderId = FolderId("media")

    private fun app(id: String, label: String) = AppEntry(AppId(id), label, id.substringBefore('/'), false, 0)

    @Test fun searchHintStaysForTheFirstWeekUnlessASearchSucceeds() {
        val firstSeen = 1_000_000L
        assertTrue(searchHintVisible(firstSeen, firstSeen, false))
        assertTrue(searchHintVisible(firstSeen + SearchHintWeekMillis - 1, firstSeen, false))
        assertFalse("a week passes", searchHintVisible(firstSeen + SearchHintWeekMillis, firstSeen, false))
        assertFalse("a search succeeded", searchHintVisible(firstSeen + 1000, firstSeen, true))
    }

    @Test fun homeProjectionKeepsA2RingItemsPastTheVisibleCapacity() {
        val apps = (0 until 8).map { app("pkg/a2/$it@0", "App $it") }
        val slots = (0 until 8).associateWith { Slot.App(apps[it].id) }
        val layout = ParcLayout(arcs = listOf(ArcAssignment(geometry.arcs[1], slots)))
        val scene = LauncherScenes.build(geometry, layout, apps, ShellState(), "") { null }
        val a2 = scene.slots.filter { it.position.arcId == geometry.arcs[1].id }
        assertEquals((0 until 8).toList(), a2.map { it.position.index })
        // The arc keeps its 5 visible slots; the extra items only reachable by rotating it.
        assertEquals(5, geometry.arcs[1].slotCapacity)
        assertTrue(RingRotation.rotates(a2.size, geometry.arcs[1].slotCapacity))
    }

    @Test fun folderProjectionOrdersByRankWhenProvided() {
        val b = app("pkg/b@0", "Beta")
        val a = app("pkg/a@0", "Alpha")
        val layout = ParcLayout(folders = listOf(Folder(folderId, "Media", appIds = listOf(b.id, a.id))))
        val shell = ShellState(layer = Layer.Folder, folderId = folderId)
        val ranked = LauncherScenes.build(geometry, layout, listOf(b, a), shell, "", rank = { listOf(a.id, b.id) }) { null }
        assertEquals(listOf(a.id, b.id), ranked.slots.map { (it.slot as Slot.App).id })
        val stored = LauncherScenes.build(geometry, layout, listOf(b, a), shell, "") { null }
        assertEquals(listOf(b.id, a.id), stored.slots.map { (it.slot as Slot.App).id })
    }
}
