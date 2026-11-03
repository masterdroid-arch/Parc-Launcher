package com.brightblu.parc

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test

class NotificationBadgesTest {
    private val a = AppId("p/A@0")
    private val b = AppId("p/B@0")
    private val c = AppId("q/C@0")
    private val folder = FolderId("f")
    private val apps = listOf(AppEntry(a, "A", "p", false, 0), AppEntry(b, "B", "p", false, 0), AppEntry(c, "C", "q", false, 0))
    private val layout = ParcLayout(folders = listOf(Folder(folder, "F", appIds = listOf(a, b, c))))
    private val scene = RingState(emptyList(), listOf(RingSlot(SlotPosition("A1", 0), Slot.Folder(folder), "F")), 0f)
    private fun count(counts: Map<String, Int>, source: ParcLayout = layout) =
        LauncherScenes.withUnread(scene, source, apps, counts).slots.single().unreadCount

    @Test fun foldersAggregateDistinctPackagesExcludeHiddenAndIgnoreMissingOrNegativeCounts() {
        assertEquals(5, count(mapOf("p" to 2, "q" to 3)))
        assertEquals(3, count(mapOf("p" to 2, "q" to 3), layout.copy(hidden = setOf(a, b))))
        assertEquals(2, count(mapOf("p" to 2, "q" to -3)))
        assertEquals(0, count(emptyMap()))
        assertEquals(Int.MAX_VALUE, count(mapOf("p" to Int.MAX_VALUE, "q" to 1)))
    }

    @Test fun masterSwitchOverridesAllAppsOptIn() {
        val counts = mapOf("p" to 3)
        assertEquals(0, LauncherScenes.withUnread(scene, layout, apps, counts, false, true).slots.single().unreadCount)
        val allApps = scene.copy(layer = Layer.AllApps)
        assertEquals(0, LauncherScenes.withUnread(allApps, layout, apps, counts).slots.single().unreadCount)
        assertEquals(3, LauncherScenes.withUnread(allApps, layout, apps, counts, true, true).slots.single().unreadCount)
    }

}
