package com.brightblu.parc

import com.brightblu.parc.feature.settings.folders.*
import com.brightblu.parc.model.*
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class T41Test {
    private fun arc(index: Int, capacity: Int = 2, slots: Map<Int, Slot> = emptyMap()) = ArcAssignment(
        ArcSpec("A$index", index, 100f, 180f, 270f, capacity, Layer.Home), slots)
    private val app = Slot.App(AppId("app"))
    private val folder = Folder(FolderId("test"), "Test")

    @Test fun placementUsesFirstArcWithRoomThenFirstHoleRegardlessOfListOrder() {
        val layout = ParcLayout(arcs = listOf(arc(3), arc(1, slots = mapOf(0 to app)), arc(2)))
        assertEquals(SlotPosition("A1", 1), firstFolderPosition(layout))
        val fullFirst = layout.copy(arcs = listOf(arc(1, slots = mapOf(0 to app, 1 to app)), arc(2), arc(3)))
        assertEquals(SlotPosition("A2", 0), firstFolderPosition(fullFirst))
    }

    @Test fun placementDoesNotOverwriteActionsLinksAppsOrOverflow() {
        val layout = ParcLayout(arcs = listOf(arc(1, slots = mapOf(0 to app,
            1 to Slot.Action(ActionKind.Settings), 2 to Slot.Link("example://link", "Link")))))
        assertNull(firstFolderPosition(layout))
        assertNull(folderDocumentLayout(layout, FolderDocument(), FolderDocument(listOf(folder))))
    }

    @Test fun placementIgnoresNonHomeArcsAndFindsAHole() {
        val layout = ParcLayout(arcs = listOf(arc(0).let { it.copy(arc = it.arc.copy(layer = Layer.Folder)) },
            arc(1, 3, mapOf(0 to app, 2 to app))))
        assertEquals(SlotPosition("A1", 1), firstFolderPosition(layout))
    }

    @Test fun createAndRenameReorderAndDeletePersistThroughRepository() = runTest {
        val initial = ParcLayout(arcs = listOf(arc(1, slots = mapOf(0 to app))),
            hidden = setOf(AppId("hidden")), pins = setOf(app.id))
        val repository = FakeLayoutRepository(initial)
        val writer = PlacedFolderWriter(repository)
        val before = FolderDocument.from(initial)
        val created = before.withFolder(folder)
        assertEquals(FolderEditResult.Applied, writer.apply(before, created))
        assertEquals(Slot.Folder(folder.id), repository.layout.value.arcs.single().slots[1])
        val members = folder.copy(name = "Renamed", appIds = listOf(AppId("b"), AppId("a")))
        val reordered = created.withFolder(members)
        writer.apply(created, reordered)
        assertEquals(members, repository.layout.value.folders.single())
        writer.apply(reordered, reordered.copy(folders = emptyList()))
        val saved = repository.layout.value
        assertEquals(listOf(AppId("b"), AppId("a")), saved.folders.single { it.id == FolderId("new") }.appIds)
        assertEquals(mapOf(0 to app), saved.arcs.single().slots)
        assertEquals(initial.hidden, saved.hidden)
        assertEquals(initial.pins, saved.pins)
    }

    @Test fun fullArcsRefuseCreationWithoutSavingOrLeavingAnUnplacedFolder() = runTest {
        val initial = ParcLayout(arcs = listOf(arc(1, 1, mapOf(0 to app))))
        val repository = FakeLayoutRepository(initial)
        assertEquals(FolderEditResult.Unsupported(ArcsFull),
            PlacedFolderWriter(repository).apply(FolderDocument(), FolderDocument(listOf(folder))))
        assertEquals(initial, repository.layout.value)
    }

}
