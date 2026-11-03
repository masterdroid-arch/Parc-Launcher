package com.brightblu.parc

import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingLayout
import org.junit.Assert.*
import org.junit.Test

class FolderOrderTest {
    @Test fun pinnedMembersLeadInPinOrderThenSuppliedOrderWithoutDuplicates() {
        val apps = List(14) { AppEntry(AppId("app/$it"), "App $it", "pkg$it", false, 0) }
        val folder = Folder(FolderId("test"), "Test", appIds = apps.map { it.id })
        val pins = linkedSetOf(AppId("elsewhere"), apps[8].id, apps[4].id)
        val supplied = apps.reversed().map { it.id }
        val state = LauncherScenes.build(RingLayout(384f, 853f), ParcLayout(folders = listOf(folder), pins = pins),
            apps, ShellState(layer = Layer.Folder, folderId = folder.id), "", rank = { supplied }) { null }
        val expected = listOf(apps[8].id, apps[4].id) + supplied.filterNot { it in pins }
        assertEquals(expected, state.slots.map { (it.slot as Slot.App).id })
        assertEquals(expected.take(3), state.slots.filter { it.position.arcId.endsWith("A1") }.map { (it.slot as Slot.App).id })
    }
}
