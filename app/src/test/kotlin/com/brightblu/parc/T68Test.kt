package com.brightblu.parc

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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** T68: the fuller long-press menu, by app kind, folder sort and new-folder placement. */
@OptIn(ExperimentalCoroutinesApi::class)
class T68Test {
    private val geometry = RingLayout(384f, 853f)
    private val app = AppId("test/App@0")
    private val other = AppId("test/Other@0")
    private val delta = AppId("test/Delta@0")
    private val alphaEntry = AppEntry(app, "alpha", "test.alpha", false, 0)
    private val zuluEntry = AppEntry(other, "Zulu", "test.zulu", false, 0)
    private val deltaEntry = AppEntry(delta, "delta", "test.delta", false, 0)
    private val social = FolderId("social")
    private val tools = FolderId("tools")

    private fun rows(items: List<MenuItem>): List<String> =
        items.mapNotNull { (it as? MenuItem.Row)?.row?.label }

    private fun actions(items: List<MenuItem>): List<MenuAction> =
        items.mapNotNull { (it as? MenuItem.Row)?.row?.action }

    private fun slotApp(id: AppId) = Slot.App(id)

    /** A home layout from the reference geometry, with only the named slots filled. */
    private fun layoutWith(
        slots: Map<String, Map<Int, Slot>> = emptyMap(),
        folders: List<Folder> = emptyList(),
        pins: Set<AppId> = emptySet(),
    ) = ParcLayout(
        arcs = geometry.arcs.map { ArcAssignment(it, slots[it.id].orEmpty()) },
        folders = folders,
        pins = pins,
    )

    private fun fullSlots(id: String): Map<Int, Slot> {
        val arc = geometry.arcs.first { it.id == id }
        return (0 until arc.slotCapacity).associateWith { slotApp(AppId("test/filler$it@0")) }
    }

    private fun rootMenu(
        id: AppId = app,
        position: SlotPosition = SlotPosition("A1", 0),
        pinned: Boolean = false,
        inFolder: FolderId? = null,
        pinLabel: String = "Pin",
        canUninstall: Boolean = true,
        shortcuts: List<AppShortcut> = emptyList(),
    ) = MenuModel(id, position, 200f, 700f, shortcuts = shortcuts, pinned = pinned, label = "Alpha",
        inFolder = inFolder, pinLabel = pinLabel, canUninstall = canUninstall)

    // ---- rows by app kind ------------------------------------------------------------------

    @Test fun aUserAppShowsEveryRowWithHideAndUninstall() {
        val items = appMenuRootItems(rootMenu())
        assertEquals(listOf("Pin", "Move to folder", "Hide from Parc", "App info", "Uninstall"), rows(items))
        assertTrue(items.contains(MenuItem.Divider))
    }

    @Test fun aSystemAppWithoutAnUpdateHidesUninstall() {
        assertEquals(listOf("Pin", "Move to folder", "Hide from Parc", "App info"),
            rows(appMenuRootItems(rootMenu(canUninstall = false))))
    }

    @Test fun theUninstallRuleKeepsUserAppsAndUpdatedSystemApps() {
        assertTrue(canOfferUninstall(isSystem = false, updatedSystemApp = false))
        assertTrue(canOfferUninstall(isSystem = false, updatedSystemApp = true))
        assertTrue(canOfferUninstall(isSystem = true, updatedSystemApp = true))
        assertFalse(canOfferUninstall(isSystem = true, updatedSystemApp = false))
    }

    @Test fun aPinnedAppShowsUnpinAndAnAppWithoutAFreeSlotShowsNoPinRow() {
        assertEquals("Unpin", rows(appMenuRootItems(rootMenu(pinned = true))).first())
        // An empty pin label means every pinned slot is taken, so the row is dropped.
        assertFalse(rows(appMenuRootItems(rootMenu(pinLabel = ""))).contains("Pin"))
        assertTrue(rows(appMenuRootItems(rootMenu(pinLabel = ""))).contains("Move to folder"))
    }

    @Test fun anAppInsideAFolderOffersRemoveFromFolder() {
        val inside = appMenuRootItems(rootMenu(inFolder = social))
        assertTrue(rows(inside).contains("Remove from folder"))
        assertEquals(listOf("Pin", "Move to folder", "Remove from folder", "Hide from Parc", "App info", "Uninstall"),
            rows(inside))
    }

    @Test fun shortcutRowsStayFirstOnEveryAppKind() {
        val shortcuts = listOf(AppShortcut("s1", "Chat with Sam", app), AppShortcut("s2", "New note", app))
        for (menu in listOf(rootMenu(shortcuts = shortcuts), rootMenu(pinned = true, shortcuts = shortcuts),
            rootMenu(inFolder = social, shortcuts = shortcuts), rootMenu(canUninstall = false, shortcuts = shortcuts))) {
            val items = appMenuRootItems(menu)
            assertEquals("Chat with Sam", rows(items).first())
            assertEquals("New note", rows(items)[1])
            assertEquals(MenuAction.Shortcut, actions(items).first())
        }
    }

    @Test fun thePinRowNamesTheArcTheAppWillLandOn() {
        // Arc 1 free, the app sits elsewhere: the row names arc 1.
        assertEquals("Pin to arc 1", pinRowLabel(layoutWith(), SlotPosition("A3", 0)))
        // Arc 1 full: it names arc 2.
        val arcOneFull = layoutWith(slots = mapOf("A1" to fullSlots("A1")))
        assertEquals("Pin to arc 2", pinRowLabel(arcOneFull, SlotPosition("A3", 0)))
        // Both pinned arcs full: no row at all.
        val pinnedFull = layoutWith(slots = mapOf("A1" to fullSlots("A1"), "A2" to fullSlots("A2")))
        assertEquals("", pinRowLabel(pinnedFull, SlotPosition("A3", 0)))
    }

    @Test fun anUnpinnedAppAlreadyOnAPinnedArcPinsInPlace() {
        val opposite = SlotPosition("A1", 2)
        assertEquals("Pin to arc 1", pinRowLabel(layoutWith(), opposite))
        assertEquals(opposite, pinPosition(layoutWith(), opposite))
    }

    // ---- folder rows ---------------------------------------------------------------------

    @Test fun theFolderMenuOffersRenameSortAndDelete() {
        val folder = FolderMenuModel(social, "Social", SlotPosition("A3", 0), 0f, 0f)
        assertEquals(listOf("Rename", "Add apps", "Sort A to Z", "Move earlier", "Move later", "Delete"),
            rows(folderMenuRootItems(folder)))
    }

    @Test fun sortFolderAppsIgnoresCaseAndBreaksTiesByID() {
        val labels = mapOf(app to "beta", other to "Alpha", delta to "alpha")
        // "Alpha" and "alpha" tie on the folded label, so the id decides between them.
        assertEquals(listOf(delta, other, app), sortFolderApps(listOf(app, other, delta)) { labels[it].orEmpty() })
        // Same label twice: the id decides, so the result is stable.
        assertEquals(listOf(stateId("a/A@0"), stateId("b/A@0")),
            sortFolderApps(listOf(stateId("b/A@0"), stateId("a/A@0"))) { "Same" })
    }

    private fun stateId(value: String) = AppId(value)

    @Test fun theMovePageEndsWithNewFolderAndBack() {
        val menu = rootMenu().copy(folders = listOf(Folder(social, "Social"), Folder(tools, "Tools")))
        assertEquals(listOf("Social", "Tools", "New folder", "Back"), rows(appMenuFolderItems(menu)))
        assertEquals(MenuAction.OpenNewFolder, actions(appMenuFolderItems(menu))[2])
    }

    // ---- new folder ----------------------------------------------------------------------

    @Test fun aNewFolderTakesTheFirstFreeHomeSlotAndMovesTheAppIn() {
        val layout = layoutWith(
            slots = mapOf("A1" to mapOf(0 to slotApp(other))),
            folders = listOf(Folder(social, "Social", appIds = listOf(app, other))))
        val plan = requireNotNull(newFolderLayout(layout, "  Media  ", app))
        val created = plan.folders.single { it.name == "Media" }
        assertEquals(listOf(app), created.appIds)
        assertEquals("f1", created.id.value)
        // The app left the folder it was in.
        assertFalse(plan.folders.first { it.id == social }.appIds.contains(app))
        // The folder takes the lowest vacant home slot (A1 slot 1) and nothing else moves.
        assertEquals(Slot.Folder(created.id), plan.arcs.first { it.arc.id == "A1" }.slots[1])
        assertEquals(Slot.App(other), plan.arcs.first { it.arc.id == "A1" }.slots[0])
    }

    @Test fun folderKeysNeverCollide() {
        val layout = layoutWith(folders = listOf(Folder(FolderId("f1"), "One"), Folder(FolderId("f2"), "Two")))
        assertEquals(FolderId("f3"), nextFolderId(layout))
    }

    @Test fun aFullRingCannotTakeANewFolder() {
        val layout = layoutWith(slots = geometry.arcs.associate { it.id to fullSlots(it.id) })
        assertNull(newFolderLayout(layout, "Media", app))
    }

    // ---- session dispatch ----------------------------------------------------------------

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
        override fun isDefaultHome(): Boolean = true
        override fun launchShortcut(appId: AppId, shortcutId: String) = Unit
    }

    private fun newSession(
        layout: LayoutRepository,
        entries: List<AppEntry> = listOf(alphaEntry, zuluEntry, deltaEntry),
        uninstallable: (AppEntry) -> Boolean = { true },
        navigation: ShellNavigation = ShellNavigation(),
    ): LauncherSession {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val index = Index(entries)
        val search = SearchSheetState(LabelSearchEngine(), index, Usage(), scope)
        return LauncherSession(navigation, index, Usage(), layout, search, {}, {}, Shortcuts(), scope,
            UnconfinedTestDispatcher(), uninstallable = uninstallable)
    }

    @Test fun theSessionHidesUninstallForASystemAppAndKeepsItForAUserApp() {
        val layout = FakeLayoutRepository(layoutWith(slots = mapOf("A1" to mapOf(0 to slotApp(app)))))
        val system = AppEntry(app, "Alpha", "test.alpha", isSystem = true, installTime = 0)
        val session = newSession(layout, entries = listOf(system), uninstallable = { false })
        session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(app), 0f, 0f))
        assertFalse(rows(appMenuRootItems(requireNotNull(session.menu.value))).contains("Uninstall"))

        val userSession = newSession(layout)
        userSession.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(app), 0f, 0f))
        assertTrue(rows(appMenuRootItems(requireNotNull(userSession.menu.value))).contains("Uninstall"))
    }

    @Test fun theSessionComputesThePinLabelFromTheLiveLayout() = runTest {
        val layout = FakeLayoutRepository(layoutWith(slots = mapOf("A1" to fullSlots("A1"))))
        val session = newSession(layout)
        session.onLongPress(SlotHit(SlotPosition("A3", 0), Slot.App(app), 0f, 0f))
        assertEquals("Pin to arc 2", session.menu.value?.pinLabel)
    }

    @Test fun creatingAFolderFromTheMenuOpensTheSheetAndWritesTheLayout() {
        val layout = FakeLayoutRepository(layoutWith(
            slots = mapOf("A1" to mapOf(0 to slotApp(app))),
            folders = listOf(Folder(social, "Social", appIds = listOf(app)))))
        val session = newSession(layout)
        session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(app), 0f, 0f))
        session.menuAction(MenuAction.OpenMoveToFolder, "")
        assertEquals(MenuPage.MoveToFolder, session.menu.value?.page)
        session.menuAction(MenuAction.OpenNewFolder, "")
        assertEquals(MenuPage.NewFolder, session.menu.value?.page)

        // An empty name is refused with the same rule as the folder rename.
        session.menuAction(MenuAction.SaveNewFolder, "")
        assertEquals("Name cannot be empty", session.menu.value?.nameIssue)
        assertEquals(1, layout.layout.value.folders.size)

        session.menuNameChanged("Media")
        assertEquals("", session.menu.value?.nameIssue)
        session.menuAction(MenuAction.SaveNewFolder, "")
        assertNull(session.menu.value)
        val created = layout.layout.value.folders.single { it.name == "Media" }
        assertEquals(listOf(app), created.appIds)
        assertFalse(layout.layout.value.folders.first { it.id == social }.appIds.contains(app))
    }

    @Test fun cancellingTheNewFolderSheetReturnsToTheFolderList() {
        val layout = FakeLayoutRepository(layoutWith(folders = listOf(Folder(social, "Social"))))
        val session = newSession(layout)
        session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(app), 0f, 0f))
        session.menuAction(MenuAction.OpenMoveToFolder, "")
        session.menuAction(MenuAction.OpenNewFolder, "")
        session.menuAction(MenuAction.CancelNewFolder, "")
        assertEquals(MenuPage.MoveToFolder, session.menu.value?.page)
        assertEquals("", session.menu.value?.draftName)
        assertTrue(layout.layout.value.folders.none { it.name.isEmpty() })
    }

    @Test fun sortAZReordersTheFolderByLabel() {
        val layout = FakeLayoutRepository(layoutWith(
            slots = mapOf("A3" to mapOf(0 to Slot.Folder(social))),
            folders = listOf(Folder(social, "Social", appIds = listOf(other, app, delta)))))
        val session = newSession(layout)
        session.onLongPress(SlotHit(SlotPosition("A3", 0), Slot.Folder(social), 0f, 0f))
        session.folderAction(MenuAction.SortAZ, "")
        assertEquals(listOf(app, delta, other), layout.layout.value.folders.single { it.id == social }.appIds)
        // The folder keeps its slot and the rest of the document is untouched.
        assertEquals(Slot.Folder(social), layout.layout.value.arcs.first { it.arc.id == "A3" }.slots[0])
    }

    @Test fun sortingAnUnknownFolderIsANoOp() = runTest {
        val layout = FakeLayoutRepository(layoutWith(folders = listOf(Folder(social, "Social"))))
        val before = layout.layout.value
        val session = newSession(layout)
        session.folderAction(MenuAction.SortAZ, "")
        assertEquals(before, layout.layout.value)
    }
}
