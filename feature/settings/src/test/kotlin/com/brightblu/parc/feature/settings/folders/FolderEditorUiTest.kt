package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.FolderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The list projection: labels, empty slots, drag markers and the picker. */
class FolderEditorUiTest {

    private val a = testId("a")
    private val b = testId("b")
    private val c = testId("c")
    private val gone = testId("gone")
    private val hidden = testId("hidden")
    private val maps = testId("maps")

    private val apps: List<AppEntry> = listOf(
        testEntry(a, "Alpha"),
        testEntry(b, "Beta"),
        testEntry(c, "Charlie"),
        testEntry(maps, "Maps"),
        testEntry(hidden, "Zebra"),
    )

    private val social = testFolder("social", "Social", listOf(a, b, c, gone))

    private fun state(
        page: EditorPage = EditorPage.Contents,
        drag: DragState? = null,
        pins: Set<AppId> = setOf(b),
    ) = FolderEditorState(
        arcCapacities = TestCapacities,
        document = FolderDocument(listOf(social), pins, setOf(hidden)),
        page = page,
        openFolderId = FolderId("social"),
        drag = drag,
        notice = "Note",
        undo = listOf(UndoStep(FolderDocument(), "Move app")),
    )

    @Test
    fun `slots cover the grid, resolve labels and mark a missing app`() {
        val ui = state().toUi(apps)
        assertEquals(18, ui.slots.size)
        assertEquals("Social", ui.title)
        assertEquals("Alpha", ui.slots[0].label)
        assertEquals(0, ui.slots[0].arcIndex)
        assertFalse(ui.slots[0].pinned)
        assertTrue(ui.slots[1].pinned)
        assertEquals("Charlie", ui.slots[2].label)
        assertEquals("", ui.slots[3].label)
        assertTrue(ui.slots[3].unknown)
        assertEquals(gone, ui.slots[3].appId)
        assertNull(ui.slots[4].appId)
        assertFalse(ui.slots[4].unknown)
        assertEquals("", ui.slots[4].label)
        assertEquals(1, ui.slots[4].arcIndex)
        assertFalse(ui.slots[4].dragged)
        assertFalse(ui.slots[4].dropTarget)
    }

    @Test
    fun `a drag marks the held row, the target and a refused target`() {
        val ui = state(drag = DragState(0, 1)).toUi(apps)
        assertTrue(ui.slots[0].dragged)
        assertFalse(ui.slots[0].dropTarget)
        assertTrue(ui.slots[1].dropTarget)
        assertTrue(ui.slots[1].refusesDrop)
        assertFalse(ui.slots[2].refusesDrop)
        assertFalse(ui.slots[2].dropTarget)
    }

    @Test
    fun `an allowed target is highlighted without the refused mark`() {
        val ui = state(drag = DragState(0, 4)).toUi(apps)
        assertTrue(ui.slots[4].dropTarget)
        assertFalse(ui.slots[4].refusesDrop)
    }

    @Test
    fun `the picker leaves out hidden apps, sorts by label and flags members`() {
        val ui = state(page = EditorPage.AppPicker).toUi(apps)
        assertEquals(listOf("Alpha", "Beta", "Charlie", "Maps"), ui.picker.map { it.label })
        assertTrue(ui.picker[0].inFolder)
        assertFalse(ui.picker[3].inFolder)
        assertFalse(ui.picker.any { it.appId == hidden })
        assertEquals("Add to Social", ui.title)
    }

    @Test
    fun `the folder list shows one row per folder with its size`() {
        val ui = state(page = EditorPage.Folders)
            .copy(openFolderId = null)
            .toUi(apps)
        assertEquals(1, ui.folders.size)
        assertEquals(FolderId("social"), ui.folders[0].id)
        assertEquals("Social", ui.folders[0].name)
        assertEquals(4, ui.folders[0].appCount)
        assertEquals("Folders", ui.title)
        assertTrue(ui.slots.isEmpty())
    }

    @Test
    fun `prompt copy differs between create and rename`() {
        val create = state().copy(prompt = NamePrompt(folderId = null, draft = "Tools")).toUi(apps)
        assertEquals("New folder", create.prompt?.title)
        assertEquals("Add", create.prompt?.confirmLabel)
        assertEquals("Tools", create.prompt?.draft)

        val rename = state().copy(
            prompt = NamePrompt(FolderId("social"), "Chat", issue = "That name is already used"),
        ).toUi(apps)
        assertEquals("Rename folder", rename.prompt?.title)
        assertEquals("Done", rename.prompt?.confirmLabel)
        assertEquals("That name is already used", rename.prompt?.issue)
    }

    @Test
    fun `notice, undo and fullness are passed through`() {
        val ui = state().toUi(apps)
        assertEquals("Note", ui.notice)
        assertTrue(ui.canUndo)
        assertEquals("Move app", ui.undoLabel)
        assertFalse(ui.folderIsFull)

        val full = FolderEditorState(
            arcCapacities = listOf(2),
            document = FolderDocument(listOf(testFolder("social", "Social", listOf(a, b)))),
            page = EditorPage.Contents,
            openFolderId = FolderId("social"),
        ).toUi(apps)
        assertTrue(full.folderIsFull)
        assertEquals(2, full.slots.size)
    }

    @Test
    fun `a suspended app is dimmed in the slots and the picker`() {
        val suspended = apps.map { if (it.id == a) it.copy(suspended = true) else it }
        val ui = state().toUi(suspended)
        assertTrue(ui.slots[0].suspended)
        assertFalse(ui.slots[1].suspended)
        assertTrue(ui.picker.first { it.appId == a }.suspended)
    }

    @Test
    fun `an unknown app in a folder is still a row, so a reorder cannot lose it`() {
        val ui = state().toUi(emptyList())
        assertEquals(4, ui.slots.count { it.appId != null })
        ui.slots.filter { it.appId != null }.forEach { assertTrue(it.unknown) }
        assertTrue(ui.slots.filter { it.appId != null }.none { it.suspended })
    }
}
