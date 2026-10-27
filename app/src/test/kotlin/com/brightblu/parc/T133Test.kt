package com.brightblu.parc

import com.brightblu.parc.feature.search.LabelSearchEngine
import com.brightblu.parc.feature.search.SearchSheetState
import com.brightblu.parc.feature.search.extras.ParcSettingsRanking
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

/** OPUS8 (T133): Home key, Home menu, swipe-up default migration, Settings in search, edit-mode words. */
@OptIn(ExperimentalCoroutinesApi::class)
class T133Test {
    private val app = AppId("test/App@0")
    private val alpha = AppEntry(app, "Alpha", "test.alpha", false, 0)

    private class Index(entries: List<AppEntry>) : AppIndex {
        override val apps = MutableStateFlow(entries)
        override fun icon(id: AppId, sizePx: Int): android.graphics.Bitmap? = null
        override fun launch(id: AppId) = Unit
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

    private inner class Harness {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val navigation = ShellNavigation()
        val index = Index(listOf(alpha))
        val search = SearchSheetState(LabelSearchEngine(), index, Usage(), scope)
        var edits = 0
        var editing = false
        var hidden = 0
        val opened = mutableListOf<SettingsTarget>()
        val session = LauncherSession(navigation, index, Usage(), FakeLayoutRepository(ParcLayout()),
            search, { hidden++ }, {}, Shortcuts(), scope,
            closeEditing = { val was = editing; editing = false; was },
            enterEditMode = { edits++; editing = true },
            openSettingsAt = { opened += it })
    }

    // ---- Home key ----------------------------------------------------------------------------

    @Test fun homeClosesAFolderSearchAndMenusInOnePlace() {
        val h = Harness()
        h.navigation.show(ShellState(layer = Layer.Folder, folderId = FolderId("f")))
        h.search.open()
        h.session.syncSearch()
        h.editing = true
        h.session.home()
        assertEquals(ShellState(), h.navigation.state.value)
        assertFalse(h.search.isOpen)
        assertFalse(h.editing)
        assertNull(h.session.homeMenu.value)
        assertNull(h.session.menu.value)
        assertNull(h.session.folderMenu.value)
        assertEquals(1, h.session.homePresses.value)
    }

    @Test fun homeLeavesTheAllAppsRing() {
        val h = Harness()
        h.navigation.show(ShellState(layer = Layer.AllApps))
        h.session.home()
        assertEquals(Layer.Home, h.navigation.state.value.layer)
    }

    @Test fun homeOnACleanHomeStillCountsSoTheHubGoesToItsFirstPage() {
        val h = Harness()
        h.session.home()
        h.session.home()
        assertEquals(ShellState(), h.navigation.state.value)
        assertEquals(2, h.session.homePresses.value)
    }

    @Test fun homeClosesTheHomeMenu() {
        val h = Harness()
        h.session.onEmptyLongPress(100f, 500f)
        assertNotNull(h.session.homeMenu.value)
        h.session.home()
        assertNull(h.session.homeMenu.value)
    }

    @Test fun theFirstHubPageFollowsTheSavedOrderAndTheFocusFilter() {
        assertEquals("power", HomeKey.firstHubPage(false, listOf("power", "clock", "system")))
        assertEquals("clock", HomeKey.firstHubPage(false, listOf("power", "clock")) { it != "power" })
        assertNull(HomeKey.firstHubPage(false, emptyList()))
        assertEquals(DemoHubPages.first(), HomeKey.firstHubPage(true, listOf("power")))
    }

    // ---- Home menu ---------------------------------------------------------------------------

    @Test fun theHomeMenuHasTheFourApprovedRowsInOrder() {
        assertEquals(listOf("Edit home", "Wallpaper and look", "Hub pages", "Parc settings"),
            HomeMenuRow.entries.map { it.label })
    }

    @Test fun emptySpaceHoldOpensTheMenuOnlyOnAClearHome() {
        val h = Harness()
        h.session.onEmptyLongPress(120f, 480f)
        assertEquals(HomeMenuModel(120f, 480f), h.session.homeMenu.value)

        val inSearch = Harness()
        inSearch.session.onSearch()
        inSearch.session.onEmptyLongPress(1f, 1f)
        assertNull(inSearch.session.homeMenu.value)

        val inFolder = Harness()
        inFolder.navigation.show(ShellState(layer = Layer.Folder, folderId = FolderId("f")))
        inFolder.session.onEmptyLongPress(1f, 1f)
        assertNull(inFolder.session.homeMenu.value)
    }

    @Test fun backClosesTheHomeMenuFirst() {
        val h = Harness()
        h.session.onEmptyLongPress(1f, 1f)
        h.session.back()
        assertNull(h.session.homeMenu.value)
        assertEquals(Layer.Home, h.navigation.state.value.layer)
    }

    @Test fun editHomeEntersEditModeAndTheOtherRowsOpenSettings() {
        val h = Harness()
        h.session.onEmptyLongPress(1f, 1f)
        h.session.homeMenuAction(HomeMenuRow.EditHome)
        assertEquals(1, h.edits)
        assertNull(h.session.homeMenu.value)

        listOf(HomeMenuRow.Look, HomeMenuRow.HubPages, HomeMenuRow.Settings).forEach { row ->
            h.session.onEmptyLongPress(1f, 1f)
            h.session.homeMenuAction(row)
        }
        assertEquals(listOf(SettingsTarget(SettingsPage.Root, SettingsSectionId.Appearance),
            SettingsTarget(SettingsPage.HubPages), SettingsTarget(SettingsPage.Root)), h.opened)
    }

    @Test fun aRowWithoutAnOpenMenuDoesNothing() {
        val h = Harness()
        h.session.homeMenuAction(HomeMenuRow.EditHome)
        assertEquals(0, h.edits)
    }

    // ---- Swipe up default (migration) ---------------------------------------------------------

    @Test fun anUnsetSwipeUpOpensAllApps() {
        assertEquals(GestureAction.AllApps, GestureConfig.decode(null, null).swipeUp)
        assertEquals(GestureAction.AllApps, GestureConfig.decode("", null).swipeUp)
        assertEquals(GestureAction.EditMode, GestureConfig.decode(null, null).hubLongPress)
    }

    @Test fun aStoredChoiceIsKeptIncludingNone() {
        listOf(GestureAction.None, GestureAction.Search, GestureAction.LastApp, GestureAction.AllApps,
            GestureAction.HubPage("power"), GestureAction.OpenApp(app)).forEach { chosen ->
            assertEquals(chosen, GestureConfig.decode(chosen.encode(), null).swipeUp)
        }
        assertEquals(GestureAction.Search, GestureConfig.decode("none", "search").hubLongPress)
    }

    @Test fun aBackupRoundTripKeepsTheStoredTextAndItsMeaning() {
        // The backup copies the two stored texts (BackupSpecs.gestures); restoring writes them back
        // and the store reads them through the same decode.
        val stored = mapOf(GestureSlot.SwipeUp.key to GestureAction.None.encode())
        val restored = GestureConfig.decode(stored[GestureSlot.SwipeUp.key], stored[GestureSlot.HubLongPress.key])
        assertEquals(GestureConfig(swipeUp = GestureAction.None), restored)
        assertEquals(setOf("swipe_up", "hub_long_press"),
            com.brightblu.parc.core.data.backup.BackupSpecs.gestures.keys.map { it.name }.toSet())
    }

    @Test fun theRootRowShowsAllAppsForANewUser() {
        assertEquals("All apps", settingsRootRows("Midnight", false, true, false, true)
            .single { it.id == SettingsRowId.SwipeUp }.detail)
    }

    // ---- Parc settings in search -------------------------------------------------------------

    @Test fun parcSettingsAndItsPagesAreFindable() {
        val entries = SettingsSearchEntries.all
        assertEquals(SettingsSearchEntries.RootKey, ParcSettingsRanking.rank("parc settings", entries).first().key)
        assertEquals(SettingsTarget(SettingsPage.Wallpaper),
            SettingsSearchEntries.target(ParcSettingsRanking.rank("wallpaper", entries).first().key))
        assertEquals(SettingsTarget(SettingsPage.HubPages),
            SettingsSearchEntries.target(ParcSettingsRanking.rank("hub pages", entries).first().key))
        assertEquals(SettingsTarget(SettingsPage.Root, SettingsSectionId.Appearance),
            SettingsSearchEntries.target(ParcSettingsRanking.rank("look", entries).first().key))
    }

    @Test fun everyEntryOpensSomewhereAndUsesTheSettingsWords() {
        val rootLabels = settingsRootRows("", false, true, false, true).map { it.label }.toSet() +
            SettingsSectionId.entries.map { it.title } + "Parc settings"
        SettingsSearchEntries.all.forEach { entry ->
            assertNotNull(entry.key, SettingsSearchEntries.target(entry.key))
            assertTrue(entry.title, entry.title in rootLabels)
        }
        assertNull(SettingsSearchEntries.target("page:Nope"))
        assertNull(SettingsSearchEntries.target("other"))
    }

    @Test fun searchNeverListsSwitchesOrSetupReset() {
        val keys = SettingsSearchEntries.all.map { it.key }
        assertFalse("page:RerunOnboarding" in keys)
        assertFalse("page:Joystick" in keys)
        assertFalse("page:StatsVisible" in keys)
    }

    @Test fun settingsResultsCannotRevealALockedFolderOrApp() {
        // The entries are fixed page names: no layout, folder or app goes in, so a locked folder's
        // name or its apps can never appear, however the layout looks.
        val text = SettingsSearchEntries.all.flatMap { listOf(it.title) + it.keywords }.joinToString(" ")
        assertTrue(ParcSettingsRanking.rank("Banking", SettingsSearchEntries.all).isEmpty())
        assertFalse(text.contains("Banking"))
        val locked = ParcSettingsRanking.rank("locked", SettingsSearchEntries.all)
        assertEquals("Locked folders", locked.first().title)
    }

    // ---- Edit mode words ---------------------------------------------------------------------

    @Test fun editModeInstructionsAndEmptySpotTitle() {
        assertEquals("Drag an app to move it. Tap + to add. Tap Done when finished.", EditModeText.Instructions)
        assertEquals("Empty spot on the outer arc", EditModeText.emptySpotTitle(3, 4))
        assertEquals("Empty spot on the outer arc", EditModeText.emptySpotTitle(2, 3))
        assertEquals("Empty spot on the inner arc", EditModeText.emptySpotTitle(0, 4))
        assertEquals("Empty spot on the second arc", EditModeText.emptySpotTitle(1, 4))
        assertEquals("Empty spot on the third arc", EditModeText.emptySpotTitle(2, 4))
    }

    @Test fun theEmptySpotSheetHasNoCancelRow() {
        assertFalse(listOf(EditModeText.NewFolder, EditModeText.AddApp, EditModeText.Close).any { it.contains("Cancel") })
        assertFalse(EditModeText.NewFolder.startsWith("+"))
    }
}
