package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.notifications.PeekLine
import com.brightblu.parc.feature.search.LabelSearchEngine
import com.brightblu.parc.feature.search.SearchSheetState
import com.brightblu.parc.feature.settings.ui.SettingKind
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
import org.junit.Assert.*
import org.junit.Test

/** T97: notifications in the app and folder long-press menus. */
@OptIn(ExperimentalCoroutinesApi::class)
class T97Test {
    private val geometry = RingLayout(384f, 853f)
    private val app = AppId("test/App@0")
    private val other = AppId("test/Other@0")
    private val appEntry = AppEntry(app, "Alpha", "test.alpha", false, 0)
    private val otherEntry = AppEntry(other, "Beta", "test.beta", false, 0)
    private val social = FolderId("social")

    private fun line(key: String, title: String = "Title $key", text: String = "Body $key") = PeekLine(key, title, text)

    private fun menu(vararg lines: PeekLine) = MenuModel(app, SlotPosition("A1", 0), 200f, 700f,
        label = "Alpha", notifications = lines.toList())

    private fun labels(items: List<MenuItem>): List<String> = items.map {
        when (it) {
            is MenuItem.Row -> it.row.label
            is MenuItem.Peek -> "peek:${it.line.key}"
            MenuItem.Divider -> "---"
        }
    }

    // ---- composition ---------------------------------------------------------------------

    @Test fun withoutNotificationsTheMenuIsUnchangedAndHasNoEmptySection() {
        assertEquals(listOf("---", "Pin", "Move to folder", "Hide from Parc", "App info", "Uninstall"),
            labels(appMenuRootItems(menu())))
    }

    @Test fun oneNotificationLeadsTheMenuWithoutClearAll() {
        assertEquals(listOf("peek:a", "---", "Pin", "Move to folder", "Hide from Parc", "App info", "Uninstall"),
            labels(appMenuRootItems(menu(line("a")))))
    }

    @Test fun severalNotificationsAddClearAllAndKeepTheOrder() {
        val items = labels(appMenuRootItems(menu(line("new"), line("mid"), line("old"))))
        assertEquals(listOf("peek:new", "peek:mid", "peek:old", "Clear all", "---"), items.take(5))
        val withShortcut = labels(appMenuRootItems(menu(line("new")).copy(shortcuts = listOf(AppShortcut("s1", "Chat", app)))))
        assertEquals(listOf("peek:new", "---", "Chat", "---", "Pin"), withShortcut.take(5))
        assertEquals("Uninstall", items.last())
    }

    @Test fun clearAllCarriesItsOwnAction() {
        val row = appMenuRootItems(menu(line("a"), line("b"))).filterIsInstance<MenuItem.Row>()
            .first { it.row.label == "Clear all" }
        assertEquals(MenuAction.ClearNotifications, row.row.action)
    }

    @Test fun folderNoticesCountAppsAndSortMostFirst() {
        val apps = listOf(appEntry, otherEntry, AppEntry(AppId("test/None@0"), "None", "test.none", false, 0))
        val notices = folderNotices(apps, mapOf("test.alpha" to 1, "test.beta" to 4, "other.pkg" to 9))
        assertEquals(listOf("Beta" to 4, "Alpha" to 1), notices.map { it.label to it.count })
        assertEquals(emptyList<FolderNotice>(), folderNotices(apps, emptyMap()))
    }

    @Test fun theFolderMenuListsAppsWithNotificationsAboveTheUsualRows() {
        val menu = FolderMenuModel(social, "Social", SlotPosition("A3", 0), 0f, 0f,
            notices = listOf(FolderNotice(other, "Beta", 4), FolderNotice(app, "Alpha", 1)))
        val items = labels(folderMenuRootItems(menu))
        assertEquals(listOf("Beta · 4", "Alpha · 1", "---", "Rename"), items.take(4))
        val first = folderMenuRootItems(menu).first() as MenuItem.Row
        assertEquals(MenuAction.LaunchApp, first.row.action)
        assertEquals(other.value, first.row.detail)
        assertEquals(listOf("Rename", "Add apps", "Sort A to Z", "Move earlier", "Move later", "Delete"),
            labels(folderMenuRootItems(menu.copy(notices = emptyList()))))
    }

    // ---- dismiss flow with fakes ---------------------------------------------------------

    private class FakeSource(var lines: List<PeekLine>, var counts: Map<String, Int> = emptyMap()) : MenuNotificationSource {
        val opened = mutableListOf<String>()
        val dismissed = mutableListOf<String>()
        val clearedPackages = mutableListOf<String>()
        var openWorks = true
        var throwing = false
        override fun lines(packageName: String): List<PeekLine> {
            if (throwing) throw IllegalStateException("boom")
            return lines
        }
        override fun counts(): Map<String, Int> {
            if (throwing) throw IllegalStateException("boom")
            return counts
        }
        override fun open(key: String): Boolean { opened += key; return openWorks }
        override fun dismiss(key: String) { dismissed += key }
        override fun dismissAll(packageName: String) { clearedPackages += packageName }
    }

    private class Index(entries: List<AppEntry>) : AppIndex {
        override val apps = MutableStateFlow(entries)
        override fun icon(id: AppId, sizePx: Int): android.graphics.Bitmap? = null
        override fun launch(id: AppId) { launched += id }
        override fun shortcuts(id: AppId) = emptyList<AppShortcut>()
        val launched = mutableListOf<AppId>()
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

    private fun layout() = FakeLayoutRepository(ParcLayout(
        arcs = geometry.arcs.map { ArcAssignment(it, if (it.id == "A1") mapOf(0 to Slot.App(app), 1 to Slot.Folder(social)) else emptyMap()) },
        folders = listOf(Folder(social, "Social", appIds = listOf(app, other)))))

    private fun session(source: MenuNotificationSource?, index: Index = Index(listOf(appEntry, otherEntry))): LauncherSession {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val search = SearchSheetState(LabelSearchEngine(), index, Usage(), scope)
        return LauncherSession(ShellNavigation(), index, Usage(), layout(), search, {}, {}, Shortcuts(), scope,
            UnconfinedTestDispatcher(), menuNotifications = source)
    }

    private fun hold(session: LauncherSession) =
        session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(app), 0f, 0f))

    @Test fun holdingAnAppLoadsItsNotificationsIntoTheMenu() {
        val s = session(FakeSource(listOf(line("a"), line("b"))))
        hold(s)
        assertEquals(listOf("a", "b"), s.menu.value?.notifications?.map { it.key })
    }

    @Test fun neverMoreThanThreeRowsEvenIfTheSourceReturnsMore() {
        val s = session(FakeSource((1..6).map { line("k$it") }))
        hold(s)
        assertEquals(3, s.menu.value?.notifications?.size)
    }

    @Test fun noSourceOrASourceWithNothingLeavesTheMenuUnchanged() {
        val none = session(null)
        hold(none)
        assertTrue(none.menu.value!!.notifications.isEmpty())
        val empty = session(FakeSource(emptyList()))
        hold(empty)
        assertFalse(labels(appMenuRootItems(empty.menu.value!!)).any { it.startsWith("peek:") })
    }

    @Test fun aFailingSourceNeverBreaksTheMenu() {
        val source = FakeSource(listOf(line("a"))).apply { throwing = true }
        val s = session(source)
        hold(s)
        assertNotNull(s.menu.value)
        assertTrue(s.menu.value!!.notifications.isEmpty())
    }

    @Test fun dismissingOneRowCancelsItAndKeepsTheMenuOpen() {
        val source = FakeSource(listOf(line("a"), line("b")))
        val s = session(source)
        hold(s)
        s.menuAction(MenuAction.DismissNotification, "a")
        assertEquals(listOf("a"), source.dismissed)
        assertEquals(listOf("b"), s.menu.value?.notifications?.map { it.key })
        // Dismissing the last one removes the whole section.
        s.menuAction(MenuAction.DismissNotification, "b")
        assertTrue(s.menu.value!!.notifications.isEmpty())
        assertFalse(labels(appMenuRootItems(s.menu.value!!)).any { it.startsWith("peek:") || it == "Clear all" })
    }

    @Test fun clearAllDismissesTheAppsNotificationsAndEmptiesTheSection() {
        val source = FakeSource(listOf(line("a"), line("b")))
        val s = session(source)
        hold(s)
        s.menuAction(MenuAction.ClearNotifications, "")
        assertEquals(listOf("test.alpha"), source.clearedPackages)
        assertTrue(s.menu.value!!.notifications.isEmpty())
    }

    @Test fun tappingARowOpensItAndClosesTheMenu() {
        val source = FakeSource(listOf(line("a")))
        val s = session(source)
        hold(s)
        s.menuAction(MenuAction.OpenNotification, "a")
        assertEquals(listOf("a"), source.opened)
        assertNull(s.menu.value)
    }

    @Test fun aNotificationThatCannotBeOpenedDropsOutOfTheMenuInsteadOfClosingIt() {
        val source = FakeSource(listOf(line("a"), line("b"))).apply { openWorks = false }
        val s = session(source)
        hold(s)
        s.menuAction(MenuAction.OpenNotification, "a")
        assertEquals(listOf("b"), s.menu.value?.notifications?.map { it.key })
    }

    @Test fun holdingAFolderListsAppsWithNotificationsAndTappingOneLaunchesIt() {
        val index = Index(listOf(appEntry, otherEntry))
        val s = session(FakeSource(emptyList(), mapOf("test.alpha" to 1, "test.beta" to 3)), index)
        s.onLongPress(SlotHit(SlotPosition("A1", 1), Slot.Folder(social), 0f, 0f))
        val notices = s.folderMenu.value!!.notices
        assertEquals(listOf("Beta" to 3, "Alpha" to 1), notices.map { it.label to it.count })
        s.folderAction(MenuAction.LaunchApp, other.value)
        assertNull(s.folderMenu.value)
        assertEquals(listOf(other), index.launched)
    }

    @Test fun aFolderWithoutNotificationsHasNoSection() {
        val s = session(FakeSource(emptyList(), emptyMap()))
        s.onLongPress(SlotHit(SlotPosition("A1", 1), Slot.Folder(social), 0f, 0f))
        assertTrue(s.folderMenu.value!!.notices.isEmpty())
    }

    // ---- settings and backup -------------------------------------------------------------


    @Test fun theTwoSettingsTravelInTheBackup() {
        val names = BackupSpecs.uiPrefs.keys.map { it.name }.toSet()
        assertTrue("menu_notifications" in names)
        assertTrue("menu_notification_text" in names)
        assertEquals(com.brightblu.parc.core.data.backup.BackupValueType.Bool,
            BackupSpecs.uiPrefs.key("menu_notifications")?.type)
    }
}
