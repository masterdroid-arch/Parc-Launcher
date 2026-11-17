package com.brightblu.parc

import com.brightblu.parc.core.data.focus.FocusMode
import com.brightblu.parc.feature.search.LabelSearchEngine
import com.brightblu.parc.feature.search.SearchSheetState
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.AppIndex
import com.brightblu.parc.model.AppShortcut
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.LayoutJson
import com.brightblu.parc.model.LockedFolderTag
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotHit
import com.brightblu.parc.model.SlotPosition
import com.brightblu.parc.model.UsageRepository
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import com.brightblu.parc.model.hidden
import com.brightblu.parc.model.locked
import com.brightblu.parc.model.withFolderHidden
import com.brightblu.parc.model.withHidden
import com.brightblu.parc.model.withLocked
import com.brightblu.parc.ring.RingLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T218: the hide tag, the folder slot rule, the preview/visibility result and the menu action. */
@OptIn(ExperimentalCoroutinesApi::class)
class FolderVisibilityTest {
    private val geometry = RingLayout(384f, 853f)
    private val alpha = AppEntry(AppId("test/Alpha@0"), "Alpha", "test.alpha", false, 0)
    private val beta = AppEntry(AppId("test/Beta@0"), "Beta", "test.beta", false, 0)
    private val apps = listOf(alpha, beta)
    private val socialId = FolderId("social")
    private val social = Folder(socialId, "Social", appIds = listOf(alpha.id, beta.id))

    private fun sceneLayout(folder: Folder) = ParcLayout(
        arcs = listOf(ArcAssignment(ArcSpec("A1", 0, 120f, 180f, 270f, 4, Layer.Home),
            mapOf(0 to Slot.Folder(folder.id)))),
        folders = listOf(folder),
    )

    private fun scene(layout: ParcLayout, folderIds: Set<FolderId> = FolderVisibility.hiddenFolderIds(
        layout, apps, Visibility.of(apps, layout, null, emptySet()))) =
        LauncherScenes.build(geometry, layout, apps, ShellState(), "", hiddenFolderIds = folderIds) { null }

    @Test fun hiddenTagRoundTripsAndAnOldLayoutDecodesVisible() {
        val tagged = social.withLocked(true).withHidden(true)
        assertTrue(tagged.hidden)
        assertTrue(tagged.locked)
        assertEquals(setOf("parc:hidden", LockedFolderTag), tagged.tags)

        val layout = sceneLayout(tagged)
        val back = LayoutJson.decode(LayoutJson.encode(layout))
        assertTrue(back.folders.single().hidden)
        assertTrue(back.folders.single().locked)
        assertEquals(layout.arcs, back.arcs)
        assertEquals(layout.pins, back.pins)

        val old = """{"version":1,"arcs":[],"folders":[{"id":"social","name":"Social","tags":["work"],"appIds":["test/Alpha@0"]}],"pins":[],"hidden":[]}"""
        val legacy = LayoutJson.decode(old)
        assertFalse(legacy.folders.single().hidden)
        assertEquals(setOf("work"), legacy.folders.single().tags)
    }

    @Test fun hideAndUnhideTouchNoAppSlotPinOrFolderMember() {
        val original = sceneLayout(social.withLocked(true))
        val hidden = original.withFolderHidden(socialId, true)
        assertTrue(hidden.folders.single().hidden)
        assertTrue(hidden.folders.single().locked)
        assertEquals(original.arcs, hidden.arcs)
        assertEquals(original.pins, hidden.pins)
        assertEquals(original.folders.single().appIds, hidden.folders.single().appIds)
        assertEquals(original, hidden.withFolderHidden(socialId, false))
        assertEquals(original, original.withFolderHidden(FolderId("missing"), true))
    }

    @Test fun aHiddenFolderLeavesHomeAndUnhidingRestoresTheSameSlot() {
        val hiddenLayout = sceneLayout(social.withHidden(true))
        val visibility = Visibility.of(apps, hiddenLayout, null, emptySet())
        assertEquals(setOf(alpha.packageName, beta.packageName), visibility.hiddenFolderApps)
        val hiddenIds = FolderVisibility.hiddenFolderIds(hiddenLayout, apps, visibility)
        assertTrue(socialId in hiddenIds)
        assertTrue(scene(hiddenLayout, hiddenIds).slots.none { it.slot is Slot.Folder })

        val shownLayout = hiddenLayout.withFolderHidden(socialId, false)
        val shown = scene(shownLayout)
        val folderSlot = shown.slots.single { it.slot is Slot.Folder }
        assertEquals(SlotPosition("A1", 0), folderSlot.position)
        assertEquals("Social", folderSlot.label)
        assertEquals(hiddenLayout.arcs, shownLayout.arcs)
        assertEquals(listOf(alpha.id, beta.id), shownLayout.folders.single().appIds)
    }

    @Test fun aFolderLeavesWhileEveryMemberIsHiddenAndReturnsWhenOneIsVisible() {
        val layout = sceneLayout(social)
        val permanent = Visibility.of(apps, layout.copy(hidden = setOf(alpha.id, beta.id)), null, emptySet())
        assertTrue(socialId in FolderVisibility.hiddenFolderIds(layout, apps, permanent))

        val focusBoth = Visibility.of(apps, layout,
            FocusMode("Work", hiddenPackages = setOf(alpha.packageName, beta.packageName)), emptySet())
        assertTrue(socialId in FolderVisibility.hiddenFolderIds(layout, apps, focusBoth))

        val focusOne = Visibility.of(apps, layout,
            FocusMode("Work", hiddenPackages = setOf(alpha.packageName)), emptySet())
        assertFalse(socialId in FolderVisibility.hiddenFolderIds(layout, apps, focusOne))
        assertTrue(scene(layout, FolderVisibility.hiddenFolderIds(layout, apps, focusOne))
            .slots.any { it.slot is Slot.Folder })
    }

    @Test fun lockKeepsItsPadlockedSlotAndHideStillWins() {
        val lockedLayout = sceneLayout(social.withLocked(true))
        val locked = Visibility.of(apps, lockedLayout, null, setOf(alpha.packageName, beta.packageName))
        assertFalse("Lock must keep the folder slot for unlocking", socialId in
            FolderVisibility.hiddenFolderIds(lockedLayout, apps, locked))
        assertTrue(LauncherScenes.build(geometry, lockedLayout, apps, ShellState(), "",
            lockedFolders = setOf(socialId), hiddenFolderIds = emptySet()) { null }
            .slots.any { it.slot is Slot.Folder })

        val both = lockedLayout.withFolderHidden(socialId, true)
        val hiddenIds = FolderVisibility.hiddenFolderIds(both, apps,
            Visibility.of(apps, both, null, setOf(alpha.packageName, beta.packageName)))
        assertTrue(socialId in hiddenIds)
        assertTrue(both.folders.single().locked)
        assertFalse(scene(both, hiddenIds).slots.any { it.slot is Slot.Folder })
    }

    @Test fun anEmptyFolderStillDrawsAsAnEmptyFolder() {
        val empty = sceneLayout(social.copy(appIds = emptyList()))
        val visibility = Visibility.of(apps, empty, null, emptySet())
        assertFalse(socialId in FolderVisibility.hiddenFolderIds(empty, apps, visibility))
        assertTrue(scene(empty).slots.any { it.slot is Slot.Folder && it.label == "Social" })
    }

    @Test fun theMenuOffersHideAndUnhideSeparatelyFromLock() {
        val plain = FolderMenuModel(socialId, "Social", SlotPosition("A1", 0), 0f, 0f,
            lock = FolderMenuLock.CanLock)
        val rows = folderMenuRootItems(plain)
        assertEquals(MenuAction.HideFolder, rows.filterIsInstance<MenuItem.Row>()
            .first { it.row.label == "Hide folder" }.row.action)
        val lockIndex = rows.indexOfFirst { it is MenuItem.Row && it.row.action == MenuAction.LockFolder }
        val hideIndex = rows.indexOfFirst { it is MenuItem.Row && it.row.action == MenuAction.HideFolder }
        assertTrue(lockIndex >= 0 && hideIndex > lockIndex)
        assertTrue("Lock and Hide need their own divider", rows.subList(lockIndex + 1, hideIndex)
            .any { it is MenuItem.Divider })

        val hidden = folderMenuRootItems(plain.copy(hidden = true))
        assertEquals(MenuAction.UnhideFolder, hidden.filterIsInstance<MenuItem.Row>()
            .first { it.row.label == "Unhide folder" }.row.action)
        assertTrue(hidden.filterIsInstance<MenuItem.Row>().none { it.row.label == "Hide folder" })
    }

    @Test fun theFolderMenuWritesAndClearsTheTagWithoutLosingTheLockOrMembers() = runTest {
        val repository = FakeLayoutRepository(sceneLayout(social.withLocked(true)))
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val index = Index(apps)
            val search = SearchSheetState(LabelSearchEngine(), index, Usage(), scope)
            val session = LauncherSession(ShellNavigation(), index, Usage(), repository, search, {}, {},
                Shortcuts(), scope, UnconfinedTestDispatcher())
            session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.Folder(socialId), 0f, 0f))
            session.folderAction(MenuAction.HideFolder, "")
            runCurrent()
            val hidden = repository.layout.value.folders.single()
            assertTrue(hidden.hidden)
            assertTrue(hidden.locked)
            assertEquals(listOf(alpha.id, beta.id), hidden.appIds)
            assertEquals(repository.layout.value.arcs.first().slots[0], Slot.Folder(socialId))

            session.onLongPress(SlotHit(SlotPosition("A1", 0), Slot.Folder(socialId), 0f, 0f))
            session.folderAction(MenuAction.UnhideFolder, "")
            runCurrent()
            val shown = repository.layout.value.folders.single()
            assertFalse(shown.hidden)
            assertTrue(shown.locked)
            assertEquals(listOf(alpha.id, beta.id), shown.appIds)
        } finally {
            scope.cancel()
        }
    }

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

    private class Shortcuts : com.brightblu.parc.ShortcutHost {
        override fun isDefaultHome(): Boolean = true
        override fun launchShortcut(appId: AppId, shortcutId: String) = Unit
    }
}
