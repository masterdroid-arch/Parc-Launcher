package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller's contract: events reduce to a new document, the document reaches the layout, an
 * edit the contract cannot store is reverted with a reason, and a layout snapshot refreshes the
 * document while no write is in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderEditorControllerTest {

    private val a = testId("a")
    private val b = testId("b")
    private val c = testId("c")
    private val d = testId("d")
    private val social = testFolder("social", "Social", listOf(a, b, c))
    private val apps: List<AppEntry> = listOf(
        testEntry(a, "Alpha"), testEntry(b, "Beta"), testEntry(c, "Charlie"), testEntry(d, "Delta"),
    )

    private class Harness(
        scope: TestScope,
        val repository: FakeLayoutRepository,
        apps: List<AppEntry>,
    ) {
        val appsFlow = MutableStateFlow(apps)
        val controller = FolderEditorController(
            writer = LayoutFolderWriter(repository),
            apps = appsFlow,
            layout = repository.layout,
            arcCapacities = TestCapacities,
            scope = scope.backgroundScope,
        )
    }

    private fun TestScope.harness(pins: Set<com.brightblu.parc.model.AppId> = emptySet()): Harness =
        Harness(this, FakeLayoutRepository(testLayout(social, pins = pins)), apps)

    private fun Harness.storedFolder(id: String) =
        repository.layout.value.folders.first { it.id.value == id }

    @Test
    fun `the document starts from the layout and the slots carry the labels`() = runTest {
        val harness = harness()
        runCurrent()
        val state = harness.controller.state.value
        assertEquals(listOf(social), state.document.folders)
        assertEquals(EditorPage.Folders, state.page)

        harness.controller.dispatch(FolderEditorEvent.OpenFolder(FolderId("social")))
        runCurrent()
        assertEquals(18, harness.controller.state.value.grid?.capacity)
        val ui = harness.controller.state.value.toUi(harness.appsFlow.value)
        assertEquals("Social", ui.title)
        assertEquals(listOf("Alpha", "Beta", "Charlie"), ui.slots.take(3).map { it.label })
        assertNull(ui.slots[3].appId)
    }

    @Test
    fun `a drop persists the new membership order`() = runTest {
        val harness = harness()
        runCurrent()
        harness.controller.dispatch(FolderEditorEvent.OpenFolder(FolderId("social")))
        harness.controller.dispatch(FolderEditorEvent.DragStart(0))
        harness.controller.dispatch(FolderEditorEvent.DragTo(2))
        harness.controller.dispatch(FolderEditorEvent.Drop)
        runCurrent()

        assertEquals(listOf(b, c, a), harness.storedFolder("social").appIds)
        assertEquals(listOf(b, c, a), harness.controller.state.value.openFolder?.appIds)
    }

    @Test
    fun `adding and removing an app persists through the repository`() = runTest {
        val harness = harness()
        runCurrent()
        harness.controller.dispatch(FolderEditorEvent.OpenFolder(FolderId("social")))
        harness.controller.dispatch(FolderEditorEvent.AddApp(d))
        runCurrent()
        assertEquals(listOf(a, b, c, d), harness.storedFolder("social").appIds)

        harness.controller.dispatch(FolderEditorEvent.RemoveApp(b))
        runCurrent()
        assertEquals(listOf(a, c, d), harness.storedFolder("social").appIds)
    }

    @Test
    fun `creating a folder persists the folder and its name`() = runTest {
        val harness = harness()
        runCurrent()
        harness.controller.dispatch(FolderEditorEvent.BeginCreateFolder)
        harness.controller.dispatch(FolderEditorEvent.NameChanged("Tools"))
        harness.controller.dispatch(FolderEditorEvent.ConfirmName)
        runCurrent()

        val created = harness.repository.layout.value.folders.first { it.id.value == "tools" }
        assertEquals("Tools", created.name)
        assertTrue(created.appIds.isEmpty())
        assertEquals(FolderId("tools"), harness.controller.state.value.openFolderId)
    }

    @Test
    fun `renaming persists and undo puts the old name back`() = runTest {
        val harness = harness()
        runCurrent()
        harness.controller.dispatch(FolderEditorEvent.OpenFolder(FolderId("social")))
        harness.controller.dispatch(FolderEditorEvent.BeginRename)
        harness.controller.dispatch(FolderEditorEvent.NameChanged("Chat"))
        harness.controller.dispatch(FolderEditorEvent.ConfirmName)
        runCurrent()
        assertEquals("Chat", harness.storedFolder("social").name)

        harness.controller.dispatch(FolderEditorEvent.Undo)
        runCurrent()
        assertEquals("Social", harness.storedFolder("social").name)
        assertEquals("Social", harness.controller.state.value.openFolder?.name)
        assertFalse(harness.controller.state.value.canUndo)
    }

    @Test
    fun `undo of a removal persists the previous order`() = runTest {
        val harness = harness()
        runCurrent()
        harness.controller.dispatch(FolderEditorEvent.OpenFolder(FolderId("social")))
        harness.controller.dispatch(FolderEditorEvent.RemoveApp(b))
        runCurrent()
        assertEquals(listOf(a, c), harness.storedFolder("social").appIds)

        harness.controller.dispatch(FolderEditorEvent.Undo)
        runCurrent()
        assertEquals(listOf(a, b, c), harness.storedFolder("social").appIds)
        assertEquals("Undid: Remove app", harness.controller.state.value.notice)
    }

    @Test
    fun `undone folder creation is reported, because the contract cannot delete a folder`() = runTest {
        val harness = harness()
        runCurrent()
        harness.controller.dispatch(FolderEditorEvent.BeginCreateFolder)
        harness.controller.dispatch(FolderEditorEvent.NameChanged("Tools"))
        harness.controller.dispatch(FolderEditorEvent.ConfirmName)
        runCurrent()
        assertTrue(harness.repository.layout.value.folders.any { it.id.value == "tools" })

        harness.controller.dispatch(FolderEditorEvent.Undo)
        runCurrent()

        assertEquals("Parc cannot delete folders yet", harness.controller.state.value.notice)
        assertTrue(harness.repository.layout.value.folders.any { it.id.value == "tools" })
        assertEquals(
            harness.repository.layout.value.folders,
            harness.controller.state.value.document.folders,
        )
    }

    @Test
    fun `a layout change from outside refreshes the document when nothing is being written`() = runTest {
        val harness = harness()
        runCurrent()
        harness.repository.addToFolder(FolderId("social"), d)
        runCurrent()

        assertEquals(
            listOf(a, b, c, d),
            harness.controller.state.value.document.folder(FolderId("social"))?.appIds,
        )
    }

    @Test
    fun `a refused drop leaves the layout alone and reports the reason`() = runTest {
        val harness = harness(pins = setOf(c))
        runCurrent()
        harness.controller.dispatch(FolderEditorEvent.OpenFolder(FolderId("social")))
        harness.controller.dispatch(FolderEditorEvent.DragStart(0))
        harness.controller.dispatch(FolderEditorEvent.DragTo(2))
        harness.controller.dispatch(FolderEditorEvent.Drop)
        runCurrent()

        assertEquals("Pinned apps stay put", harness.controller.state.value.notice)
        assertEquals(listOf(a, b, c), harness.storedFolder("social").appIds)
        assertFalse(harness.controller.state.value.canUndo)
    }

    @Test
    fun `the label list is the one the controller was given`() = runTest {
        val harness = harness()
        runCurrent()
        harness.appsFlow.value = apps.map { if (it.id == a) it.copy(label = "Renamed") else it }
        runCurrent()
        harness.controller.dispatch(FolderEditorEvent.OpenFolder(FolderId("social")))
        runCurrent()
        val ui = harness.controller.state.value.toUi(harness.appsFlow.value)
        assertEquals("Renamed", ui.slots[0].label)
    }
}
