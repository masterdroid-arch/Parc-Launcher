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
import org.junit.Assert.*
import org.junit.Test

/** T69: gesture actions, their stored form, the swipe-up gate and the session dispatch. */
@OptIn(ExperimentalCoroutinesApi::class)
class T69Test {
    private val app = AppId("test/App@0")
    private val other = AppId("test/Other@0")
    private val alpha = AppEntry(app, "Alpha", "test.alpha", false, 0)
    private val zulu = AppEntry(other, "Zulu", "test.zulu", false, 0)

    // ---- stored form -------------------------------------------------------------------------

    @Test fun everyActionRoundTripsThroughItsStoredText() {
        val actions = listOf(
            GestureAction.None, GestureAction.AllApps, GestureAction.Search,
            GestureAction.LastApp, GestureAction.EditMode,
            GestureAction.HubPage("power"), GestureAction.OpenApp(app),
        )
        actions.forEach { action ->
            assertEquals(action, GestureAction.decode(action.encode(), GestureAction.None))
        }
    }

    @Test fun anAppIdWithSeparatorsSurvivesTheEncoding() {
        val odd = GestureAction.OpenApp(AppId("com.example/.Main:10"))
        assertEquals(odd, GestureAction.decode(odd.encode(), GestureAction.None))
        assertEquals("app:com.example/.Main:10", odd.encode())
    }

    @Test fun missingUnknownOrEmptyTextKeepsTheGivenDefault() {
        val fallback = GestureAction.EditMode
        assertEquals(fallback, GestureAction.decode(null, fallback))
        assertEquals(fallback, GestureAction.decode("", fallback))
        assertEquals(fallback, GestureAction.decode("page:", fallback))
        assertEquals(fallback, GestureAction.decode("app:", fallback))
        assertEquals(fallback, GestureAction.decode("shade", fallback))
    }

    // OPUS8 (T133): the swipe-up default is now All apps (the product owner's decision); hub long-press unchanged.
    @Test fun theDefaultsLeaveTodaysBehaviourUnchanged() {
        val defaults = GestureConfig()
        assertEquals(GestureAction.AllApps, defaults.swipeUp)
        assertEquals(GestureAction.EditMode, defaults.hubLongPress)
        assertEquals(GestureAction.AllApps, GestureAction.decode(null, defaults.swipeUp))
        assertEquals(GestureAction.EditMode, GestureAction.decode(null, defaults.hubLongPress))
    }

    @Test fun configReplacesOnlyTheChosenGesture() {
        val updated = GestureConfig().with(GestureSlot.SwipeUp, GestureAction.AllApps)
        assertEquals(GestureAction.AllApps, updated.swipeUp)
        assertEquals(GestureAction.EditMode, updated.hubLongPress)
        assertEquals(GestureAction.AllApps, updated.actionFor(GestureSlot.SwipeUp))
        assertEquals(GestureAction.EditMode, updated.actionFor(GestureSlot.HubLongPress))
    }

    // ---- rows and labels ---------------------------------------------------------------------

    @Test fun swipeUpRowsListTheCardActionsWithoutEditMode() {
        val rows = gestureRows(GestureSlot.SwipeUp, GestureAction.None)
        val picks = rows.filterIsInstance<GestureRow.Pick>()
        assertEquals(listOf(GestureAction.None, GestureAction.AllApps, GestureAction.Search, GestureAction.LastApp),
            picks.map { it.action })
        assertTrue(picks.none { it.action == GestureAction.EditMode })
        assertEquals(listOf("None", "All apps view", "Search", "Last app"), picks.map { it.label })
        assertEquals(2, rows.filterIsInstance<GestureRow.Picker>().size)
    }

    @Test fun hubLongPressRowsOfferEditModeAfterTheSharedActions() {
        val rows = gestureRows(GestureSlot.HubLongPress, GestureAction.EditMode)
        val picks = rows.filterIsInstance<GestureRow.Pick>()
        assertEquals(listOf(GestureAction.None, GestureAction.AllApps, GestureAction.Search,
            GestureAction.LastApp, GestureAction.EditMode), picks.map { it.action })
        assertTrue(picks.single { it.action == GestureAction.EditMode }.checked)
    }

    @Test fun exactlyTheCurrentActionIsCheckedAndPickersShowTheirChoice() {
        val current = GestureAction.OpenApp(other)
        val rows = gestureRows(GestureSlot.SwipeUp, current) { id -> if (id == other) "Zulu" else null }
        assertEquals(listOf(false, false, false, false), rows.filterIsInstance<GestureRow.Pick>().map { it.checked })
        val pickers = rows.filterIsInstance<GestureRow.Picker>()
        assertFalse(pickers.single { it.picker == GesturePicker.HubPage }.checked)
        assertEquals("Choose a page", pickers.single { it.picker == GesturePicker.HubPage }.detail)
        assertTrue(pickers.single { it.picker == GesturePicker.App }.checked)
        assertEquals("Zulu", pickers.single { it.picker == GesturePicker.App }.detail)
    }

    @Test fun pageRowsFollowTheVisiblePagesAndMarkTheChosenOne() {
        val rows = gesturePageRows(listOf("system", "power"), GestureAction.HubPage("power"))
        assertEquals(listOf("System", "Power"), rows.map { it.label })
        assertEquals(listOf(false, true), rows.map { it.checked })
        assertEquals(listOf(GestureAction.HubPage("system"), GestureAction.HubPage("power")),
            rows.map { it.action })
    }

    @Test fun appRowsSortByLabelAndMarkTheChosenOne() {
        val rows = gestureAppRows(listOf(zulu, alpha), GestureAction.OpenApp(zulu.id))
        assertEquals(listOf("Alpha", "Zulu"), rows.map { it.label })
        assertEquals(listOf(false, true), rows.map { it.checked })
    }

    @Test fun labelsNameTheTargetAndStayShort() {
        assertEquals("None", gestureActionLabel(GestureAction.None))
        assertEquals("All apps", gestureActionLabel(GestureAction.AllApps))
        assertEquals("Search", gestureActionLabel(GestureAction.Search))
        assertEquals("Last app", gestureActionLabel(GestureAction.LastApp))
        assertEquals("Edit mode", gestureActionLabel(GestureAction.EditMode))
        assertEquals("Stats · Power", gestureActionLabel(GestureAction.HubPage("power")))
        assertEquals("App · Alpha", gestureActionLabel(GestureAction.OpenApp(app)) { "Alpha" })
        assertEquals("App · not installed", gestureActionLabel(GestureAction.OpenApp(app)))
    }

    // ---- resolution --------------------------------------------------------------------------

    @Test fun plainActionsMapOneToOne() {
        val pages = listOf("system", "power")
        assertEquals(GestureTarget.Nothing, resolveGesture(GestureAction.None, pages) { true })
        assertEquals(GestureTarget.AllApps, resolveGesture(GestureAction.AllApps, pages) { true })
        assertEquals(GestureTarget.Search, resolveGesture(GestureAction.Search, pages) { true })
        assertEquals(GestureTarget.LastApp, resolveGesture(GestureAction.LastApp, pages) { true })
        assertEquals(GestureTarget.EditMode, resolveGesture(GestureAction.EditMode, pages) { true })
    }

    @Test fun aChosenAppRunsOnlyWhileItIsInstalled() {
        assertEquals(GestureTarget.OpenApp(app), resolveGesture(GestureAction.OpenApp(app), emptyList()) { it == app })
        assertEquals(GestureTarget.Nothing, resolveGesture(GestureAction.OpenApp(app), emptyList()) { false })
    }

    @Test fun aHiddenOrUnknownPageFallsBackToTheFirstVisiblePageThenNothing() {
        assertEquals(GestureTarget.HubPage("power"),
            resolveGesture(GestureAction.HubPage("power"), listOf("system", "power")) { true })
        assertEquals(GestureTarget.HubPage("system"),
            resolveGesture(GestureAction.HubPage("sound"), listOf("system", "power")) { true })
        assertEquals(GestureTarget.Nothing, resolveGesture(GestureAction.HubPage("system"), emptyList()) { true })
    }

    // ---- swipe-up gate -----------------------------------------------------------------------

    @Test fun swipeUpIsArmedOnlyForAnActionOnEmptySpaceOutsideTheHubPanel() {
        assertTrue(SwipeUpGate.armed(GestureAction.AllApps, blocked = false, onControl = false, fromHubPanel = false))
        assertFalse(SwipeUpGate.armed(GestureAction.None, blocked = false, onControl = false, fromHubPanel = false))
        assertFalse(SwipeUpGate.armed(GestureAction.AllApps, blocked = true, onControl = false, fromHubPanel = false))
        assertFalse(SwipeUpGate.armed(GestureAction.AllApps, blocked = false, onControl = true, fromHubPanel = false))
        assertFalse(SwipeUpGate.armed(GestureAction.AllApps, blocked = false, onControl = false, fromHubPanel = true))
    }

    // ---- upward detector (same thresholds as T42/T59) ---------------------------------------

    @Test fun anUpwardDragNeedsTheSame110DpAndDominance() {
        val detector = SearchSwipeDetector(upward = true)
        detector.start(100f, 700f, 0L)
        assertFalse(detector.move(100f, 591f, 2_000))
        assertTrue(detector.move(100f, 590f, 3_000))
        // One decision per gesture.
        assertFalse(detector.move(100f, 500f, 3_100))

        detector.start(100f, 700f, 0L)
        assertFalse(detector.move(174f, 590f, 1_000))
        assertTrue(detector.move(173f, 590f, 1_100))
    }

    @Test fun anUpwardFlingNeeds60DpAndMoreThan1200DpPerSecond() {
        val detector = SearchSwipeDetector(upward = true)
        detector.start(100f, 700f, 0L)
        assertFalse(detector.move(100f, 641f, 20, released = true))
        detector.start(100f, 700f, 0L)
        assertFalse(detector.move(100f, 640f, 50, released = true))
        detector.start(100f, 700f, 0L)
        assertFalse(detector.move(100f, 640f, 40))
        detector.start(100f, 700f, 0L)
        assertTrue(detector.move(100f, 640f, 49, released = true))
    }

    @Test fun downwardTravelNeverFiresTheUpwardDetector() {
        val detector = SearchSwipeDetector(upward = true)
        detector.start(100f, 400f, 0L)
        assertFalse(detector.move(100f, 700f, 40, released = true))
    }

    @Test fun aBlockedOrControlStartNeverFiresEvenWithEnoughTravel() {
        val detector = SearchSwipeDetector(upward = true)
        detector.start(100f, 700f, 0L, blocked = true)
        assertFalse(detector.move(100f, 500f, 40, released = true))
        detector.start(100f, 700f, 0L, onControl = true)
        assertFalse(detector.move(100f, 500f, 40, released = true))
        detector.start(100f, 40f, 0L)
        assertFalse(detector.move(100f, 10f, 40, released = true))
    }

    // ---- settings rows -----------------------------------------------------------------------


    // ---- session dispatch --------------------------------------------------------------------

    private class Index(entries: List<AppEntry>) : AppIndex {
        override val apps = MutableStateFlow(entries)
        val launched = mutableListOf<AppId>()
        override fun icon(id: AppId, sizePx: Int): android.graphics.Bitmap? = null
        override fun launch(id: AppId) { launched += id }
        override fun shortcuts(id: AppId): List<AppShortcut> = emptyList()
    }

    private class Usage : UsageRepository {
        override fun recordLaunch(id: AppId) = Unit
        override fun recents(limit: Int): Flow<List<AppId>> = flowOf(emptyList())
        override fun rank(folderId: FolderId): List<AppId> = emptyList()
    }

    private class Shortcuts : ShortcutHost {
        override fun isDefaultHome(): Boolean = true
        override fun launchShortcut(appId: AppId, shortcutId: String) = Unit
    }

    private inner class Harness(config: GestureConfig) {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val navigation = ShellNavigation()
        val index = Index(listOf(alpha, zulu))
        val search: SearchSheetState
        val session: LauncherSession
        val hubPages = mutableListOf<String>()
        var edits = 0

        init {
            search = SearchSheetState(LabelSearchEngine(), index, Usage(), scope)
            session = LauncherSession(navigation, index, Usage(), FakeLayoutRepository(ParcLayout()),
                search, {}, {}, Shortcuts(), scope,
                gestures = { config },
                enterEditMode = { edits++ },
                hubPage = { hubPages += it },
                hubPages = { listOf("system", "power") },
                lastApp = { other })
        }
    }

    // OPUS1: "All apps view" shows the All-apps arcs (T29), not the alphabetical list.
    @Test fun swipeUpAllAppsShowsTheAllAppsArcs() {
        val harness = Harness(GestureConfig(swipeUp = GestureAction.AllApps))
        harness.session.onSwipeUp()
        assertFalse(harness.search.isOpen)
        assertEquals(Layer.AllApps, harness.navigation.state.value.layer)
        harness.session.onSwipeUp()
        assertEquals(Layer.Home, harness.navigation.state.value.layer)
    }

    @Test fun hubLongPressAllAppsLeavesAFolderAndClosesSearch() {
        val harness = Harness(GestureConfig(hubLongPress = GestureAction.AllApps))
        harness.navigation.show(ShellState(layer = Layer.Folder, folderId = FolderId("f")))
        harness.session.onHubLongPress()
        assertEquals(Layer.AllApps, harness.navigation.state.value.layer)
        harness.session.onSearch()
        assertTrue(harness.search.isOpen)
        harness.session.onHubLongPress()
        assertFalse(harness.search.isOpen)
    }

    @Test fun swipeUpNoneDoesNothing() {
        val harness = Harness(GestureConfig(swipeUp = GestureAction.None))
        harness.session.onSwipeUp()
        assertFalse(harness.search.isOpen)
        assertEquals(Layer.Home, harness.navigation.state.value.layer)
        assertEquals(0, harness.edits)
    }

    @Test fun hubLongPressEditModeEntersEditMode() {
        val harness = Harness(GestureConfig())
        harness.session.onHubLongPress()
        assertEquals(1, harness.edits)
        assertFalse(harness.search.isOpen)
    }

    @Test fun hubLongPressLastAppLaunchesTheLastApp() {
        val harness = Harness(GestureConfig(hubLongPress = GestureAction.LastApp))
        harness.session.onHubLongPress()
        assertEquals(listOf(other), harness.index.launched)
    }

    @Test fun hubLongPressSearchOpensTheSheet() {
        val harness = Harness(GestureConfig(hubLongPress = GestureAction.Search))
        harness.session.onHubLongPress()
        assertTrue(harness.search.isOpen)
        assertFalse(harness.search.state.value.abcListOpen)
    }

    @Test fun hubLongPressHubPageGoesHomeAndRemembersThePage() {
        val harness = Harness(GestureConfig(hubLongPress = GestureAction.HubPage("power")))
        harness.navigation.show(ShellState(layer = Layer.AllApps))
        harness.session.onHubLongPress()
        assertEquals(Layer.Home, harness.navigation.state.value.layer)
        assertEquals(listOf("power"), harness.hubPages)
    }

    @Test fun hubLongPressOpenAppLaunchesOnlyAnInstalledApp() {
        val harness = Harness(GestureConfig(hubLongPress = GestureAction.OpenApp(app)))
        harness.session.onHubLongPress()
        assertEquals(listOf(app), harness.index.launched)

        val missing = Harness(GestureConfig(hubLongPress = GestureAction.OpenApp(AppId("test/Gone@0"))))
        missing.session.onHubLongPress()
        assertTrue(missing.index.launched.isEmpty())
    }
}
