package com.brightblu.parc

import com.brightblu.parc.core.data.profiles.LaunchOutcome
import com.brightblu.parc.feature.search.*
import com.brightblu.parc.model.*
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import com.brightblu.parc.ring.RingLayout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherIntegrationTest {
    private val id = AppId("example/.Main@0")
    private val folderId = FolderId("social")
    private val entry = AppEntry(id, "Example", "example", false, 0)
    private class Index(entries: List<AppEntry>) : AppIndex {
        override val apps = MutableStateFlow(entries)
        val launched = mutableListOf<AppId>()
        var shortcutsResult: List<AppShortcut> = emptyList()
        override fun icon(id: AppId, sizePx: Int): android.graphics.Bitmap? = null
        override fun launch(id: AppId) { launched += id }
        override fun shortcuts(id: AppId) = shortcutsResult
    }
    private class Usage : UsageRepository {
        val recorded = mutableListOf<AppId>()
        override fun recordLaunch(id: AppId) { recorded += id }
        override fun recents(limit: Int) = flowOf(emptyList<AppId>())
        override fun rank(folderId: FolderId) = emptyList<AppId>()
    }

    private class Shortcuts(var defaultHome: Boolean = true) : ShortcutHost {
        val launched = mutableListOf<Pair<AppId, String>>()
        override fun isDefaultHome(): Boolean = defaultHome
        override fun launchShortcut(appId: AppId, shortcutId: String) { launched += appId to shortcutId }
    }

    @Test fun backAndHostPauseEndEditingWithoutChangingHomeLayer() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        try {
            val index = Index(listOf(entry)); val usage = Usage()
            val repository = FakeLayoutRepository(ParcLayout())
            val search = SearchSheetState(LabelSearchEngine(), index, usage, scope)
            val edit = RingEditSession(repository, scope)
            val navigation = ShellNavigation()
            val session = LauncherSession(navigation, index, usage, repository, search, {}, {}, Shortcuts(), scope,
                closeEditing = { val enabled = edit.enabled; edit.close(); enabled })
            edit.enter(); session.back()
            assertFalse(edit.enabled)
            assertEquals(Layer.Home, navigation.state.value.layer)
            edit.enter(); edit.notify("Saved"); session.onHostPaused()
            assertFalse(edit.enabled)
            assertEquals("", edit.notice)
            edit.enter(); session.reset()
            assertFalse(edit.enabled)
        } finally { scope.cancel() }
    }

    @Test fun depthAndMissingAppsPreserveSavedSlotPositionsAndAlignmentDefault() {
        val geometry = RingLayout(384f, 853f)
        val arc = geometry.arcs.first()
        val missing = AppId("missing/.Main@0")
        val layout = ParcLayout(arcs = listOf(ArcAssignment(arc,
            mapOf(0 to Slot.App(missing), 2 to Slot.App(id)))))
        val scene = LauncherScenes.build(geometry, layout, listOf(entry), ShellState(), "") { null }
        assertEquals(listOf(SlotPosition(arc.id, 2)), scene.slots.map { it.position })
        assertFalse(com.brightblu.parc.ring.RingLayoutOptions().alignAcrossArcs)
        assertEquals(geometry.arcs, scene.arcs)
    }

    @Test fun feedbackRequiresSuccessfulPinnedLaunchAndActualMenuOpen() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        try {
            val index = Index(listOf(entry)); val usage = Usage()
            val repository = FakeLayoutRepository(ParcLayout(pins = setOf(id)))
            val search = SearchSheetState(LabelSearchEngine(), index, usage, scope)
            var success = false; var confirms = 0; var menus = 0
            val session = LauncherSession(ShellNavigation(), index, usage, repository, search, {}, {}, Shortcuts(), scope,
                shortcutDispatcher = UnconfinedTestDispatcher(), launchApp = { if (success) LaunchOutcome.Started else LaunchOutcome.Unavailable },
                onPinnedLaunch = { confirms++ }, onMenuOpened = { menus++ })
            session.launch(id)
            assertEquals(0, confirms); assertTrue(usage.recorded.isEmpty())
            success = true; session.launch(id)
            assertEquals(1, confirms); assertEquals(listOf(id), usage.recorded)
            index.apps.value = listOf(entry.copy(suspended = true)); session.launch(id)
            assertEquals(1, confirms)
            session.onSearch()
            val hit = SlotHit(SlotPosition("A1", 0), Slot.App(id), 0f, 0f)
            session.onLongPress(hit); assertEquals(0, menus)
            session.dismissSearch(); session.onLongPress(hit); assertEquals(1, menus)
        } finally { scope.cancel() }
    }

    @Test fun everyLaunchResetsOverlaysAndRecordsOnceButSuspendedOrMissingCannotLaunch() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        try {
            val index = Index(listOf(entry))
            val usage = Usage()
            val search = SearchSheetState(LabelSearchEngine(), index, usage, scope)
            val navigation = ShellNavigation()
            var hidden = 0
            val session = LauncherSession(navigation, index, usage, FakeLayoutRepository(), search, { hidden++ }, {}, Shortcuts(), scope)
            session.allApps()
            session.launch(id)
            assertEquals(listOf(id), usage.recorded)
            assertEquals(listOf(id), index.launched)
            assertEquals(ShellState(), navigation.state.value)
            assertFalse(search.isOpen)
            index.apps.value = listOf(entry.copy(suspended = true))
            session.launch(id)
            session.launch(AppId("missing"))
            assertEquals(1, usage.recorded.size)
            assertEquals(1, index.launched.size)
            assertTrue(hidden > 0)
        } finally { scope.cancel() }
    }

    @Test fun integratedBackDismissesSearchWithImeAndPreservesFolderAndHomeResetsQuery() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        try {
            val index = Index(listOf(entry)); val usage = Usage()
            val search = SearchSheetState(LabelSearchEngine(), index, usage, scope)
            val navigation = ShellNavigation()
            val session = LauncherSession(navigation, index, usage, FakeLayoutRepository(), search, {}, {}, Shortcuts(), scope)
            session.onTap(SlotHit(SlotPosition("A3", 0), Slot.Folder(folderId), 0f, 0f))
            session.onSearch()
            session.keyboard(true)
            session.back()
            assertFalse(navigation.state.value.keyboardOpen)
            assertFalse(search.isOpen)
            session.allApps()
            session.onSearch()
            assertFalse(search.state.value.abcListOpen)
            session.allApps()
            session.back()
            assertFalse(search.state.value.abcListOpen)
            assertTrue(search.isOpen)
            session.back()
            assertFalse(search.isOpen)
            assertEquals(Layer.Folder, navigation.state.value.layer)
            session.back()
            assertEquals(ShellState(), navigation.state.value)
            session.onSearch(); search.onQueryChange("exam")
            session.reset()
            assertEquals("", search.state.value.query)
            assertEquals(ShellState(), navigation.state.value)
            val oldFolder = RingState(emptyList(), emptyList(), 1f, layer = Layer.Folder)
            session.onSettled(oldFolder)
            assertEquals(ShellState(), navigation.state.value)
            session.onInterrupted(oldFolder)
            assertEquals(Layer.Folder, navigation.state.value.layer)
        } finally { scope.cancel() }
    }

    @Test fun dismissalPreservesFolderAndTheGearTapUsesTheSettingsCallback() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        try {
            val index = Index(listOf(entry)); val usage = Usage()
            val search = SearchSheetState(LabelSearchEngine(), index, usage, scope)
            val navigation = ShellNavigation()
            var settings = 0
            val session = LauncherSession(navigation, index, usage, FakeLayoutRepository(), search, {}, { settings++ }, Shortcuts(), scope)
            session.onTap(SlotHit(SlotPosition("A3", 0), Slot.Folder(folderId), 0f, 0f))
            session.onSearch(); session.keyboard(true); session.keyboard(false)
            assertFalse(search.isOpen)
            assertFalse(navigation.state.value.keyboardOpen)
            assertEquals(folderId, navigation.state.value.folderId)
            session.onTap(SlotHit(SlotPosition("A2", 4), Slot.Action(ActionKind.Settings), 0f, 0f))
            assertEquals(1, settings)
            assertTrue(index.launched.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun moreIsOmittedWithoutChangingSavedPositions() {
        val geometry = RingLayout(384f, 853f)
        val assignments = geometry.arcs.map { arc -> ArcAssignment(arc, if (arc.id == "A4")
            mapOf(0 to Slot.Action(ActionKind.More), 1 to Slot.Folder(folderId)) else emptyMap()) }
        val layout = ParcLayout(arcs = assignments, folders = listOf(Folder(folderId, "Social")))
        val scene = LauncherScenes.build(geometry, layout, emptyList(), ShellState(), "") { null }
        assertEquals(listOf(SlotPosition("A4", 1)), scene.slots.map { it.position })
        assertEquals(Slot.Action(ActionKind.More), layout.arcs.last().slots[0])
    }

    @Test fun realLayoutProjectionKeepsHolesAndMakesUnplacedInboxReachable() {
        val geometry = RingLayout(384f, 853f)
        val position = SlotPosition("A1", 2)
        val layout = ParcLayout(arcs = geometry.arcs.map { ArcAssignment(it,
            if (it.id == "A1") mapOf(2 to Slot.App(id)) else emptyMap()) },
            folders = listOf(Folder(FolderId("new"), "New", appIds = listOf(id))), pins = setOf(id))
        val scene = LauncherScenes.build(geometry, layout, listOf(entry.copy(suspended = true)), ShellState(), "09:41") { null }
        assertEquals(position, scene.slots.first { it.slot == Slot.App(id) }.position)
        assertFalse(scene.slots.first { it.slot == Slot.App(id) }.enabled)
        assertTrue(scene.slots.any { it.slot == Slot.Folder(FolderId("new")) && it.position.arcId == "A4" })
        assertEquals(mapOf(2 to Slot.App(id)), layout.arcs.first().slots)
        val hidden = LauncherScenes.build(geometry, layout.copy(hidden = setOf(id)), listOf(entry), ShellState(), "") { null }
        assertFalse(hidden.slots.any { it.slot == Slot.App(id) })
    }

    @Test fun folderProjectionUsesPersistedOrderAndHiddenFilter() {
        val geometry = RingLayout(384f, 853f)
        val other = AppId("other/.Main@0")
        val layout = ParcLayout(folders = listOf(Folder(folderId, "Social", appIds = listOf(other, id))), hidden = setOf(other))
        val scene = LauncherScenes.build(geometry, layout, listOf(entry, entry.copy(id = other)),
            ShellState(layer = Layer.Folder, folderId = folderId), "") { null }
        assertEquals("Social", scene.title)
        assertEquals(listOf(Slot.App(id)), scene.slots.map { it.slot })
        assertEquals(SlotPosition("social/A1", 0), scene.slots.single().position)
    }

    @Test fun swipesRespectTopKeepoutDirectionConsumptionAndListStart() {
        val swipe = SearchSwipe()
        swipe.start(100f, 56f); assertFalse(swipe.move(100f, 200f, false))
        swipe.start(100f, 57f); assertFalse(swipe.move(200f, 110f, false))
        assertTrue(swipe.move(100f, 110f, false)); assertFalse(swipe.move(100f, 180f, false))
        swipe.start(100f, 100f); assertFalse(swipe.move(100f, 170f, true))
        swipe.start(100f, 100f); assertFalse(swipe.scroll(30f, 0f)); assertTrue(swipe.scroll(20f, 0f))
        swipe.start(100f, 100f); assertFalse(swipe.scroll(0f, 20f))
        assertFalse(swipe.scroll(100f, 0f)); assertFalse(swipe.move(100f, 250f, false))
    }

    private fun menuSession(
        layout: LayoutRepository = FakeLayoutRepository(),
        shortcuts: Shortcuts = Shortcuts(),
        index: Index = Index(listOf(entry)),
    ): Triple<LauncherSession, Index, Shortcuts> {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val usage = Usage()
        val search = SearchSheetState(LabelSearchEngine(), index, usage, scope)
        val session = LauncherSession(ShellNavigation(), index, usage, layout, search, {}, {},
            shortcuts, scope, UnconfinedTestDispatcher())
        return Triple(session, index, shortcuts)
    }

    @Test fun longPressOnAppOpensMenuAndLoadsShortcuts() {
        val (session, index, _) = menuSession()
        index.shortcutsResult = listOf(AppShortcut("chat1", "Chat with Alice", id))
        session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(id), 200f, 700f))
        val menu = requireNotNull(session.menu.value)
        assertEquals(id, menu.appId)
        assertTrue(menu.isDefaultHome)
        assertFalse(menu.pinned)
        assertEquals(listOf(AppShortcut("chat1", "Chat with Alice", id)), menu.shortcuts)
    }

    @Test fun longPressOnFolderDoesNotOpenMenu() {
        val (session, _, _) = menuSession()
        session.onLongPress(SlotHit(SlotPosition("A3", 0), Slot.Folder(folderId), 200f, 700f))
        assertNull(session.menu.value)
    }

    @Test fun notDefaultHomeOpensMenuWithoutShortcuts() {
        val (session, _, _) = menuSession(shortcuts = Shortcuts(defaultHome = false))
        session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(id), 200f, 700f))
        val menu = requireNotNull(session.menu.value)
        assertFalse(menu.isDefaultHome)
        assertTrue(menu.shortcuts.isEmpty())
    }

    @Test fun launchingAShortcutDismissesRecordsAndDelegates() {
        val (session, _, shortcuts) = menuSession()
        session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(id), 200f, 700f))
        assertNotNull(session.menu.value)
        session.launchShortcut(AppShortcut("chat1", "Chat with Alice", id))
        assertNull(session.menu.value)
        assertEquals(listOf(id to "chat1"), shortcuts.launched)
    }

    @Test fun togglePinPinsUnpinnedAppToFirstVacantSlotAndUnpinsInPlace() {
        val geometry = RingLayout(384f, 853f)
        val layout = FakeLayoutRepository(ParcLayout(arcs = geometry.arcs.map { ArcAssignment(it, emptyMap()) }))
        val (session, _, _) = menuSession(layout = layout)
        session.onLongPress(SlotHit(SlotPosition("A3", 0), Slot.App(id), 200f, 700f))
        assertNotNull(session.menu.value)
        session.togglePin()
        assertNull(session.menu.value)
        assertTrue(id in layout.layout.value.pins)
        assertEquals(Slot.App(id), layout.layout.value.arcs.first { it.arc.id == "A1" }.slots[0])

        session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(id), 200f, 700f))
        assertTrue(session.menu.value!!.pinned)
        session.togglePin()
        assertFalse(id in layout.layout.value.pins)
    }

    @Test fun hubDoubleTapUsesConfirmedLaunchAndTrackerHookAndCancelsOnPause() = kotlinx.coroutines.test.runTest {
        val index = Index(listOf(entry)); val usage = Usage()
        val search = SearchSheetState(LabelSearchEngine(), index, usage, this)
        val navigation = ShellNavigation()
        val tracked = mutableListOf<AppId>()
        var known: AppId? = id
        var success = true
        val session = LauncherSession(navigation, index, usage, FakeLayoutRepository(), search, {}, {}, Shortcuts(), this,
            launchApp = { index.launch(it); if (success) LaunchOutcome.Started else LaunchOutcome.Unavailable }, onAppLaunched = { tracked += it },
            lastApp = { known }, hubTapClock = { testScheduler.currentTime })
        session.onHubTap(); session.onHubTap()
        assertEquals(listOf(id), tracked)
        assertEquals(listOf(id), usage.recorded)
        assertEquals(Layer.Home, navigation.state.value.layer)
        known = null
        session.onHubTap(); session.onHubTap()
        assertEquals(1, index.launched.size)
        known = id; success = false
        session.onHubTap(); session.onHubTap()
        assertEquals(1, tracked.size)
        session.onHubTap(); session.onHostPaused()
        testScheduler.advanceUntilIdle()
        assertEquals(Layer.Home, navigation.state.value.layer)
        session.onHubTap(); session.onHubBack()
        assertEquals(Layer.AllApps, navigation.state.value.layer)
        testScheduler.advanceUntilIdle()
        assertEquals(Layer.AllApps, navigation.state.value.layer)
        search.cancel()
    }

    @Test fun hubTapTogglesHomeAndAllAppsAndFolderGoesBack() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        try {
            val index = Index(listOf(entry)); val usage = Usage()
            val search = SearchSheetState(LabelSearchEngine(), index, usage, scope)
            val navigation = ShellNavigation()
            val session = LauncherSession(navigation, index, usage, FakeLayoutRepository(), search, {}, {}, Shortcuts(), scope, hubDoubleTapEnabled = false)
            assertEquals(Layer.Home, navigation.state.value.layer)
            session.onHubTap()
            assertEquals(Layer.AllApps, navigation.state.value.layer)
            session.onHubTap()
            assertEquals(Layer.Home, navigation.state.value.layer)
            session.onTap(SlotHit(SlotPosition("A3", 0), Slot.Folder(folderId), 0f, 0f))
            assertEquals(Layer.Folder, navigation.state.value.layer)
            session.onHubTap()
            assertEquals(Layer.Home, navigation.state.value.layer)
            session.onHubTap()
            assertEquals(Layer.AllApps, navigation.state.value.layer)
            // Back from All-apps returns Home, matching the retired Recents layer.
            navigation.emit(ShellEvent.Back)
            assertEquals(ShellState(), navigation.state.value)
        } finally { scope.cancel() }
    }
}
