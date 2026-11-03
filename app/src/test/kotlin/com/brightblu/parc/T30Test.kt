package com.brightblu.parc

import com.brightblu.parc.feature.search.LabelSearchEngine
import com.brightblu.parc.feature.search.SearchSheetState
import com.brightblu.parc.model.*
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import com.brightblu.parc.ring.RingLayout
import com.brightblu.parc.ring.RingScenes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** T30: editing UX and the Parc settings entry (menus, folder actions, folder-drop and undo). */
@OptIn(ExperimentalCoroutinesApi::class)
class T30Test {
    @org.junit.Test fun hubPageOptionsCatalogIsCompleteForSettingsAndHome() {
        org.junit.Assert.assertEquals(HubCatalog.pages.toSet(), hubPageOptions.keys)
        org.junit.Assert.assertTrue(SettingsRegistry.byId.getValue("HubPages").help.contains("options"))
        assertTrue("time zone" in SettingsRegistry.byId.getValue("HubPages").words)
        assertTrue(hubPageOptions.getValue("clock").tap.contains("Long press"))
        hubPageOptions.values.forEach { org.junit.Assert.assertTrue(it.fullSettings in SettingsPage.entries) }
    }
    @Test fun sharedEditorsKeepEveryExistingDestination() {
        assertEquals(com.brightblu.parc.feature.settings.ui.EditorSpec.entries.toSet(), editorDestinations.keys)
        assertEquals(10, editorDestinations.values.distinct().size)
        editorDestinations.values.forEach { assertTrue(it in SettingsPage.entries) }
    }


    private val geometry = RingLayout(384f, 853f)
    private val a = AppId("test/A@0")
    private val b = AppId("test/B@0")
    private val social = FolderId("social")
    private val entryA = AppEntry(a, "App A", "test.a", false, 0)
    private val entryB = AppEntry(b, "App B", "test.b", false, 0)

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

    private class Shortcuts(var defaultHome: Boolean = true) : ShortcutHost {
        override fun isDefaultHome(): Boolean = defaultHome
        override fun launchShortcut(appId: AppId, shortcutId: String) = Unit
    }

    private fun rows(items: List<MenuItem>): List<String> =
        items.mapNotNull { (it as? MenuItem.Row)?.row?.label }

    private fun actions(items: List<MenuItem>): List<MenuAction> =
        items.mapNotNull { (it as? MenuItem.Row)?.row?.action }

    // ---- menu item builders ----------------------------------------------------------------

    @Test fun appMenuRootListsEveryRowAndShortcutsInOrder() {
        val menu = MenuModel(a, SlotPosition("A1", 0), 200f, 700f,
            shortcuts = listOf(AppShortcut("s1", "Chat with Alice", a)), pinned = false, label = "App A")
        assertEquals(
            listOf("Chat with Alice", "Pin", "Move to folder", "Hide from Parc", "App info", "Uninstall"),
            rows(appMenuRootItems(menu)),
        )
        assertTrue(appMenuRootItems(menu).any { it is MenuItem.Divider })
    }

    @Test fun appMenuRootSwapsShortcutsForTheNotDefaultHomeHint() {
        val menu = MenuModel(a, SlotPosition("A1", 0), 200f, 700f, pinned = true,
            isDefaultHome = false, label = "App A")
        val items = appMenuRootItems(menu)
        assertEquals("Set Parc as your home screen for shortcuts", (items.first() as MenuItem.Row).row.label)
        assertTrue(rows(items).contains("Unpin"))
        assertEquals(appMenuRootItems(menu).none { (it as? MenuItem.Row)?.row?.action == MenuAction.Shortcut }, true)
    }

    @Test fun appMenuRootShowsRemoveFromFolderOnlyInsideAFolder() {
        val home = MenuModel(a, SlotPosition("A1", 0), 0f, 0f, label = "A")
        assertFalse(rows(appMenuRootItems(home)).contains("Remove from folder"))
        val inside = home.copy(inFolder = social)
        assertTrue(rows(appMenuRootItems(inside)).contains("Remove from folder"))
    }

    @Test fun appMenuFolderPickerAndConfirmPagesAreComplete() {
        val menu = MenuModel(a, SlotPosition("A1", 0), 0f, 0f, label = "App A",
            folders = listOf(Folder(social, "Social"), Folder(FolderId("tools"), "Tools")))
        assertEquals(listOf("Social", "Tools", "New folder", "Back"), rows(appMenuFolderItems(menu)))
        assertEquals(listOf("Uninstall App A?", "Uninstall", "Cancel"), rows(appMenuConfirmItems(menu)))
    }

    @Test fun folderMenuRootDisablesMoveAtTheEnds() {
        val start = FolderMenuModel(social, "Social", SlotPosition("A3", 0), 0f, 0f,
            canMoveEarlier = false, canMoveLater = true)
        val startItems = folderMenuRootItems(start)
        assertEquals("At the start", (startItems[3] as MenuItem.Row).row.detail)
        assertEquals("", (startItems[4] as MenuItem.Row).row.detail)
        val end = start.copy(canMoveEarlier = true, canMoveLater = false)
        assertEquals("", (folderMenuRootItems(end)[3] as MenuItem.Row).row.detail)
        assertEquals("At the end", (folderMenuRootItems(end)[4] as MenuItem.Row).row.detail)
    }

    @Test fun folderMenuAddAndConfirmPagesCarryTheirCopy() {
        val menu = FolderMenuModel(social, "Social", SlotPosition("A3", 0), 0f, 0f,
            candidates = listOf(entryA, entryB))
        assertEquals(listOf("App A", "App B", "Done"), rows(folderMenuAddItems(menu)))
        assertEquals(listOf("Delete Social?", "Its apps go to New.", "Delete", "Cancel"),
            rows(folderMenuConfirmItems(menu)))
    }

    @Test fun folderNameIssueRejectsEmptyLongAndDuplicateNames() {
        val folders = listOf(Folder(social, "Social"), Folder(FolderId("tools"), "Tools"))
        assertEquals("Name cannot be empty", folderNameIssue("  ", folders, social))
        assertEquals("At most 8 characters", folderNameIssue("NineChars!", folders, social))
        assertEquals("Name already used", folderNameIssue("tools", folders, social))
        assertEquals("Name already used", folderNameIssue("TOOLS", folders, social))
        assertEquals("", folderNameIssue("Social", folders, social))
        assertEquals("", folderNameIssue("Media", folders, social))
    }


    @Test fun aMenuTallerThanTheSafeBandIsPinnedToTheTopInsteadOfThrowing() {
        // A long folder candidate list (T30 "Add apps") can be taller than the screen.
        val placement = placeMenu(anchorXDp = 200f, anchorYDp = 500f, menuWidthDp = 220f,
            menuHeightDp = 1104f, screenWidthDp = 384f, screenHeightDp = 853f)
        assertEquals(Metrics.keepOutTopDp, placement.yDp)
    }

    @Test fun dropOntoAFolderDiscIsAllowedOnHome() {        val layout = ParcLayout(
            arcs = geometry.arcs.map { ArcAssignment(it,
                if (it.id == "A1") mapOf(0 to Slot.App(a)) else if (it.id == "A3") mapOf(0 to Slot.Folder(social)) else emptyMap()) },
            folders = listOf(Folder(social, "Social")))
        val scene = LauncherScenes.build(geometry, layout, listOf(entryA), ShellState(), "") { null }
        val from = SlotPosition("A1", 0)
        val to = SlotPosition("A3", 0)
        assertTrue(editDropAllowed(layout, scene, from, to))
    }

    // ---- session: menu actions -------------------------------------------------------------

    private fun newSession(
        layout: LayoutRepository = FakeLayoutRepository(),
        entries: List<AppEntry> = listOf(entryA, entryB),
        navigation: ShellNavigation = ShellNavigation(),
        shortcuts: Shortcuts = Shortcuts(),
        appInfo: (AppId) -> Unit = {},
        uninstall: (AppId) -> Unit = {},
        androidSettings: () -> Unit = {},
    ): LauncherSession {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val index = Index(entries)
        val search = SearchSheetState(LabelSearchEngine(), index, Usage(), scope)
        return LauncherSession(navigation, index, Usage(), layout, search, {}, {}, shortcuts, scope,
            UnconfinedTestDispatcher(), appInfo = appInfo, uninstall = uninstall,
            openAndroidSettings = androidSettings)
    }

    @Test fun longPressOnFolderOpensTheFolderMenuNotTheAppMenu() {
        val layout = FakeLayoutRepository(ParcLayout(
            arcs = geometry.arcs.map { ArcAssignment(it,
                if (it.id == "A3") mapOf(0 to Slot.Folder(social), 1 to Slot.Folder(FolderId("tools"))) else emptyMap()) },
            folders = listOf(Folder(social, "Social", appIds = listOf(a)), Folder(FolderId("tools"), "Tools"))))
        val session = newSession(layout = layout)
        session.onLongPress(SlotHit(SlotPosition("A3", 0), Slot.Folder(social), 100f, 500f))
        assertNull(session.menu.value)
        val menu = requireNotNull(session.folderMenu.value)
        assertEquals(social, menu.folderId)
        assertEquals("Social", menu.name)
        assertFalse(menu.canMoveEarlier)
        assertTrue(menu.canMoveLater)
    }

    @Test fun longPressOnTheGearOpensAndroidSettings() {
        var opened = 0
        val session = newSession(androidSettings = { opened++ })
        session.onLongPress(SlotHit(SlotPosition("A4", 0), Slot.Action(ActionKind.Settings), 0f, 0f))
        assertEquals(1, opened)
        assertNull(session.menu.value)
    }

    @Test fun hideAppInfoAndUninstallRowsCallTheirBoundaries() {
        val layout = FakeLayoutRepository()
        var infos = 0
        var uninstalls = 0
        val session = newSession(layout = layout, appInfo = { infos++ }, uninstall = { uninstalls++ })
        session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(a), 0f, 0f))
        session.menuAction(MenuAction.Hide, "")
        assertTrue(a in layout.layout.value.hidden)

        session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(a), 0f, 0f))
        assertTrue(actions(appMenuRootItems(requireNotNull(session.menu.value))).contains(MenuAction.AppInfo))
        session.menuAction(MenuAction.AppInfo, "")
        assertEquals(1, infos)

        session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(a), 0f, 0f))
        session.menuAction(MenuAction.OpenUninstall, "")
        assertEquals(MenuPage.ConfirmUninstall, session.menu.value?.page)
        session.menuAction(MenuAction.Uninstall, "")
        assertEquals(1, uninstalls)
        assertNull(session.menu.value)
    }

    @Test fun moveToFolderAddsAndRemoveFromFolderDrops() {
        val tools = FolderId("tools")
        val layout = FakeLayoutRepository(ParcLayout(folders = listOf(
            Folder(social, "Social", appIds = listOf(a)), Folder(tools, "Tools"))))
        val navigation = ShellNavigation(ShellState(layer = Layer.Folder, folderId = social))
        val session = newSession(layout = layout, navigation = navigation)
        session.onLongPress(SlotHit(SlotPosition("social/A1", 0), Slot.App(a), 0f, 0f))
        assertEquals(social, session.menu.value?.inFolder)
        session.menuAction(MenuAction.OpenMoveToFolder, "")
        // The folder the app is already in is not offered.
        assertFalse(session.menu.value!!.folders.any { it.id == social })
        session.menuAction(MenuAction.MoveToFolder, tools.value)
        assertTrue(layout.layout.value.folders.first { it.id == tools }.appIds.contains(a))
        assertFalse(layout.layout.value.folders.first { it.id == social }.appIds.contains(a))

        // Remove from folder drops the app out of the folder the launcher is currently inside.
        val back = FakeLayoutRepository(ParcLayout(folders = listOf(Folder(social, "Social", appIds = listOf(a)))))
        val insideSocial = newSession(layout = back,
            navigation = ShellNavigation(ShellState(layer = Layer.Folder, folderId = social)))
        insideSocial.onLongPress(SlotHit(SlotPosition("social/A1", 0), Slot.App(a), 0f, 0f))
        insideSocial.menuAction(MenuAction.RemoveFromFolder, "")
        assertTrue(back.layout.value.folders.first { it.id == social }.appIds.isEmpty())
    }

    @Test fun renameFolderValidatesAndPersists() {
        val layout = FakeLayoutRepository(ParcLayout(folders = listOf(Folder(social, "Social"))))
        val session = newSession(layout = layout)
        session.onLongPress(SlotHit(SlotPosition("A3", 0), Slot.Folder(social), 0f, 0f))
        session.folderAction(MenuAction.OpenRename, "")
        assertEquals(FolderPage.Rename, session.folderMenu.value?.page)
        session.folderNameChanged("WayTooLongName")
        session.folderAction(MenuAction.Save, "")
        assertEquals("At most 8 characters", session.folderMenu.value?.nameIssue)
        assertEquals("Social", layout.layout.value.folders.single().name)

        session.folderNameChanged("Media")
        session.folderAction(MenuAction.Save, "")
        assertEquals("Media", layout.layout.value.folders.single().name)
        assertNull(session.folderMenu.value)
    }

    @Test fun deleteFolderRemovesItsSlotAndReturnsAppsToNew() {
        val layout = FakeLayoutRepository(ParcLayout(
            arcs = geometry.arcs.map { ArcAssignment(it,
                if (it.id == "A3") mapOf(0 to Slot.Folder(social)) else emptyMap()) },
            folders = listOf(Folder(social, "Social", appIds = listOf(a, b)))))
        val session = newSession(layout = layout)
        session.onLongPress(SlotHit(SlotPosition("A3", 0), Slot.Folder(social), 0f, 0f))
        session.folderAction(MenuAction.OpenDelete, "")
        assertEquals(FolderPage.ConfirmDelete, session.folderMenu.value?.page)
        session.folderAction(MenuAction.Delete, "")
        val snapshot = layout.layout.value
        assertTrue(snapshot.folders.none { it.id == social })
        assertEquals(listOf(a, b), snapshot.folders.single { it.id == FolderId("new") }.appIds)
        assertTrue(snapshot.arcs.none { arc -> arc.slots.values.contains(Slot.Folder(social)) })
    }

    @Test fun moveEarlierSwapsNeighbouringFolderSlots() {
        val layout = FakeLayoutRepository(ParcLayout(
            arcs = geometry.arcs.map { ArcAssignment(it,
                if (it.id == "A3") mapOf(0 to Slot.Folder(social), 1 to Slot.Folder(FolderId("tools"))) else emptyMap()) },
            folders = listOf(Folder(social, "Social"), Folder(FolderId("tools"), "Tools"))))
        val session = newSession(layout = layout)
        session.onLongPress(SlotHit(SlotPosition("A3", 1), Slot.Folder(FolderId("tools")), 0f, 0f))
        assertTrue(session.folderMenu.value?.canMoveEarlier == true)
        session.folderAction(MenuAction.MoveEarlier, "")
        val a3 = layout.layout.value.arcs.first { it.arc.id == "A3" }.slots
        assertEquals(Slot.Folder(FolderId("tools")), a3[0])
        assertEquals(Slot.Folder(social), a3[1])
    }

    @Test fun addAppsPageListsCandidatesAndAddsThem() {
        val layout = FakeLayoutRepository(ParcLayout(folders = listOf(Folder(social, "Social", appIds = listOf(a)))))
        val session = newSession(layout = layout)
        session.onLongPress(SlotHit(SlotPosition("A3", 0), Slot.Folder(social), 0f, 0f))
        session.folderAction(MenuAction.AddApps, "")
        assertEquals(FolderPage.AddApps, session.folderMenu.value?.page)
        assertEquals(listOf(entryB), session.folderMenu.value?.candidates)
        session.folderAction(MenuAction.AddApp, b.value)
        assertEquals(listOf(a, b), layout.layout.value.folders.single().appIds)
    }

    // ---- drag onto a folder disc and undo --------------------------------------------------

    @Test fun droppingAnAppOnAFolderDiscAddsItToTheFolderAndUndoRestores() = runTest {
        val layout = FakeLayoutRepository(ParcLayout(
            arcs = geometry.arcs.map { ArcAssignment(it,
                if (it.id == "A1") mapOf(0 to Slot.App(a)) else if (it.id == "A3") mapOf(0 to Slot.Folder(social)) else emptyMap()) },
            folders = listOf(Folder(social, "Social"))))
        val edit = RingEditSession(layout, this)
        val scene = LauncherScenes.build(geometry, layout.layout.value, listOf(entryA), ShellState(), "") { null }
        edit.scene = scene
        edit.enter()
        edit.drop(SlotPosition("A1", 0), SlotPosition("A3", 0))
        runCurrent()
        assertEquals(listOf(a), layout.layout.value.folders.single().appIds)
        assertTrue(edit.undoAvailable)
        edit.undo()
        runCurrent()
        assertTrue(layout.layout.value.folders.single().appIds.isEmpty())
        assertFalse(edit.undoAvailable)
    }
}
