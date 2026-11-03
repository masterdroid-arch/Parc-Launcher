package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.BackupValueType
import com.brightblu.parc.core.data.focus.FocusState
import com.brightblu.parc.core.data.lock.AuthAvailability
import com.brightblu.parc.core.data.lock.AuthFailure
import com.brightblu.parc.core.data.lock.AuthOutcome
import com.brightblu.parc.core.data.lock.LockedApps
import com.brightblu.parc.core.data.lock.UnlockTimeout
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** T98: locked folders across every surface, the session flows and the unlock window. */
@OptIn(ExperimentalCoroutinesApi::class)
class T98Test {
    private val geometry = RingLayout(384f, 853f)
    private val bank = AppEntry(AppId("test/Bank@0"), "Bank", "test.bank", false, 0)
    private val mail = AppEntry(AppId("test/Mail@0"), "Mail", "test.mail", false, 0)
    private val notes = AppEntry(AppId("test/Notes@0"), "Notes", "test.notes", false, 0)
    private val apps = listOf(bank, mail, notes)
    private val vault = FolderId("vault")
    private val daily = FolderId("daily")

    private fun layout(locked: Boolean = true) = ParcLayout(
        arcs = geometry.arcs.map {
            ArcAssignment(it, if (it.id == "A1") mapOf(0 to Slot.App(mail.id), 1 to Slot.Folder(vault),
                2 to Slot.Folder(daily), 3 to Slot.App(bank.id)) else emptyMap())
        },
        folders = listOf(
            Folder(vault, "Vault", appIds = listOf(bank.id)).withLocked(locked),
            Folder(daily, "Daily", appIds = listOf(mail.id, notes.id)),
        ),
        pins = setOf(bank.id),
    )

    // ---- the scene: arcs, folder, All apps, badges ----------------------------------------

    private fun scene(unlocked: Boolean, shell: ShellState = ShellState(), hideName: Boolean = false): RingState {
        val l = layout()
        val hidden = LockedApps.lockedPackages(apps, l.folders, unlocked)
        return LauncherScenes.build(geometry, l, LockedApps.visibleApps(apps, hidden), shell, "",
            lockedFolders = LockedApps.lockedFoldersNow(l.folders, unlocked), hideLockedName = hideName) { null }
    }

    private fun appIds(state: RingState): List<AppId> = state.slots.map { it.slot }.filterIsInstance<Slot.App>().map { it.id }

    @Test fun lockedAppsLeaveTheArcsEvenWhenPinnedThereAndReturnWhenUnlocked() {
        assertEquals(listOf(mail.id), appIds(scene(unlocked = false)))
        assertEquals(setOf(mail.id, bank.id), appIds(scene(unlocked = true)).toSet())
    }

    @Test fun theLockedFolderStaysOnTheArcAndItsNameCanBeHidden() {
        val shown = scene(false).slots.filter { it.slot is Slot.Folder }.map { it.label }
        assertEquals(listOf("Vault", "Daily"), shown)
        val hiddenName = scene(false, hideName = true).slots.filter { it.slot is Slot.Folder }.map { it.label }
        assertEquals(listOf("Locked", "Daily"), hiddenName)
        // Once unlocked the real name returns even with the switch on.
        val open = scene(true, hideName = true).slots.filter { it.slot is Slot.Folder }.map { it.label }
        assertEquals(listOf("Vault", "Daily"), open)
    }

    @Test fun allAppsListsOnlyUnlockedApps() {
        val locked = scene(false, ShellState(layer = Layer.AllApps))
        assertEquals(listOf(mail.id, notes.id), appIds(locked))
        val open = scene(true, ShellState(layer = Layer.AllApps))
        assertEquals(listOf(bank.id, mail.id, notes.id), appIds(open))
    }

    @Test fun aLockedFolderViewShowsNothingButItsApps() {
        val shell = ShellState(layer = Layer.Folder, folderId = vault)
        assertTrue(appIds(scene(false, shell)).isEmpty())
        assertEquals(listOf(bank.id), appIds(scene(true, shell)))
    }

    @Test fun badgesOfLockedAppsAndTheLockedFolderDiscAreZeroWhileLocked() {
        val counts = mapOf("test.bank" to 5, "test.mail" to 2)
        fun unread(unlocked: Boolean): Map<Slot, Int> {
            val l = layout()
            val hidden = LockedApps.lockedPackages(apps, l.folders, unlocked)
            val visible = LockedApps.visibleApps(apps, hidden)
            val base = scene(unlocked)
            return LauncherScenes.withUnread(base, l, visible, counts,
                lockedFolders = LockedApps.lockedFoldersNow(l.folders, unlocked)).slots.associate { it.slot to it.unreadCount }
        }
        val locked = unread(false)
        assertEquals(0, locked[Slot.Folder(vault)])
        assertNull(locked[Slot.App(bank.id)])
        assertEquals(2, locked[Slot.App(mail.id)])
        val open = unread(true)
        assertEquals(5, open[Slot.Folder(vault)])
        assertEquals(5, open[Slot.App(bank.id)])
    }

    // ---- search, shortcuts and recents ----------------------------------------------------

    private class Index(entries: List<AppEntry>) : AppIndex {
        override val apps = MutableStateFlow(entries)
        override fun icon(id: AppId, sizePx: Int): android.graphics.Bitmap? = null
        override fun launch(id: AppId) { launched += id }
        override fun shortcuts(id: AppId) = listOf(AppShortcut("s", "Shortcut", id))
        val launched = mutableListOf<AppId>()
    }

    private class Usage(private val recents: List<AppId> = emptyList()) : UsageRepository {
        override fun recordLaunch(id: AppId) = Unit
        override fun recents(limit: Int): Flow<List<AppId>> = flowOf(recents)
        override fun rank(folderId: FolderId): List<AppId> = emptyList()
    }

    private class Shortcuts : ShortcutHost {
        val launched = mutableListOf<Pair<AppId, String>>()
        override fun isDefaultHome(): Boolean = true
        override fun launchShortcut(appId: AppId, shortcutId: String) { launched += appId to shortcutId }
    }

    @Test fun theSearchViewDropsLockedAppsAndTheirShortcutsAndFollowsTheLockState() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val index = Index(apps)
        val hidden = MutableStateFlow(setOf("test.bank"))
        val view = FocusAppIndex(index, MutableStateFlow(FocusState()), scope, locked = hidden)
        assertEquals(listOf(mail, notes), view.apps.value)
        assertTrue(view.shortcuts(bank.id).isEmpty())
        assertEquals(1, view.shortcuts(mail.id).size)
        hidden.value = emptySet()
        assertEquals(apps, view.apps.value)
        assertEquals(1, view.shortcuts(bank.id).size)
    }

    @Test fun recentsAndLabelsInTheSearchSheetNeverNameALockedApp() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val hidden = MutableStateFlow(setOf("test.bank"))
        val view = FocusAppIndex(Index(apps), MutableStateFlow(FocusState()), scope, locked = hidden)
        val search = SearchSheetState(LabelSearchEngine(), view, Usage(listOf(bank.id, mail.id)), scope)
        search.open()
        assertEquals(listOf("Mail"), search.state.value.rows.map { it.label })
        hidden.value = emptySet()
        assertEquals(listOf("Bank", "Mail"), search.state.value.rows.map { it.label })
        hidden.value = setOf("test.bank")
        assertEquals(listOf("Mail"), search.state.value.rows.map { it.label })
        search.cancel()
    }

    // ---- the unlock window controller -----------------------------------------------------

    @Test fun theWindowOpensAndClosesAfterTheTimeoutWithAFakeClock() = runTest {
        var timeout = UnlockTimeout.Minute1
        val lock = FolderLock(backgroundScope, { testScheduler.currentTime }, { timeout })
        assertFalse(lock.unlocked.value)
        lock.unlock()
        assertTrue(lock.unlocked.value)
        advanceTimeBy(59_999); runCurrent()
        assertTrue(lock.unlocked.value)
        advanceTimeBy(1); runCurrent()
        assertFalse(lock.unlocked.value)
        timeout = UnlockTimeout.Seconds30
        lock.unlock()
        advanceTimeBy(30_000); runCurrent()
        assertFalse(lock.unlocked.value)
    }

    @Test fun leavingParcOrTurningTheScreenOffRelocksAtOnceForEveryTimeout() = runTest {
        for (timeout in UnlockTimeout.entries) {
            val lock = FolderLock(backgroundScope, { testScheduler.currentTime }, { timeout })
            lock.unlock()
            assertTrue(lock.unlocked.value)
            lock.onHostLeft()
            assertFalse(lock.unlocked.value)
            lock.unlock()
            lock.onScreenOff()
            assertFalse(lock.unlocked.value)
        }
    }

    @Test fun untilScreenOffHasNoTimerButExpireIfDueNeverClosesIt() = runTest {
        val lock = FolderLock(backgroundScope, { testScheduler.currentTime }, { UnlockTimeout.UntilScreenOff })
        lock.unlock()
        advanceTimeBy(3_600_000); runCurrent()
        lock.expireIfDue()
        assertTrue(lock.unlocked.value)
    }

    @Test fun anExpiredWindowIsClosedByTheSafetyCheckEvenIfTheTimerDidNotFire() = runTest {
        var now = 0L
        val lock = FolderLock(backgroundScope, { now }, { UnlockTimeout.Minute1 })
        lock.unlock()
        now = 61_000
        lock.expireIfDue()
        assertFalse(lock.unlocked.value)
    }

    // ---- the gate: authentication rules ---------------------------------------------------

    private class FakeAuthenticator(var availability: AuthAvailability = AuthAvailability.Available,
        var outcome: AuthOutcome = AuthOutcome.Success) : FolderAuthenticator {
        var prompts = 0
        override fun availability() = availability
        override fun authenticate(title: String, subtitle: String, onResult: (AuthOutcome) -> Unit) { prompts++; onResult(outcome) }
        override fun cancel() = Unit
    }

    private fun gate(auth: FakeAuthenticator, notices: MutableList<String>, scope: CoroutineScope,
        locked: Boolean = true, hideName: Boolean = false): Pair<ServicesFolderLockGate, FolderLock> {
        val lock = FolderLock(scope, { 0L }, { UnlockTimeout.UntilScreenOff })
        val l = layout(locked)
        return ServicesFolderLockGate(lock, { l.folders }, { apps }, { hideName }, auth, { notices += it }) to lock
    }

    @Test fun noSecureLockMeansNoPromptAndAnExplanation() = runTest {
        val auth = FakeAuthenticator(AuthAvailability.NoSecureLock)
        val notices = mutableListOf<String>()
        val (gate, lock) = gate(auth, notices, backgroundScope)
        var ran = false
        gate.authenticate(openWindow = true) { ran = true }
        assertFalse(ran)
        assertEquals(0, auth.prompts)
        assertFalse(lock.unlocked.value)
        assertEquals(listOf(AuthAvailability.NoSecureLock.message), notices)
    }

    @Test fun successOpensTheWindowOnlyWhenAskedAndRunsTheAction() = runTest {
        val auth = FakeAuthenticator()
        val (gate, lock) = gate(auth, mutableListOf(), backgroundScope)
        var ran = 0
        gate.authenticate(openWindow = false) { ran++ }
        assertEquals(1, ran); assertFalse(lock.unlocked.value)
        gate.authenticate(openWindow = true) { ran++ }
        assertEquals(2, ran); assertTrue(lock.unlocked.value)
    }

    @Test fun cancelledIsSilentFailedSaysSoAndNeitherRunsTheAction() = runTest {
        val auth = FakeAuthenticator(outcome = AuthOutcome.Refused(AuthFailure.Cancelled))
        val notices = mutableListOf<String>()
        val (gate, lock) = gate(auth, notices, backgroundScope)
        var ran = false
        gate.authenticate(true) { ran = true }
        assertFalse(ran); assertTrue(notices.isEmpty()); assertFalse(lock.unlocked.value)
        auth.outcome = AuthOutcome.Refused(AuthFailure.Failed)
        gate.authenticate(true) { ran = true }
        assertFalse(ran); assertEquals(1, notices.size); assertFalse(lock.unlocked.value)
    }

    @Test fun theGateAnswersByFolderAndByAppAndFollowsTheWindow() = runTest {
        val (gate, lock) = gate(FakeAuthenticator(), mutableListOf(), backgroundScope)
        assertTrue(gate.isFolderFlagged(vault)); assertFalse(gate.isFolderFlagged(daily))
        assertTrue(gate.isFolderLockedNow(vault)); assertFalse(gate.isFolderLockedNow(daily))
        assertTrue(gate.isAppLocked(bank.id)); assertFalse(gate.isAppLocked(mail.id))
        lock.unlock()
        assertFalse(gate.isFolderLockedNow(vault)); assertFalse(gate.isAppLocked(bank.id))
        assertTrue(gate.isFolderFlagged(vault))
    }

    // ---- the session: taps, launches, menus -----------------------------------------------

    private class FakeGate(var flagged: Set<FolderId> = setOf(FolderId("vault")),
        var lockedApps: Set<AppId> = setOf(AppId("test/Bank@0")), var hide: Boolean = false) : FolderLockGate {
        var unlocked = false
        var allow = true
        var prompts = 0
        var relocks = 0
        override fun isFolderFlagged(id: FolderId) = id in flagged
        override fun isFolderLockedNow(id: FolderId) = id in flagged && !unlocked
        override fun isAppLocked(id: AppId) = !unlocked && id in lockedApps
        override fun hideName() = hide
        override fun authenticate(openWindow: Boolean, onSuccess: () -> Unit) {
            prompts++
            if (!allow) return
            if (openWindow) unlocked = true
            onSuccess()
        }
        override fun relock() { unlocked = false; relocks++ }
    }

    private class Rig(val session: LauncherSession, val navigation: ShellNavigation, val index: Index,
        val layout: FakeLayoutRepository, val shortcuts: Shortcuts)

    private fun rig(gate: FolderLockGate?, startLocked: Boolean = true): Rig {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val index = Index(apps)
        val repository = FakeLayoutRepository(layout(startLocked))
        val navigation = ShellNavigation()
        val shortcuts = Shortcuts()
        val search = SearchSheetState(LabelSearchEngine(), index, Usage(), scope)
        val session = LauncherSession(navigation, index, Usage(), repository, search, {}, {}, shortcuts, scope,
            UnconfinedTestDispatcher(), lockGate = gate)
        return Rig(session, navigation, index, repository, shortcuts)
    }

    private fun tapFolder(rig: Rig, id: FolderId) =
        rig.session.onTap(SlotHit(SlotPosition("A1", 1), Slot.Folder(id), 0f, 0f))

    private fun holdFolder(rig: Rig, id: FolderId) =
        rig.session.onLongPress(SlotHit(SlotPosition("A1", 1), Slot.Folder(id), 0f, 0f))

    private fun labels(items: List<MenuItem>) = items.filterIsInstance<MenuItem.Row>().map { it.row.label }

    @Test fun tappingALockedFolderAsksFirstAndOpensOnlyAfterSuccess() {
        val gate = FakeGate().apply { allow = false }
        val rig = rig(gate)
        tapFolder(rig, vault)
        assertEquals(1, gate.prompts)
        assertEquals(Layer.Home, rig.navigation.state.value.layer)
        gate.allow = true
        tapFolder(rig, vault)
        assertEquals(Layer.Folder, rig.navigation.state.value.layer)
        assertEquals(vault, rig.navigation.state.value.folderId)
        assertTrue(gate.unlocked)
    }

    @Test fun anOpenWindowOrAPlainFolderNeverAsks() {
        val gate = FakeGate().apply { unlocked = true }
        val rig = rig(gate)
        tapFolder(rig, vault)
        assertEquals(0, gate.prompts)
        assertEquals(Layer.Folder, rig.navigation.state.value.layer)
        val plain = rig(FakeGate())
        tapFolder(plain, daily)
        assertEquals(daily, plain.navigation.state.value.folderId)
    }

    @Test fun aLockedAppCannotBeLaunchedFromAnySource() {
        val gate = FakeGate()
        val rig = rig(gate)
        rig.session.launch(bank.id)
        rig.session.onTap(SlotHit(SlotPosition("A1", 3), Slot.App(bank.id), 0f, 0f))
        rig.session.launchShortcut(AppShortcut("s", "Shortcut", bank.id))
        assertTrue(rig.index.launched.isEmpty())
        assertTrue(rig.shortcuts.launched.isEmpty())
        rig.session.launch(mail.id)
        assertEquals(listOf(mail.id), rig.index.launched)
        gate.unlocked = true
        rig.session.launch(bank.id)
        assertEquals(listOf(mail.id, bank.id), rig.index.launched)
    }

    @Test fun holdingALockedAppOpensNoMenu() {
        val rig = rig(FakeGate())
        rig.session.onLongPress(SlotHit(SlotPosition("A1", 3), Slot.App(bank.id), 0f, 0f))
        assertNull(rig.session.menu.value)
        rig.session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(mail.id), 0f, 0f))
        assertNotNull(rig.session.menu.value)
    }

    @Test fun theFolderMenuOffersTheRightLockRowsForEachState() {
        val rig = rig(FakeGate(flagged = setOf(vault)))
        holdFolder(rig, vault)
        assertEquals(FolderMenuLock.LockedNow, rig.session.folderMenu.value!!.lock)
        assertEquals(listOf("Unlock folder"), labels(folderMenuRootItems(rig.session.folderMenu.value!!)))
        rig.session.dismissFolderMenu()
        holdFolder(rig, daily)
        val plain = labels(folderMenuRootItems(rig.session.folderMenu.value!!))
        assertEquals(FolderMenuLock.CanLock, rig.session.folderMenu.value!!.lock)
        assertTrue("Lock folder" in plain)
        assertEquals("Delete", plain.last())
        val open = rig(FakeGate(flagged = setOf(vault)).apply { unlocked = true })
        holdFolder(open, vault)
        val rows = labels(folderMenuRootItems(open.session.folderMenu.value!!))
        assertTrue("Lock now" in rows && "Unlock folder" in rows && "Rename" in rows)
        // No gate, no lock rows: the menu is exactly what it was before T98.
        val none = rig(null)
        holdFolder(none, daily)
        assertEquals(listOf("Rename", "Add apps", "Sort A to Z", "Move earlier", "Move later", "Delete"),
            labels(folderMenuRootItems(none.session.folderMenu.value!!)))
    }

    @Test fun aHiddenFolderMenuCanHideTheNameAndOffersNothingElse() {
        val rig = rig(FakeGate(hide = true))
        holdFolder(rig, vault)
        assertEquals("Locked", rig.session.folderMenu.value!!.name)
        rig.session.folderAction(MenuAction.OpenDelete, "")
        rig.session.folderAction(MenuAction.OpenRename, "")
        rig.session.folderAction(MenuAction.AddApps, "")
        assertEquals(FolderPage.Root, rig.session.folderMenu.value!!.page)
        assertTrue(rig.layout.layout.value.folders.any { it.id == vault })
    }

    @Test fun lockFolderNeedsAuthenticationThenFlagsTheFolderAndHidesItAtOnce() {
        val gate = FakeGate(flagged = emptySet(), lockedApps = emptySet()).apply { allow = false }
        val rig = rig(gate, startLocked = false)
        holdFolder(rig, daily)
        rig.session.folderAction(MenuAction.LockFolder, "")
        assertEquals(1, gate.prompts)
        assertFalse(rig.layout.layout.value.folders.first { it.id == daily }.locked)
        gate.allow = true
        holdFolder(rig, daily)
        rig.session.folderAction(MenuAction.LockFolder, "")
        assertTrue(rig.layout.layout.value.folders.first { it.id == daily }.locked)
        assertEquals(1, gate.relocks)
        assertNull(rig.session.folderMenu.value)
    }

    @Test fun unlockFolderOnAHiddenFolderNeedsAuthenticationThenClearsTheFlag() {
        val gate = FakeGate().apply { allow = false }
        val rig = rig(gate)
        holdFolder(rig, vault)
        rig.session.folderAction(MenuAction.UnlockFolder, "")
        assertTrue(rig.layout.layout.value.folders.first { it.id == vault }.locked)
        gate.allow = true
        holdFolder(rig, vault)
        rig.session.folderAction(MenuAction.UnlockFolder, "")
        assertFalse(rig.layout.layout.value.folders.first { it.id == vault }.locked)
        assertFalse(gate.unlocked)
    }

    @Test fun lockNowRelocksTheWindow() {
        val gate = FakeGate().apply { unlocked = true }
        val rig = rig(gate)
        holdFolder(rig, vault)
        rig.session.folderAction(MenuAction.LockNow, "")
        assertEquals(1, gate.relocks)
        assertFalse(gate.unlocked)
        assertTrue(rig.layout.layout.value.folders.first { it.id == vault }.locked)
    }

    // ---- settings, text and backup --------------------------------------------------------


    @Test fun theTwoSettingsTravelInTheBackupAndKeepTheirTypes() {
        assertEquals(BackupValueType.Text, BackupSpecs.uiPrefs.key("lock_timeout")?.type)
        assertEquals(BackupValueType.Bool, BackupSpecs.uiPrefs.key("lock_hide_name")?.type)
    }

    @Test fun backupRoundTripKeepsTheTimeoutAndRejectsAWrongType() = runTest {
        val source = FakeSharedPreferences()
        source.data["lock_timeout"] = "5m"
        source.data["lock_hide_name"] = true
        val read = SharedPreferencesStoreAccess(source).read(BackupSpecs.uiPrefs)
        assertEquals("5m", read["lock_timeout"])
        assertEquals(true, read["lock_hide_name"])
        val target = FakeSharedPreferences()
        SharedPreferencesStoreAccess(target).write(BackupSpecs.uiPrefs, mapOf("lock_timeout" to true, "lock_hide_name" to "yes"))
        assertFalse(target.data.containsKey("lock_timeout"))
        assertFalse(target.data.containsKey("lock_hide_name"))
        SharedPreferencesStoreAccess(target).write(BackupSpecs.uiPrefs, read)
        assertEquals(UnlockTimeout.Minutes5, UnlockTimeout.fromId(target.data["lock_timeout"] as String))
    }

    @Test fun theLockFlagItselfTravelsInTheLayoutDocumentOfTheBackup() {
        val json = LayoutJson.encode(layout())
        assertTrue(LayoutJson.decode(json).folders.first { it.id == vault }.locked)
    }

    // ---- OPUS6 (audit F1): the long-press lists never show what a locked folder hides ----------

    @Test fun addAppsInAnotherFolderNeverListsALockedApp() {
        val gate = FakeGate()
        val rig = rig(gate)
        holdFolder(rig, daily)
        rig.session.folderAction(MenuAction.AddApps, "")
        val offered = rig.session.folderMenu.value!!.candidates.map { it.id }
        assertFalse("locked Bank must not be offered", bank.id in offered)
        // With the folder unlocked the app may be added again.
        gate.unlocked = true
        rig.session.folderAction(MenuAction.AddApps, "")
        assertTrue(bank.id in rig.session.folderMenu.value!!.candidates.map { it.id })
    }

    @Test fun moveToFolderShowsLockedInsteadOfAHiddenName() {
        val gate = FakeGate(hide = true)
        val rig = rig(gate)
        rig.session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(mail.id), 0f, 0f))
        rig.session.menuAction(MenuAction.OpenMoveToFolder, "")
        val names = rig.session.menu.value!!.folders.map { it.name }
        assertFalse("the real name of a hidden locked folder leaked", "Vault" in names)
        assertTrue(com.brightblu.parc.core.data.lock.LockedFolderText.HiddenName in names)
        // Without "hide name" the user sees the name they chose to show.
        val shown = rig(FakeGate(hide = false))
        shown.session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.App(mail.id), 0f, 0f))
        shown.session.menuAction(MenuAction.OpenMoveToFolder, "")
        assertTrue("Vault" in shown.session.menu.value!!.folders.map { it.name })
    }
}
