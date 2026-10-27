package com.brightblu.parc

import com.brightblu.parc.core.data.lock.LockedFolderText
import com.brightblu.parc.core.data.profiles.LaunchOutcome
import com.brightblu.parc.feature.search.SearchSheetState
import com.brightblu.parc.model.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Activity-owned navigation adapter. All launch sources share suspension checks and the usage hook. */
class LauncherSession internal constructor(
    val navigation: ShellNavigation,
    private val apps: AppIndex,
    private val usage: UsageRepository,
    private val layout: LayoutRepository,
    val search: SearchSheetState,
    private val hideKeyboard: () -> Unit,
    private val openSettings: () -> Unit = {},
    private val shortcutHost: ShortcutHost,
    private val scope: CoroutineScope,
    private val shortcutDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val launchLink: (Slot.Link) -> Unit = {},
    private val launchPinnedShortcut: (Slot.Shortcut) -> Unit = {},
    private val closeEditing: () -> Boolean = { false },
    private val launchApp: ((AppId) -> LaunchOutcome)? = null,
    /** T75: a refused profile launch (paused or removed) is explained, never silent. */
    private val onLaunchRefused: (LaunchOutcome) -> Unit = {},
    private val onPinnedLaunch: () -> Unit = {},
    private val onMenuOpened: () -> Unit = {},
    private val onRotation: (String, Float) -> Unit = { _, _ -> },
    private val openAndroidSettings: () -> Unit = {},
    private val appInfo: (AppId) -> Unit = {},
    private val uninstall: (AppId) -> Unit = {},
    private val uninstallable: (AppEntry) -> Boolean = { true },
    private val onAppLaunched: (AppId) -> Unit = {},
    private val lastApp: () -> AppId? = { null },
    hubDoubleTapEnabled: Boolean = true,
    hubTapClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val gestures: () -> GestureConfig = { GestureConfig() },
    private val enterEditMode: () -> Unit = {},
    private val hubPage: (String) -> Unit = {},
    private val hubPages: () -> List<String> = { HubCatalog.pages },
    private val shortcutAllowed: (AppId) -> Boolean = { true },
    /** T97: notifications in the long-press menus; null leaves both menus exactly as before. */
    private val menuNotifications: MenuNotificationSource? = null,
    /** T98: locked folders. Null means locking is not offered and nothing is ever hidden. */
    private val lockGate: FolderLockGate? = null,
    /** OPUS8 (T133): opens Parc settings on a page (Home menu rows and Settings search results). */
    private val openSettingsAt: (SettingsTarget) -> Unit = { openSettings() },
) : RingCallbacks {
    private val hubTaps = HubTapDispatcher(scope, hubDoubleTapEnabled, hubTapClock,
        singleTap = ::toggleAllApps, doubleTap = { lastApp()?.let(::launch) })

    fun setHubDoubleTap(enabled: Boolean) { hubTaps.setEnabled(enabled) }
    fun cancelHubTap() { hubTaps.cancel() }

    private val _shortcutMenu = MutableStateFlow<Slot.Shortcut?>(null)
    val shortcutMenu: StateFlow<Slot.Shortcut?> = _shortcutMenu.asStateFlow()
    fun dismissShortcutMenu() { _shortcutMenu.value = null }

    private val _menu = MutableStateFlow<MenuModel?>(null)
    val menu: StateFlow<MenuModel?> = _menu.asStateFlow()

    private val _folderMenu = MutableStateFlow<FolderMenuModel?>(null)
    val folderMenu: StateFlow<FolderMenuModel?> = _folderMenu.asStateFlow()

    /** OPUS8 (T133): the small Home menu opened by a long-press on empty space. */
    private val _homeMenu = MutableStateFlow<HomeMenuModel?>(null)
    internal val homeMenu: StateFlow<HomeMenuModel?> = _homeMenu.asStateFlow()
    fun dismissHomeMenu() { _homeMenu.value = null }

    /**
     * OPUS8 (T133): counts Home key presses. The host and the hub watch it to close their own layers
     * (Settings, sheets, hub page options) and to go back to the first hub page.
     */
    private val _homePresses = MutableStateFlow(0)
    val homePresses: StateFlow<Int> = _homePresses.asStateFlow()

    fun launch(id: AppId) {
        // T98: the narrowest launch point (arcs, All apps, search, last app, gestures, hub cards).
        if (lockGate?.isAppLocked(id) == true) return
        val app = apps.apps.value.firstOrNull { it.id == id } ?: return
        if (app.suspended) return
        val pinned = id in layout.layout.value.pins
        reset()
        // T74/T75: the source decides by the id's user serial, so a work app starts in its own
        // profile and a paused profile is reported instead of throwing.
        val outcome = launchApp?.invoke(id) ?: run { apps.launch(id); LaunchOutcome.Started }
        if (outcome == LaunchOutcome.Started) {
            usage.recordLaunch(id)
            onAppLaunched(id)
            if (pinned) onPinnedLaunch()
        } else {
            onLaunchRefused(outcome)
        }
    }

    fun onHostPaused() { hubTaps.cancel(); closeEditing() }

    /**
     * OPUS8 (T133): the Home key (a new HOME intent, whether Parc was on screen or another app was).
     * The ONE place that closes every layer: the session's own (menus, search, folder, All apps,
     * edit mode) through [reset], then the host's and the hub's through [homePresses]. On an
     * already clean Home this still counts, so the hub goes back to its first page.
     */
    fun home() {
        reset()
        _homePresses.value = _homePresses.value + 1
    }

    fun reset() {
        hubTaps.cancel()
        dismissShortcutMenu()
        dismissMenu()
        dismissFolderMenu()
        dismissHomeMenu()
        closeEditing()
        hideKeyboard()
        search.close()
        navigation.emit(ShellEvent.ResetToBase)
    }

    fun keyboard(visible: Boolean) {
        val current = navigation.state.value
        // Android handles Back in the IME before the Activity callback. Close its sheet too.
        if (!visible && current.keyboardOpen && search.isOpen && !search.state.value.abcListOpen) {
            dismissSearch()
            return
        }
        navigation.show(current.copy(keyboardOpen = visible && current.searchOpen && !current.abcListOpen))
    }

    fun back() {
        hubTaps.cancel()
        if (closeEditing()) return
        if (_shortcutMenu.value != null) { dismissShortcutMenu(); return }
        if (_homeMenu.value != null) { dismissHomeMenu(); return }
        if (_folderMenu.value != null) {
            dismissFolderMenu()
            return
        }
        if (_menu.value != null) {
            dismissMenu()
            return
        }
        if (search.isOpen && !search.state.value.abcListOpen) {
            dismissSearch()
            return
        }
        if (navigation.state.value.keyboardOpen) hideKeyboard()
        navigation.emit(ShellEvent.Back)
        val next = navigation.state.value
        if (!next.searchOpen) search.close()
        else if (!next.abcListOpen) search.closeAbcList()
    }

    fun dismissSearch() {
        hideKeyboard()
        search.close()
        syncSearch()
    }

    fun syncSearch() {
        val ui = search.state.value
        val current = navigation.state.value
        if (ui.abcListOpen) hideKeyboard()
        navigation.show(current.copy(searchOpen = ui.isOpen, abcListOpen = ui.abcListOpen,
            keyboardOpen = current.keyboardOpen && ui.isOpen && !ui.abcListOpen))
    }

    override fun onSearch() {
        hubTaps.cancel()
        if (search.state.value.abcListOpen) {
            search.closeAbcList()
            syncSearch()
        } else if (search.isOpen) dismissSearch()
        else {
            search.open()
            syncSearch()
        }
    }

    fun allApps() {
        hubTaps.cancel()
        search.open()
        search.openAbcList()
        syncSearch()
    }

    override fun onHubTap() {
        if (search.isOpen) return
        hubTaps.tap()
    }

    /**
     * T69: the configured swipe-up action. Only armed by the host when the gesture has an action
     * and starts on empty space, so this is the single dispatch point.
     */
    fun onSwipeUp() = performGesture(gestures().swipeUp)

    /** T69: the configured hub long-press action; the default keeps entering edit mode. */
    fun onHubLongPress() = performGesture(gestures().hubLongPress)

    private fun performGesture(action: GestureAction) {
        when (val target = resolveGesture(action, hubPages()) { id -> apps.apps.value.any { it.id == id } }) {
            GestureTarget.Nothing -> Unit
            // OPUS1: the action is called "All apps view", which is the arc view (T29), not the
            // alphabetical text list; that list stays on the search sheet's "All apps" link.
            GestureTarget.AllApps -> showAllAppsView()
            GestureTarget.Search -> openSearch()
            GestureTarget.LastApp -> lastApp()?.let(::launch)
            GestureTarget.EditMode -> enterEditMode()
            is GestureTarget.HubPage -> {
                // The hub only exists on Home; leave a folder or All apps first.
                if (navigation.state.value.layer != Layer.Home) reset()
                hubPage(target.page)
            }
            is GestureTarget.OpenApp -> launch(target.appId)
        }
    }

    private fun openSearch() {
        if (search.isOpen) return
        search.open()
        syncSearch()
    }

    /** OPUS1: a gesture shows (or, if already shown, leaves) the All-apps arcs, from anywhere. */
    private fun showAllAppsView() {
        hubTaps.cancel()
        if (search.isOpen) dismissSearch()
        val layer = navigation.state.value.layer
        navigation.show(ShellState(layer = if (layer == Layer.AllApps) Layer.Home else Layer.AllApps))
    }

    override fun onHubBack() { hubTaps.cancel(); toggleAllApps() }

    private fun toggleAllApps() {
        if (search.isOpen) return
        val current = navigation.state.value
        navigation.show(ShellState(layer = when (current.layer) {
            Layer.Home -> Layer.AllApps
            Layer.AllApps -> Layer.Home
            else -> Layer.Home // In a folder (or the retired Recents) the hub tap goes back.
        }))
    }

    override fun onTap(hit: SlotHit) {
        hubTaps.cancel()
        if (search.isOpen) return
        when (val slot = hit.slot) {
            is Slot.App -> launch(slot.id)
            is Slot.Folder -> openFolder(slot.id)
            is Slot.Action -> when (slot.kind) {
                ActionKind.More, ActionKind.AbcList -> allApps()
                ActionKind.Settings -> openSettings()
            }
            is Slot.Link -> launchLink(slot)
            is Slot.Shortcut -> launchPinnedShortcut(slot)
        }
    }

    /**
     * OPUS6 (audit F1): the folder menu's "Add apps" list. Apps hidden by the layout, already in the
     * folder, or inside a folder that is locked right now (by id, and every app of the same package)
     * are never offered.
     */
    internal fun addAppCandidates(all: List<AppEntry>, folder: Folder?, hidden: Set<AppId>): List<AppEntry> {
        val gate = lockGate
        val lockedPackages = if (gate == null) emptySet()
            else all.filter { gate.isAppLocked(it.id) }.mapTo(HashSet()) { it.packageName }
        return all.filterNot {
            it.id in folder?.appIds.orEmpty() || it.id in hidden || it.packageName in lockedPackages ||
                gate?.isAppLocked(it.id) == true
        }
    }

    /** T98: a locked folder asks for authentication first, then opens; others open directly. */
    private fun openFolder(id: FolderId) {
        val gate = lockGate
        if (gate != null && gate.isFolderLockedNow(id)) {
            gate.authenticateFolder(id) {
                if (navigation.state.value.layer == Layer.Home) {
                    navigation.show(ShellState(layer = Layer.Folder, folderId = id))
                }
            }
        } else {
            navigation.show(ShellState(layer = Layer.Folder, folderId = id))
        }
    }

    /**
     * Long-press routing (T30): app slots open the app menu, folder slots open the folder menu, the
     * Settings gear opens Android Settings. Only the hub hold enters edit mode in the renderer.
     */
    override fun onLongPress(hit: SlotHit) {
        hubTaps.cancel()
        if (search.isOpen) return
        when (val slot = hit.slot) {
            is Slot.App -> openAppMenu(slot.id, hit)
            is Slot.Folder -> openFolderMenu(slot.id, hit)
            is Slot.Action -> if (slot.kind == ActionKind.Settings) openAndroidSettings()
            is Slot.Link -> Unit
            is Slot.Shortcut -> { dismissMenu(); dismissFolderMenu(); _shortcutMenu.value = slot; onMenuOpened() }
        }
    }

    /**
     * OPUS8 (T133): a long-press on empty Home space (the renderer applies [com.brightblu.parc.ring.EmptySpacePress]).
     * Nothing opens while search, another menu or a folder/All-apps view is up.
     */
    fun onEmptyLongPress(xDp: Float, yDp: Float) {
        hubTaps.cancel()
        if (search.isOpen || navigation.state.value.layer != Layer.Home) return
        if (_menu.value != null || _folderMenu.value != null || _shortcutMenu.value != null) return
        _homeMenu.value = HomeMenuModel(xDp, yDp)
        onMenuOpened()
    }

    /** OPUS8 (T133): one Home menu row. */
    internal fun homeMenuAction(row: HomeMenuRow) {
        if (_homeMenu.value == null) return
        dismissHomeMenu()
        when (row) {
            HomeMenuRow.EditHome -> enterEditMode()
            else -> openSettingsAt(row.target ?: return)
        }
    }

    private fun openAppMenu(id: AppId, hit: SlotHit) {
        if (lockGate?.isAppLocked(id) == true) return
        val entry = apps.apps.value.firstOrNull { it.id == id }
        val pinned = id in layout.layout.value.pins
        val isDefaultHome = shortcutHost.isDefaultHome()
        val inFolder = navigation.state.value.folderId.takeIf { navigation.state.value.layer == Layer.Folder }
        _menu.value = MenuModel(id, hit.position, hit.xDp, hit.yDp, emptyList(), pinned, isDefaultHome,
            label = entry?.label.orEmpty(), inFolder = inFolder, hidden = id in layout.layout.value.hidden,
            pinLabel = pinRowLabel(layout.layout.value, hit.position),
            canUninstall = entry != null && uninstallable(entry),
            notifications = entry?.let { peekLines(it.packageName) }.orEmpty())
        onMenuOpened()
        if (isDefaultHome) {
            scope.launch(shortcutDispatcher) {
                val shortcuts = apps.shortcuts(id)
                // Guard against a newer menu (or a dismissal) arriving before the load completes.
                _menu.value = _menu.value?.takeIf { it.appId == id }?.copy(shortcuts = shortcuts)
            }
        }
    }

    fun dismissMenu() {
        _menu.value = null
    }

    /** Never throws: a failing source just means no notifications section. */
    private fun peekLines(packageName: String): List<com.brightblu.parc.core.data.notifications.PeekLine> =
        try {
            menuNotifications?.lines(packageName).orEmpty().take(com.brightblu.parc.core.data.notifications.PeekRules.MaxPerApp)
        } catch (e: Exception) {
            emptyList()
        }

    private fun menuPackage(id: AppId): String? = apps.apps.value.firstOrNull { it.id == id }?.packageName

    /** New-folder draft edits (T68); the issue line clears as soon as the name changes. */
    fun menuNameChanged(text: String) {
        val open = _menu.value ?: return
        if (open.page != MenuPage.NewFolder) return
        _menu.value = open.copy(draftName = text, nameIssue = "")
    }

    fun launchShortcut(shortcut: AppShortcut) {
        if (lockGate?.isAppLocked(shortcut.appId) == true) { dismissMenu(); return }
        if (!shortcutAllowed(shortcut.appId)) { launch(shortcut.appId); return }
        dismissMenu()
        usage.recordLaunch(shortcut.appId)
        onAppLaunched(shortcut.appId)
        shortcutHost.launchShortcut(shortcut.appId, shortcut.id)
    }

    fun togglePin() {
        val open = _menu.value ?: return
        val id = open.appId
        dismissMenu()
        scope.launch {
            if (id in layout.layout.value.pins) {
                layout.unpin(id)
            } else {
                pinPosition(layout.layout.value, open.position)?.let { layout.pin(id, it) }
            }
        }
    }

    /** Dispatches one app-menu row. Pure switch, so every row is unit-testable through the session. */
    fun menuAction(action: MenuAction, detail: String) {
        val open = _menu.value ?: return
        when (action) {
            MenuAction.Dismiss -> dismissMenu()
            MenuAction.Back -> _menu.value = open.copy(page = MenuPage.Root)
            MenuAction.OpenNotification -> {
                val opened = try { menuNotifications?.open(detail) == true } catch (e: Exception) { false }
                if (opened) dismissMenu()
                else _menu.value = open.copy(notifications = open.notifications.filterNot { it.key == detail })
            }
            MenuAction.DismissNotification -> {
                try { menuNotifications?.dismiss(detail) } catch (e: Exception) { /* already gone */ }
                _menu.value = open.copy(notifications = open.notifications.filterNot { it.key == detail })
            }
            MenuAction.ClearNotifications -> {
                try { menuPackage(open.appId)?.let { menuNotifications?.dismissAll(it) } } catch (e: Exception) { /* already gone */ }
                _menu.value = open.copy(notifications = emptyList())
            }
            MenuAction.Shortcut -> open.shortcuts.firstOrNull { it.id == detail }?.let(::launchShortcut)
            MenuAction.TogglePin -> togglePin()
            MenuAction.OpenMoveToFolder -> _menu.value = open.copy(
                page = MenuPage.MoveToFolder,
                // OPUS6 (audit F1): a folder locked right now is listed as "Locked" when the user
                // asked to hide its name, never by its real name.
                folders = layout.layout.value.folders.filterNot { it.id == open.inFolder }.map { folder ->
                    val gate = lockGate
                    if (gate == null) folder
                    else com.brightblu.parc.core.data.lock.LockedApps.folderForDisplay(folder,
                        gate.isFolderLockedNow(folder.id), gate.hideName())
                },
            )
            MenuAction.OpenNewFolder -> _menu.value = open.copy(
                page = MenuPage.NewFolder, draftName = "", nameIssue = "")
            MenuAction.SaveNewFolder -> {
                val issue = folderNameIssue(open.draftName, layout.layout.value.folders, FolderId(""))
                val plan = if (issue.isEmpty()) newFolderLayout(layout.layout.value, open.draftName, open.appId) else null
                when {
                    issue.isNotEmpty() -> _menu.value = open.copy(nameIssue = issue)
                    plan == null -> _menu.value = open.copy(nameIssue = ArcsFull)
                    else -> {
                        dismissMenu()
                        scope.launch { layout.importLayout(LayoutJson.encode(plan)) }
                    }
                }
            }
            MenuAction.CancelNewFolder -> _menu.value = open.copy(
                page = MenuPage.MoveToFolder, draftName = "", nameIssue = "")
            MenuAction.MoveToFolder -> {
                val target = FolderId(detail)
                dismissMenu()
                scope.launch {
                    layout.addToFolder(target, open.appId)
                    open.inFolder?.let { layout.removeFromFolder(it, open.appId) }
                }
            }
            MenuAction.RemoveFromFolder -> {
                val from = open.inFolder ?: return dismissMenu()
                dismissMenu()
                scope.launch { layout.removeFromFolder(from, open.appId) }
            }
            MenuAction.Hide -> {
                dismissMenu()
                scope.launch { layout.hide(open.appId) }
            }
            MenuAction.AppInfo -> {
                dismissMenu()
                appInfo(open.appId)
            }
            MenuAction.OpenUninstall -> _menu.value = open.copy(page = MenuPage.ConfirmUninstall)
            MenuAction.Uninstall -> {
                dismissMenu()
                uninstall(open.appId)
            }
            else -> Unit
        }
    }

    private fun openFolderMenu(id: FolderId, hit: SlotHit) {
        val snapshot = layout.layout.value
        val folder = snapshot.folders.firstOrNull { it.id == id } ?: return
        val slotArc = snapshot.arcs.firstOrNull { it.slots[hit.position.index] == Slot.Folder(id) }
        val (earlier, later) = folderNeighbours(snapshot, id)
        val lock = folderMenuLock(id)
        _folderMenu.value = FolderMenuModel(id,
            if (lock == FolderMenuLock.LockedNow && lockGate?.hideName() == true) LockedFolderText.HiddenName else folder.name,
            hit.position, hit.xDp, hit.yDp,
            candidates = emptyList(), canMoveEarlier = slotArc != null && earlier, canMoveLater = slotArc != null && later,
            notices = if (lock == FolderMenuLock.LockedNow) emptyList() else folderNoticesFor(snapshot, folder),
            lock = lock)
        onMenuOpened()
    }

    private fun folderMenuLock(id: FolderId): FolderMenuLock {
        val gate = lockGate ?: return FolderMenuLock.None
        return when {
            !gate.isFolderFlagged(id) -> FolderMenuLock.CanLock
            gate.isFolderLockedNow(id) -> FolderMenuLock.LockedNow
            else -> FolderMenuLock.Flagged
        }
    }

    private suspend fun setFolderFlag(id: FolderId, locked: Boolean) = layout.setFolderLockFlag(id, locked)

    /** T97: apps inside the folder that have notifications; empty without a source or access. */
    private fun folderNoticesFor(snapshot: ParcLayout, folder: Folder): List<FolderNotice> = try {
        val source = menuNotifications
        if (source == null) emptyList() else {
            val counts = source.counts()
            if (counts.isEmpty()) emptyList()
            else folderNotices(apps.apps.value.filter { it.id in folder.appIds && it.id !in snapshot.hidden }, counts)
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun dismissFolderMenu() {
        _folderMenu.value = null
    }

    fun folderNameChanged(text: String) {
        val open = _folderMenu.value ?: return
        _folderMenu.value = open.copy(draftName = text, nameIssue = "")
    }

    /** Dispatches one folder-menu row. */
    fun folderAction(action: MenuAction, detail: String) {
        val open = _folderMenu.value ?: return
        // T98: a folder that is hidden right now offers the unlock row and nothing else.
        if (open.lock == FolderMenuLock.LockedNow &&
            action != MenuAction.UnlockFolder && action != MenuAction.Dismiss) return
        when (action) {
            MenuAction.Dismiss -> dismissFolderMenu()
            MenuAction.LockFolder -> {
                dismissFolderMenu()
                // Locking needs authentication first; the window stays closed so the folder hides at once.
                lockGate?.authenticate(openWindow = false) {
                    scope.launch { setFolderFlag(open.folderId, true); lockGate.relock() }
                }
            }
            MenuAction.LockNow -> {
                dismissFolderMenu()
                lockGate?.relock()
            }
            MenuAction.UnlockFolder -> {
                dismissFolderMenu()
                val gate = lockGate
                if (gate != null) {
                    if (gate.isFolderLockedNow(open.folderId)) {
                        gate.authenticate(openWindow = false) { scope.launch { setFolderFlag(open.folderId, false) } }
                    } else scope.launch { setFolderFlag(open.folderId, false) }
                }
            }
            MenuAction.Back -> _folderMenu.value = open.copy(page = FolderPage.Root, nameIssue = "")
            MenuAction.OpenRename -> _folderMenu.value = open.copy(page = FolderPage.Rename,
                draftName = open.name, nameIssue = "")
            MenuAction.Save -> {
                val issue = folderNameIssue(open.draftName, layout.layout.value.folders, open.folderId)
                if (issue.isNotEmpty()) _folderMenu.value = open.copy(nameIssue = issue)
                else {
                    dismissFolderMenu()
                    scope.launch { layout.renameFolder(open.folderId, open.draftName.trim()) }
                }
            }
            MenuAction.AddApps -> {
                val folder = layout.layout.value.folders.firstOrNull { it.id == open.folderId }
                val hidden = layout.layout.value.hidden
                _folderMenu.value = open.copy(page = FolderPage.AddApps,
                    candidates = addAppCandidates(apps.apps.value, folder, hidden))
            }
            MenuAction.AddApp -> {
                val id = AppId(detail)
                _folderMenu.value = open.copy(candidates = open.candidates.filterNot { it.id == id })
                scope.launch { layout.addToFolder(open.folderId, id) }
            }
            MenuAction.MoveEarlier -> {
                dismissFolderMenu()
                scope.launch { moveFolder(open.folderId, earlier = true) }
            }
            MenuAction.MoveLater -> {
                dismissFolderMenu()
                scope.launch { moveFolder(open.folderId, earlier = false) }
            }
            MenuAction.SortAZ -> {
                dismissFolderMenu()
                scope.launch { sortFolder(open.folderId) }
            }
            MenuAction.LaunchApp -> {
                dismissFolderMenu()
                launch(AppId(detail))
            }
            MenuAction.OpenDelete -> _folderMenu.value = open.copy(page = FolderPage.ConfirmDelete)
            MenuAction.Delete -> {
                dismissFolderMenu()
                scope.launch { deleteFolder(open.folderId) }
            }
            else -> Unit
        }
    }

    /** Whether a folder slot has a neighbouring folder slot earlier or later on the same arc. */
    private fun folderNeighbours(snapshot: ParcLayout, id: FolderId): Pair<Boolean, Boolean> {
        val assignment = snapshot.arcs.firstOrNull { it.slots.values.contains(Slot.Folder(id)) } ?: return false to false
        val index = assignment.slots.entries.firstOrNull { it.value == Slot.Folder(id) }?.key ?: return false to false
        val earlier = assignment.slots[index - 1] is Slot.Folder
        val later = assignment.slots[index + 1] is Slot.Folder
        return earlier to later
    }

    /** Swaps a folder slot with the neighbouring folder slot on its arc. Persisted slot order wins. */
    private suspend fun moveFolder(id: FolderId, earlier: Boolean) {
        val snapshot = layout.layout.value
        val assignment = snapshot.arcs.firstOrNull { it.slots.values.contains(Slot.Folder(id)) } ?: return
        val index = assignment.slots.entries.firstOrNull { it.value == Slot.Folder(id) }?.key ?: return
        val target = if (earlier) index - 1 else index + 1
        if (assignment.slots[target] !is Slot.Folder) return
        try {
            layout.move(SlotPosition(assignment.arc.id, index), SlotPosition(assignment.arc.id, target))
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    /**
     * Sorts a folder's contents A to Z (T68). The frozen repository has no sort mutation, so the
     * document is replaced through importLayout, which validates it before writing.
     */
    private suspend fun sortFolder(id: FolderId) {
        val snapshot = layout.layout.value
        val folder = snapshot.folders.firstOrNull { it.id == id } ?: return
        val labels = apps.apps.value.associate { it.id to it.label }
        val sorted = folder.copy(appIds = sortFolderApps(folder.appIds) { labels[it].orEmpty() })
        if (sorted == folder) return
        val updated = snapshot.copy(folders = snapshot.folders.map { if (it.id == id) sorted else it })
        runCatching { layout.importLayout(LayoutJson.encode(updated)) }
    }

    /**
     * Deletes a folder (T30): its slot leaves the ring and its apps join the New folder. The frozen
     * repository has no deleteFolder, so the whole document is replaced through importLayout, which
     * validates it before writing.
     */
    private suspend fun deleteFolder(id: FolderId) {
        val snapshot = layout.layout.value
        val folder = snapshot.folders.firstOrNull { it.id == id } ?: return
        val others = snapshot.folders.filterNot { it.id == id }
        val withNew = if (others.any { it.id == FolderId(NewFolderKey) }) others
        else others + Folder(FolderId(NewFolderKey), NewFolderName)
        val folders = withNew.map {
            if (it.id == FolderId(NewFolderKey)) it.copy(appIds = (it.appIds + folder.appIds).distinct()) else it
        }
        val arcs = snapshot.arcs.map { a -> a.copy(slots = a.slots.filterValues { it != Slot.Folder(id) }) }
        layout.importLayout(LayoutJson.encode(snapshot.copy(arcs = arcs, folders = folders)))
    }

    override fun onSettled(state: RingState) {
        // Navigation already owns the requested target. Late completion cannot undo HOME/Back.
    }

    override fun onInterrupted(state: RingState) {
        val current = navigation.state.value
        navigation.show(current.copy(layer = state.layer,
            folderId = state.arcs.firstOrNull()?.folderId))
    }
    override fun onDrag(hit: SlotHit, phase: DragPhase) = Unit
    override fun onRotate(arcId: String, offset: Float) = onRotation(arcId, offset)
}

/** The unplaced inbox folder (T05): deleted folders return their apps here. */
internal const val NewFolderKey = "new"
internal const val NewFolderName = "New"
