package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.FolderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The editor state machine: create, rename, add, remove, move, undo, page handling. */
class FolderEditorMachineTest {

    private val a = testId("a")
    private val b = testId("b")
    private val c = testId("c")
    private val d = testId("d")
    private val social = testFolder("social", "Social", listOf(a, b, c))

    private fun open(drag: DragState? = null) = testState(
        document = FolderDocument(listOf(social)),
        page = EditorPage.Contents,
        openFolderId = FolderId("social"),
        drag = drag,
    )

    private fun FolderEditorState.run(vararg events: FolderEditorEvent): FolderEditorState =
        events.fold(this) { state, event -> FolderEditorMachine.reduce(state, event) }

    @Test fun `valid rename auto saves without closing the field and rejects invalid drafts`() {
        val editing = open().run(FolderEditorEvent.BeginRename, FolderEditorEvent.NameChanged("Friends"), FolderEditorEvent.ApplyNameDraft)
        assertEquals("Friends", editing.openFolder?.name)
        assertEquals("Friends", editing.prompt?.draft)
        val completed = editing.run(FolderEditorEvent.ConfirmName)
        assertEquals(editing.undo.size, completed.undo.size)
        assertNull(completed.prompt)
        val leaving = editing.run(FolderEditorEvent.NameChanged("Family"), FolderEditorEvent.CloseFolder)
        assertEquals("Family", leaving.document.folder(social.id)?.name)
        val invalid = editing.run(FolderEditorEvent.NameChanged(""), FolderEditorEvent.ApplyNameDraft)
        assertEquals("Friends", invalid.openFolder?.name)
        assertNotNull(invalid.prompt?.issue)
        val creating = open().run(FolderEditorEvent.BeginCreateFolder, FolderEditorEvent.NameChanged("New group"), FolderEditorEvent.ApplyNameDraft)
        assertEquals(1, creating.document.folders.size)
    }

    // region folders

    @Test
    fun `opening a folder shows its slots and opening nothing keeps the list`() {
        val list = testState(document = FolderDocument(listOf(social)))
        val opened = list.run(FolderEditorEvent.OpenFolder(FolderId("social")))
        assertEquals(EditorPage.Contents, opened.page)
        assertEquals(FolderId("social"), opened.openFolderId)
        assertEquals(social, opened.openFolder)
        assertEquals(18, opened.grid?.capacity)

        val unknown = list.run(FolderEditorEvent.OpenFolder(FolderId("nope")))
        assertEquals(EditorPage.Folders, unknown.page)
        assertNull(unknown.openFolderId)
        assertNull(unknown.grid)
    }

    @Test
    fun `back leaves the picker first, then the folder`() {
        val picker = open().run(
            FolderEditorEvent.OpenAppPicker,
            FolderEditorEvent.CloseFolder,
        )
        assertEquals(EditorPage.Contents, picker.page)
        assertEquals(FolderId("social"), picker.openFolderId)

        val list = picker.run(FolderEditorEvent.CloseFolder)
        assertEquals(EditorPage.Folders, list.page)
        assertNull(list.openFolderId)
        assertNull(list.grid)
    }

    @Test
    fun `creating a folder names it, opens it and offers one undo step`() {
        val state = testState().run(
            FolderEditorEvent.BeginCreateFolder,
            FolderEditorEvent.NameChanged(" Social "),
            FolderEditorEvent.ConfirmName,
        )
        assertEquals(EditorPage.Contents, state.page)
        assertEquals(FolderId("social"), state.openFolderId)
        assertEquals("Social", state.openFolder?.name)
        assertEquals(emptyList<AppId>(), state.openFolder?.appIds)
        assertEquals("Folder created", state.notice)
        assertEquals(1, state.undo.size)
        assertEquals("New folder", state.undoLabel)
        assertNull(state.prompt)
    }

    @Test
    fun `a refused name keeps the field open with the reason`() {
        val tooLong = testState().run(
            FolderEditorEvent.BeginCreateFolder,
            FolderEditorEvent.NameChanged("a name that is far too long"),
            FolderEditorEvent.ConfirmName,
        )
        assertEquals("At most 8 characters", tooLong.prompt?.issue)
        assertTrue(tooLong.document.folders.isEmpty())
        assertFalse(tooLong.canUndo)

        val taken = testState(document = FolderDocument(listOf(social))).run(
            FolderEditorEvent.BeginCreateFolder,
            FolderEditorEvent.NameChanged("social"),
            FolderEditorEvent.ConfirmName,
        )
        assertEquals("That name is already used", taken.prompt?.issue)
        assertEquals(1, taken.document.folders.size)
    }

    @Test
    fun `a key already in use gets a suffix, and a cancelled prompt changes nothing`() {
        val taken = testState(document = FolderDocument(listOf(testFolder("social-2", "Chat", emptyList()))))
        val created = taken.run(
            FolderEditorEvent.BeginCreateFolder,
            FolderEditorEvent.NameChanged("Social 2"),
            FolderEditorEvent.ConfirmName,
        )
        assertEquals(FolderId("social-2-2"), created.openFolderId)

        val cancelled = testState().run(
            FolderEditorEvent.BeginCreateFolder,
            FolderEditorEvent.NameChanged("Tools"),
            FolderEditorEvent.CancelName,
        )
        assertNull(cancelled.prompt)
        assertTrue(cancelled.document.folders.isEmpty())
        assertFalse(cancelled.canUndo)
    }

    @Test
    fun `renaming keeps the id, the members and the folder open`() {
        val renamed = open().run(
            FolderEditorEvent.BeginRename,
            FolderEditorEvent.NameChanged("Chat"),
            FolderEditorEvent.ConfirmName,
        )
        assertEquals(FolderId("social"), renamed.openFolderId)
        assertEquals("Chat", renamed.openFolder?.name)
        assertEquals(listOf(a, b, c), renamed.openFolder?.appIds)
        assertEquals("Rename", renamed.undoLabel)
    }

    @Test
    fun `renaming away from a folder list is refused`() {
        val list = testState(document = FolderDocument(listOf(social)))
        assertEquals(list, list.run(FolderEditorEvent.BeginRename))
    }

    // endregion

    // region members

    @Test
    fun `adding an app appends it in slot order`() {
        val state = open().run(FolderEditorEvent.AddApp(d))
        assertEquals(listOf(a, b, c, d), state.openFolder?.appIds)
        assertEquals("Add app", state.undoLabel)
        assertEquals(1, state.undo.size)
    }

    @Test
    fun `adding an app twice changes nothing and adds no undo step`() {
        val state = open().run(FolderEditorEvent.AddApp(a))
        assertEquals(open(), state)
        assertFalse(state.canUndo)
    }

    @Test
    fun `adding to a full folder is refused with a notice`() {
        val full = testState(
            document = FolderDocument(listOf(testFolder("social", "Social", (1..18).map { testId("app$it") }))),
            page = EditorPage.Contents,
            openFolderId = FolderId("social"),
        )
        val state = full.run(FolderEditorEvent.AddApp(testId("extra")))
        assertEquals("This folder is full", state.notice)
        assertEquals(full.document, state.document)
        assertFalse(state.canUndo)
    }

    @Test
    fun `removing an app drops it and keeps the order`() {
        val state = open().run(FolderEditorEvent.RemoveApp(b))
        assertEquals(listOf(a, c), state.openFolder?.appIds)
        assertEquals("Remove app", state.undoLabel)
    }

    @Test
    fun `removing an app that is not there changes nothing`() {
        assertEquals(open(), open().run(FolderEditorEvent.RemoveApp(d)))
    }

    @Test
    fun `the picker only opens on an open folder`() {
        assertEquals(testState(), testState().run(FolderEditorEvent.OpenAppPicker))
        assertEquals(
            EditorPage.AppPicker,
            open().run(FolderEditorEvent.OpenAppPicker).page,
        )
    }

    // endregion

    // region drag

    @Test
    fun `a drag that starts and ends on a slot reorders the folder`() {
        val state = open(drag = DragState(0, 2)).run(FolderEditorEvent.Drop)
        assertEquals(listOf(b, c, a), state.openFolder?.appIds)
        assertEquals("Move app", state.undoLabel)
        assertNull(state.drag)
        assertNull(state.notice)
    }

    @Test
    fun `dropping on a pinned app is refused with the notice and no document change`() {
        val pinned = testState(
            document = FolderDocument(listOf(social), pins = setOf(c)),
            page = EditorPage.Contents,
            openFolderId = FolderId("social"),
            drag = DragState(0, 2),
        )
        val state = pinned.run(FolderEditorEvent.Drop)
        assertEquals("Pinned apps stay put", state.notice)
        assertEquals(pinned.document, state.document)
        assertFalse(state.canUndo)
        assertNull(state.drag)
    }

    @Test
    fun `an empty source is reported as nothing to move`() {
        val state = open(drag = DragState(10, 0)).run(FolderEditorEvent.Drop)
        assertEquals("Nothing to move", state.notice)
        assertEquals(open(), state.copy(notice = null))
    }

    @Test
    fun `drag events outside the grid are ignored and a cancel drops the drag`() {
        val started = open().run(FolderEditorEvent.DragStart(0))
        assertEquals(DragState(0, 0), started.drag)
        assertEquals(started, started.run(FolderEditorEvent.DragStart(18)))

        val dragged = started.run(FolderEditorEvent.DragTo(20))
        assertEquals(DragState(0, 17), dragged.drag)

        val cancelled = dragged.run(FolderEditorEvent.DragCancel)
        assertNull(cancelled.drag)
        assertNull(cancelled.run(FolderEditorEvent.Drop).drag)
    }

    @Test
    fun `a drag can start on an empty slot and is refused at the drop`() {
        val state = open().run(FolderEditorEvent.DragStart(5), FolderEditorEvent.DragTo(0), FolderEditorEvent.Drop)
        assertEquals("Nothing to move", state.notice)
    }

    @Test
    fun `a drag without a start is not a drop`() {
        assertEquals(open(), open().run(FolderEditorEvent.Drop))
    }

    // endregion

    // region undo

    @Test
    fun `undo walks back through every edit`() {
        val original = open()
        val edited = original.run(
            FolderEditorEvent.RemoveApp(b),
            FolderEditorEvent.AddApp(d),
        )
        assertEquals(listOf(a, c, d), edited.openFolder?.appIds)
        assertEquals(2, edited.undo.size)

        val once = edited.run(FolderEditorEvent.Undo)
        assertEquals(listOf(a, c), once.openFolder?.appIds)
        assertEquals("Undid: Add app", once.notice)

        val twice = once.run(FolderEditorEvent.Undo)
        assertEquals(original.document, twice.document)
        assertFalse(twice.canUndo)
        assertEquals("Undid: Remove app", twice.notice)
    }

    @Test
    fun `undo with an empty stack does nothing`() {
        assertEquals(open(), open().run(FolderEditorEvent.Undo))
    }

    @Test
    fun `undoing a creation returns to the folder list`() {
        val created = testState().run(
            FolderEditorEvent.BeginCreateFolder,
            FolderEditorEvent.NameChanged("Tools"),
            FolderEditorEvent.ConfirmName,
        )
        val undone = created.run(FolderEditorEvent.Undo)
        assertTrue(undone.document.folders.isEmpty())
        assertEquals(EditorPage.Folders, undone.page)
        assertNull(undone.openFolderId)
    }

    @Test
    fun `the undo stack is bounded`() {
        val apps = (1..40).map { testId("app$it") }
        var state = FolderEditorState(
            arcCapacities = listOf(64),
            document = FolderDocument(listOf(testFolder("social", "Social", emptyList()))),
            page = EditorPage.Contents,
            openFolderId = FolderId("social"),
        )
        apps.forEach { state = state.run(FolderEditorEvent.AddApp(it)) }
        assertEquals(FolderEditorMachine.MaxUndoSteps, state.undo.size)
        assertEquals(40, state.openFolder?.appIds?.size)
        assertTrue(state.canUndo)
    }

    // endregion
}
