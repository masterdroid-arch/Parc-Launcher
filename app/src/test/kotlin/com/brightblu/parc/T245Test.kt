package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.BackupValueType
import com.brightblu.parc.feature.search.LabelSearchEngine
import com.brightblu.parc.feature.search.SearchSheetState
import com.brightblu.parc.model.*
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

/** T245: one undo history for every Home layout change, and the Lock layout switch. */
@OptIn(ExperimentalCoroutinesApi::class)
class T245Test {
    private val geometry = RingLayout(384f, 853f)
    private val a = AppId("test/A@0")
    private val b = AppId("test/B@0")
    private val entryA = AppEntry(a, "App A", "test.a", false, 0)
    private val entryB = AppEntry(b, "App B", "test.b", false, 0)
    private fun position(index: Int) = SlotPosition(geometry.arcs[0].id, index)

    private fun layout(vararg slots: Pair<Int, Slot>) = ParcLayout(arcs = geometry.arcs.map {
        ArcAssignment(it, if (it.index == 0) mapOf(*slots) else emptyMap())
    })

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
        val index = Index(listOf(entryA, entryB))
        val search = SearchSheetState(LabelSearchEngine(), index, Usage(), scope)
        return LauncherSession(ShellNavigation(), index, Usage(), layout, search, {}, {}, Shortcuts(), scope,
            UnconfinedTestDispatcher(), layoutEditing = edit)
    }

    @Test fun historyUndoesNewestFirstAndKeepsTheEarlierLabel() {
        val history = LayoutUndoHistory()
        val first = layout(0 to Slot.App(a))
        val second = first.copy(hidden = setOf(b))
        assertFalse(history.available)
        assertEquals("", history.label)
        history.record(first, "App added")
        history.record(second, "Hidden")
        assertTrue(history.available)
        assertEquals("Hidden", history.label)
        assertEquals(2, history.depth)
        assertEquals(second, history.undo())
        assertEquals("App added", history.label)
        assertEquals(first, history.undo())
        assertFalse(history.available)
        assertNull(history.undo())
    }

    @Test fun theHistoryIsBounded() {
        val history = LayoutUndoHistory(maxSteps = 2)
        val zero = layout()
        history.record(zero, "One")
        history.record(zero.copy(hidden = setOf(a)), "Two")
        history.record(zero.copy(hidden = setOf(b)), "Three")
        assertEquals(2, history.depth)
        assertEquals("Three", history.label)
        assertEquals(zero.copy(hidden = setOf(b)), history.undo())
        assertEquals(zero.copy(hidden = setOf(a)), history.undo())
        assertFalse(history.available)
    }

    @Test fun lockedHomeExplainsInsteadOfOpeningAndRefusesGuardedChanges() = runTest {
        val repository = FakeLayoutRepository(layout())
        val edit = RingEditSession(repository, this) { true }
        edit.enter()
        assertFalse(edit.enabled)
        assertEquals(LayoutLockText.Notice, edit.notice)
        assertTrue(edit.notice.contains("Settings > Home"))

        edit.change("Hidden") { repository.hide(a) }
        runCurrent()
        assertTrue(repository.layout.value.hidden.isEmpty())

        // A folder-only change is still allowed while the arcs are locked; only the arcs are frozen.
        edit.change("Pinned", guarded = false) { repository.pin(a, position(0)) }
        runCurrent()
        assertEquals(setOf(a), repository.layout.value.pins)
    }

    @Test fun everyChangeInTheEditSessionStaysUndoableUntilItIsClosed() = runTest {
        val repository = FakeLayoutRepository(layout(0 to Slot.App(a)))
        val edit = RingEditSession(repository, this)
        edit.enter()
        edit.change("App added") { repository.pin(b, position(1)) }
        edit.change("Hidden") { repository.hide(a) }
        assertTrue(edit.undoAvailable)
        assertEquals("Hidden", edit.undoLabel)
        assertEquals(3, edit.undoRevision)

        edit.undo()
        runCurrent()
        assertTrue(edit.undoAvailable)
        assertEquals("App added", edit.undoLabel)
        assertFalse(a in repository.layout.value.hidden)
        assertEquals(setOf(b), repository.layout.value.pins)

        edit.undo()
        runCurrent()
        assertFalse(edit.undoAvailable)
        assertTrue(repository.layout.value.pins.isEmpty())
        assertNull(repository.layout.value.arcs.first { it.arc.id == geometry.arcs[0].id }.slots[1])

        edit.close()
        assertFalse(edit.undoAvailable)
        assertFalse(edit.enabled)
    }

    @Test fun aMenuLayoutChangeGoesThroughTheSameHistoryAndTheChipExpires() = runTest {
        val repository = FakeLayoutRepository(layout(0 to Slot.App(a)))
        val edit = RingEditSession(repository, this)
        val session = session(repository, edit)
        session.onLongPress(SlotHit(position(0), Slot.App(a), 0f, 0f))
        session.menuAction(MenuAction.Hide, "")
        runCurrent()
        assertTrue(a in repository.layout.value.hidden)
        assertTrue(edit.undoAvailable)
        assertEquals("Hidden", edit.undoLabel)

        edit.undo()
        runCurrent()
        assertFalse(a in repository.layout.value.hidden)
        assertFalse(edit.undoAvailable)

        // The host clears the chip after its few seconds; a new change starts it again.
        session.onLongPress(SlotHit(position(0), Slot.App(a), 0f, 0f))
        session.menuAction(MenuAction.Hide, "")
        runCurrent()
        assertTrue(edit.undoAvailable)
        edit.clearUndo()
        assertFalse(edit.undoAvailable)
        assertEquals("", edit.undoLabel)
    }

    @Test fun lockLayoutIsTheHomeArcSwitchAndIsBackedUp() {
        val def = SettingsRegistry.byId.getValue("LockLayout")
        assertEquals("Lock layout", def.title)
        assertEquals(SettingsPage.HomeArcs, def.page)
        assertEquals(SettingControl.Switch, def.control)
        assertEquals(SettingEffect.Logic, def.effect)
        assertEquals(0, def.read!!(SettingsSnapshot()))
        assertEquals(1, def.read!!(SettingsSnapshot(layoutLocked = true)))
        assertTrue(def.write!!(SettingsSnapshot(), 1).layoutLocked)
        assertEquals(BackupValueType.Bool, BackupSpecs.uiPrefs.key("lock_layout")!!.type)
    }
}
