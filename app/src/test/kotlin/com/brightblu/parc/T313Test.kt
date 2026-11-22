package com.brightblu.parc

import com.brightblu.parc.feature.search.LabelSearchEngine
import com.brightblu.parc.feature.search.SearchSheetState
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.LayoutJson
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotHit
import com.brightblu.parc.model.SlotPosition
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import com.brightblu.parc.ring.RingLayout
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

/** T313: All-apps placement through the shared chooser, no duplicates, clearer Remove and Unpin. */
@OptIn(ExperimentalCoroutinesApi::class)
class T313Test {
    private val geometry = RingLayout(384f, 853f)
    private val a = AppId("test/A@0")
    private val b = AppId("test/B@0")
    private val entryA = AppEntry(a, "App A", "test.a", false, 0L)

    private fun arc(id: String, index: Int, capacity: Int = 4, layer: Layer = Layer.Home) =
        ArcSpec(id, index, 120f, 180f, 270f, capacity, layer)

    private fun layout(
        a1: Map<Int, Slot> = emptyMap(),
        a2: Map<Int, Slot> = emptyMap(),
        a3: Map<Int, Slot> = emptyMap(),
        a4: Map<Int, Slot> = emptyMap(),
        pins: Set<AppId> = emptySet(),
    ) = ParcLayout(
        arcs = listOf(
            ArcAssignment(arc("A1", 0), a1),
            ArcAssignment(arc("A2", 1), a2),
            ArcAssignment(arc("A3", 2), a3),
            ArcAssignment(arc("A4", 3), a4),
        ),
        folders = emptyList(),
        pins = pins,
    )

    private fun rows(items: List<MenuItem>): List<String> =
        items.mapNotNull { (it as? MenuItem.Row)?.row?.label }

    private fun actions(items: List<MenuItem>): List<MenuAction> =
        items.mapNotNull { (it as? MenuItem.Row)?.row?.action }

    // ---- labels and the All-apps ring menu ---------------------------------------------------

    @Test fun theAddToArcLabelReadsAddOrMoveByPlacement() {
        assertEquals("Add to arc", addToArcLabel(placed = false))
        assertEquals("Move to arc", addToArcLabel(placed = true))
    }

    @Test fun theAllAppsRingOffersAddToArcAndNoPinRow() {
        // The ring is not Home and not a folder: onArc=false, inFolder=null.
        val menu = MenuModel(a, SlotPosition("pending", 0), 200f, 700f, label = "App A",
            onArc = false, inFolder = null, placed = false)
        assertEquals(listOf("Add to arc", "Move to folder", "Hide from Parc", "App info", "Uninstall"),
            rows(appMenuRootItems(menu)))
        assertFalse(rows(appMenuRootItems(menu)).contains("Pin"))
        assertFalse(rows(appMenuRootItems(menu)).contains("Remove from arc"))
    }

    @Test fun anAppAlreadyOnHomeReadsMoveToArcOnTheRing() {
        val menu = MenuModel(a, SlotPosition("pending", 0), 200f, 700f, label = "App A",
            onArc = false, inFolder = null, placed = true)
        assertEquals("Move to arc", rows(appMenuRootItems(menu)).first())
        assertTrue(actions(appMenuRootItems(menu)).contains(MenuAction.AddToArc))
    }

    @Test fun aPinnedAppReadsClearPinKeepOnArcOnHome() {
        val menu = MenuModel(a, SlotPosition("A1", 0), 200f, 700f, label = "App A", pinned = true)
        assertEquals("Clear pin, keep on arc", rows(appMenuRootItems(menu)).first())
        assertEquals(MenuAction.TogglePin, actions(appMenuRootItems(menu)).first())
    }

    @Test fun theSessionRoutesTheRingMenuThroughTheChooserAndMarksPlacement() {
        val repository = FakeLayoutRepository(layout(a1 = mapOf(0 to Slot.App(a))))
        val index = Index(listOf(entryA))
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val session = LauncherSession(ShellNavigation(), index, Usage(), repository,
            SearchSheetState(LabelSearchEngine(), index, Usage(), scope), {}, {}, Shortcuts(), scope,
            UnconfinedTestDispatcher(),
            onAddToArc = { dispatched = it },
            layoutEditing = RingEditSession(repository, scope))
        // Show the All-apps ring, then long-press the app already on Home.
        session.onHubBack()
        assertEquals(Layer.AllApps, session.navigation.state.value.layer)
        session.onLongPress(SlotHit(SlotPosition("pending", 0), Slot.App(a), 200f, 700f))
        val menu = requireNotNull(session.menu.value)
        assertFalse(menu.onArc)
        assertTrue(menu.placed)
        assertEquals("Move to arc", rows(appMenuRootItems(menu)).first())

        session.menuAction(MenuAction.AddToArc, "")
        assertNull(session.menu.value)
        assertEquals(a, dispatched)
    }

    private var dispatched: AppId? = null

    // ---- placement: move, repeated request, full destination --------------------------------

    @Test fun placementMovesAnAppAndNeverCreatesASecondCopy() {
        val value = layout(a1 = mapOf(0 to Slot.App(a)))
        val placed = ArcChooser.placement(value, a, "A3", ArcMaximums.Auto, null)!!
        assertFalse(placed.layout.arcs.first { it.arc.id == "A1" }.slots.containsValue(Slot.App(a)))
        assertEquals(Slot.App(a), placed.layout.arcs.first { it.arc.id == "A3" }.slots[0])
    }

    @Test fun aRepeatedRequestToTheSameArcKeepsTheSpotAndWritesNothing() {
        val value = layout(a1 = mapOf(0 to Slot.App(a)))
        val placed = ArcChooser.placement(value, a, "A1", ArcMaximums.Auto, null)!!
        assertEquals(value, placed.layout)
        assertEquals(0, placed.index)
    }

    @Test fun aMoveToAFullArcFailsAndLeavesTheOriginalSpotUntouched() {
        val full = layout(a3 = (0 until 4).associateWith { Slot.App(AppId("test/f$it@0")) })
        val value = full.copy(arcs = full.arcs.map {
            if (it.arc.id == "A1") it.copy(slots = mapOf(0 to Slot.App(a))) else it
        })
        // The destination is full: the write is refused and A1 keeps its single copy.
        assertNull(ArcChooser.placement(value, a, "A3", ArcMaximums.Auto, null))
        assertEquals(Slot.App(a), value.arcs.first { it.arc.id == "A1" }.slots[0])
    }

    // ---- removal and undo --------------------------------------------------------------------

    @Test fun removeFromArcFreesTheSpotAndClearsThePin() {
        val value = layout(a1 = mapOf(0 to Slot.App(a), 1 to Slot.App(b)), pins = setOf(a, b))
        val after = requireNotNull(ArcSlotRemoval.remove(value, SlotPosition("A1", 0), a))
        assertEquals(mapOf(1 to Slot.App(b)), after.arcs.first { it.arc.id == "A1" }.slots)
        assertEquals(setOf(b), after.pins)
    }

    @Test fun aMoveThroughTheChooserIsUndoableViaTheEditHost() = runTest {
        val repository = FakeLayoutRepository(layout(a1 = mapOf(0 to Slot.App(a))))
        val edit = RingEditSession(repository, this)
        val before = repository.layout.value
        edit.change("Added to Inner arc") {
            val placed = ArcChooser.placement(repository.layout.value, a, "A3", ArcMaximums.Auto, null)!!
            repository.importLayout(LayoutJson.encode(placed.layout))
        }
        runCurrent()
        assertTrue(edit.undoAvailable)
        assertNull(repository.layout.value.arcs.first { it.arc.id == "A1" }.slots[0])

        edit.undo()
        runCurrent()
        assertEquals(before, repository.layout.value)
        assertEquals(Slot.App(a), repository.layout.value.arcs.first { it.arc.id == "A1" }.slots[0])
    }

    // ---- fixtures ---------------------------------------------------------------------------

    private class Index(entries: List<AppEntry>) : com.brightblu.parc.model.AppIndex {
        override val apps = MutableStateFlow(entries)
        override fun icon(id: AppId, sizePx: Int): android.graphics.Bitmap? = null
        override fun launch(id: AppId) = Unit
        override fun shortcuts(id: AppId) = emptyList<com.brightblu.parc.model.AppShortcut>()
    }

    private class Usage : com.brightblu.parc.model.UsageRepository {
        override fun recordLaunch(id: AppId) = Unit
        override fun recents(limit: Int): Flow<List<AppId>> = flowOf(emptyList())
        override fun rank(folderId: FolderId): List<AppId> = emptyList()
    }

    private class Shortcuts : ShortcutHost {
        override fun isDefaultHome(): Boolean = true
        override fun launchShortcut(appId: AppId, shortcutId: String) = Unit
    }
}
