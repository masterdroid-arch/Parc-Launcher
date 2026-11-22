package com.brightblu.parc

import com.brightblu.parc.feature.search.LabelSearchEngine
import com.brightblu.parc.feature.search.SearchSheetState
import com.brightblu.parc.model.*
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T246: Remove from arc, the empty slot an uninstall leaves, and the one-time new-app prompt. */
@OptIn(ExperimentalCoroutinesApi::class)
class T246Test {
    private val a = AppId("test/A@0")
    private val b = AppId("test/B@0")
    private val c = AppId("test/C@0")
    private val social = FolderId("social")
    private val entryA = AppEntry(a, "App A", "test.a", false, 100L)
    private val entryB = AppEntry(b, "App B", "test.b", false, 200L)
    private val entryC = AppEntry(c, "App C", "test.c", false, 300L)

    private fun arc(id: String, index: Int, capacity: Int = 4, layer: Layer = Layer.Home) =
        ArcSpec(id, index, 120f, 180f, 270f, capacity, layer)

    private fun layout(
        a1: Map<Int, Slot> = emptyMap(),
        a2: Map<Int, Slot> = emptyMap(),
        folder: Map<Int, Slot> = emptyMap(),
        pins: Set<AppId> = emptySet(),
    ) = ParcLayout(
        arcs = listOf(
            ArcAssignment(arc("A1", 0), a1),
            ArcAssignment(arc("A2", 1), a2),
            ArcAssignment(arc("F1", 0, layer = Layer.Folder, capacity = 2), folder),
        ),
        pins = pins,
    )

    // ---- menu rows -------------------------------------------------------------------------

    @Test fun theHomeMenuAddsRemoveFromArcBetweenTheFolderRowAndHide() {
        val home = MenuModel(a, SlotPosition("A1", 0), 0f, 0f, label = "App A", canUninstall = true)
        assertEquals(
            listOf("Pin", "Move to folder", "Remove from arc", "Hide from Parc", "App info", "Uninstall"),
            appMenuRootItems(home).mapNotNull { (it as? MenuItem.Row)?.row?.label },
        )
        assertTrue(appMenuRootItems(home).any {
            (it as? MenuItem.Row)?.row?.action == MenuAction.RemoveFromArc
        })
        // Inside a folder the visible arc is the folder's own; the Home row would be ambiguous.
        val inside = home.copy(inFolder = social)
        assertFalse(appMenuRootItems(inside).any {
            (it as? MenuItem.Row)?.row?.label == "Remove from arc"
        })
    }

    // ---- remove from arc -------------------------------------------------------------------

    @Test fun removingFromAnArcClearsOnlyThatSlotAndItsPin() {
        val before = layout(
            a1 = mapOf(0 to Slot.App(a), 1 to Slot.App(b)),
            a2 = mapOf(0 to Slot.App(c)),
            pins = setOf(a, b),
        )
        val after = requireNotNull(ArcSlotRemoval.remove(before, SlotPosition("A1", 0), a))

        assertEquals(mapOf(1 to Slot.App(b)), after.arcs.first { it.arc.id == "A1" }.slots)
        assertEquals(before.arcs.first { it.arc.id == "A2" }, after.arcs.first { it.arc.id == "A2" })
        assertEquals(setOf(b), after.pins)
        // The gap is at index 0 and B never shifts down into it: that index draws as a "+".
        assertFalse(after.arcs.first { it.arc.id == "A1" }.slots.containsKey(0))
    }

    @Test fun aWrongAppAFolderArcOrAnUnknownSlotChangesNothing() {
        val before = layout(
            a1 = mapOf(0 to Slot.App(a)),
            folder = mapOf(0 to Slot.App(b)),
            pins = setOf(a),
        )
        assertNull(ArcSlotRemoval.remove(before, SlotPosition("A1", 0), b))
        assertNull(ArcSlotRemoval.remove(before, SlotPosition("F1", 0), b))
        assertNull(ArcSlotRemoval.remove(before, SlotPosition("A9", 0), a))
        assertNull(ArcSlotRemoval.remove(before, SlotPosition("A1", 9), a))
    }

    @Test fun theSessionRemovesThePressedSlotThroughTheUndoHost() = runTest {
        val repository = FakeLayoutRepository(layout(
            a1 = mapOf(0 to Slot.App(a), 1 to Slot.App(b)), pins = setOf(a),
        ))
        val edit = RingEditSession(repository, this)
        val session = session(repository, edit)
        session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(a), 0f, 0f))
        session.menuAction(MenuAction.RemoveFromArc, "")
        runCurrent()

        assertEquals(mapOf(1 to Slot.App(b)), repository.layout.value.arcs.first { it.arc.id == "A1" }.slots)
        assertFalse(a in repository.layout.value.pins)
        assertTrue(edit.undoAvailable)
        assertEquals("Removed from arc", edit.undoLabel)

        edit.undo()
        runCurrent()
        assertEquals(mapOf(0 to Slot.App(a), 1 to Slot.App(b)),
            repository.layout.value.arcs.first { it.arc.id == "A1" }.slots)
        assertEquals(setOf(a), repository.layout.value.pins)
    }

    // ---- new app prompt --------------------------------------------------------------------

    @Test fun onlyAnUnseenInstallAfterTheBaselineThatWaitsInNewCanPrompt() {
        val seen = AppEntry(AppId("test/Seen@0"), "Seen", "test.seen", false, 500L)
        val hidden = AppEntry(AppId("test/Hidden@0"), "Hidden", "test.hidden", false, 400L)
        val placed = AppEntry(AppId("test/Placed@0"), "Placed", "test.placed", false, 350L)
        val fresh = entryB
        val beforeBaseline = entryA
        val notInNew = entryC
        val system = AppEntry(AppId("test/System@0"), "System", "test.system", true, 600L)
        val installed = listOf(beforeBaseline, fresh, notInNew, seen, hidden, placed, system)

        val candidate = NewAppPrompt.candidate(
            installed = installed,
            newFolderIds = setOf(beforeBaseline.id, fresh.id, seen.id, hidden.id, placed.id, system.id),
            seen = setOf(seen.id.value),
            since = 150L,
            hidden = setOf(hidden.id),
            placed = setOf(placed.id),
        )

        assertEquals(fresh, candidate)
        assertEquals("Add App B to an arc?", NewAppPrompt.question(fresh.label))
        assertNull(NewAppPrompt.candidate(installed, emptySet(), emptySet(), 150L))
        assertNull(NewAppPrompt.candidate(installed, setOf(beforeBaseline.id), emptySet(), 150L))
    }

    @Test fun anInstallExactlyAtTheBaselineCountsAsNew() {
        assertEquals(entryB, NewAppPrompt.candidate(listOf(entryB), setOf(entryB.id), emptySet(), entryB.installTime))
    }

    // ---- session fixture -------------------------------------------------------------------

    private class Index(entries: List<AppEntry>) : AppIndex {
        override val apps = MutableStateFlow(entries)
        override fun icon(id: AppId, sizePx: Int): android.graphics.Bitmap? = null
        override fun launch(id: AppId) = Unit
        override fun shortcuts(id: AppId) = emptyList<AppShortcut>()
    }

    private class Usage : UsageRepository {
        override fun recordLaunch(id: AppId) = Unit
        override fun recents(limit: Int): Flow<List<AppId>> = flowOf(emptyList())
        override fun rank(folderId: FolderId): List<AppId> = emptyList()
    }

    private class Shortcuts : ShortcutHost {
        override fun isDefaultHome() = true
        override fun launchShortcut(appId: AppId, shortcutId: String) = Unit
    }

    private fun session(layout: LayoutRepository, edit: RingEditSession): LauncherSession {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val index = Index(listOf(entryA, entryB, entryC))
        val search = SearchSheetState(LabelSearchEngine(), index, Usage(), scope)
        return LauncherSession(ShellNavigation(), index, Usage(), layout, search, {}, {}, Shortcuts(), scope,
            UnconfinedTestDispatcher(), layoutEditing = edit)
    }
}
