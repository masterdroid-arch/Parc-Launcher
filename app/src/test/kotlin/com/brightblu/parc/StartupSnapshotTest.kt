package com.brightblu.parc

import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingLayout
import org.junit.Assert.*
import org.junit.Test

class StartupSnapshotTest {
    private val id = AppId("pkg/.Main@0")
    private val other = AppId("pkg/.Other@0")
    private val apps = listOf(AppEntry(id, "Full label", "pkg", false, 0), AppEntry(other, "Other", "pkg", false, 0))
    private val geometry = RingLayout(384f, 853f)
    private val folder = Folder(FolderId("social"), "Social", appIds = listOf(id, other))
    private val layout = ParcLayout(arcs = geometry.arcs.map { ArcAssignment(it, when(it.id) {
        "A1" -> mapOf(2 to Slot.App(id))
        "A3" -> mapOf(1 to Slot.Folder(folder.id))
        else -> emptyMap()
    }) }, folders = listOf(folder), pins = setOf(id))

    @Test fun coldSnapshotReproducesLabelsPositionsAndFoldersWithoutIconsOrLiveIndex() {
        val restored = StartupSnapshotJson.decode(StartupSnapshotJson.encode(StartupSnapshot(layout, apps)))
        assertEquals(layout, restored.layout)
        val scene = LauncherScenes.build(geometry, restored.layout, restored.apps, ShellState(), "") { null }
        assertEquals(listOf(SlotPosition("A1", 2), SlotPosition("A3", 1)), scene.slots.map { it.position })
        assertEquals(listOf("Full label", "Social"), scene.slots.map { it.label })
        assertTrue(scene.slots.all { it.bitmap == null && it.unreadCount == 0 })
    }

    @Test fun corruptUnknownVersionAndDuplicateIdsAreRejected() {
        listOf("{", "{\"version\":2,\"layout\":{},\"apps\":[]}",
            StartupSnapshotJson.encode(StartupSnapshot(layout, apps + apps))).forEach { source ->
            try { StartupSnapshotJson.decode(source); fail("Accepted invalid snapshot") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun unreadCountsAreLiveAndFolderPackagesAreCountedOnceWithHiddenAppsFiltered() {
        val scene = LauncherScenes.build(geometry, layout, apps, ShellState(), "") { null }
        val counted = LauncherScenes.withUnread(scene, layout, apps, mapOf("pkg" to 7))
        assertEquals(listOf(7, 7), counted.slots.map { it.unreadCount })
        val hidden = LauncherScenes.withUnread(scene, layout.copy(hidden = setOf(id, other)), apps, mapOf("pkg" to 7))
        assertEquals(0, hidden.slots.last().unreadCount)
        assertEquals(listOf(0, 0), LauncherScenes.withUnread(scene, layout, apps, emptyMap()).slots.map { it.unreadCount })
    }
}
