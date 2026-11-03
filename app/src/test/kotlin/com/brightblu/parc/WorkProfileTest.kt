package com.brightblu.parc

import com.brightblu.parc.core.data.profiles.LaunchOutcome
import com.brightblu.parc.core.data.profiles.ProfileInfo
import com.brightblu.parc.core.data.profiles.ProfileKind
import com.brightblu.parc.core.data.profiles.ProfileSet
import com.brightblu.parc.feature.search.LabelSearchEngine
import com.brightblu.parc.feature.search.SearchSheetState
import com.brightblu.parc.model.*
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import com.brightblu.parc.ring.RingLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T75: the badge projection, the paused dim, the launch routing and the exact notice text. The
 * renderer only reads `RingSlot.isWork`, so its drawing is reviewed on the emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkProfileTest {
    private val geometry = RingLayout(384f, 853f)
    private val personalId = AppId("com.personal/.Main@0")
    private val workId = AppId("com.work/.Main@10")
    private val folderId = FolderId("work")
    private val personal = AppEntry(personalId, "Personal", "com.personal", false, 0)
    private val work = AppEntry(workId, "Work", "com.work", false, 0)

    private val profiles = ProfileSet(0L, listOf(
        ProfileInfo(0L, ProfileKind.Personal, paused = false),
        ProfileInfo(10L, ProfileKind.Work, paused = false),
    ))
    private val paused = profiles.copy(profiles = profiles.profiles.map {
        if (it.serial == 10L) it.copy(paused = true) else it
    })
    private var refusal: LaunchOutcome? = null

    private fun home(profiles: ProfileSet = this.profiles, markWorkApps: Boolean = true,
        apps: List<AppEntry> = listOf(personal, work)): RingState {
        val arc = geometry.arcs.first()
        val layout = ParcLayout(arcs = listOf(ArcAssignment(arc,
            mapOf(0 to Slot.App(personalId), 1 to Slot.App(workId)))))
        return LauncherScenes.build(geometry, layout, apps, ShellState(), "09:41",
            profiles = profiles, markWorkApps = markWorkApps) { null }
    }

    private fun allApps(profiles: ProfileSet = this.profiles, markWorkApps: Boolean = true): RingState =
        LauncherScenes.build(geometry, ParcLayout(), listOf(personal, work),
            ShellState(layer = Layer.AllApps), "09:41",
            profiles = profiles, markWorkApps = markWorkApps) { null }

    private fun RingState.slot(id: AppId): RingSlot =
        slots.first { (it.slot as? Slot.App)?.id == id }

    @Test fun homeMarksTheWorkAppAndLeavesThePersonalAppPlain() {
        val scene = home()
        assertTrue(scene.slot(workId).isWork)
        assertFalse(scene.slot(personalId).isWork)
    }

    @Test fun allAppsMarksWorkAppsWhileMarkWorkAppsIsOn() {
        assertTrue(allApps().slot(workId).isWork)
        assertFalse(allApps().slot(personalId).isWork)
    }

    @Test fun turningMarkWorkAppsOffClearsEveryView() {
        assertFalse(home(markWorkApps = false).slot(workId).isWork)
        assertFalse(allApps(markWorkApps = false).slot(workId).isWork)
    }

    @Test fun aWorkAppInsideAFolderKeepsItsBadge() {
        val arc = geometry.arcs.first()
        val layout = ParcLayout(
            arcs = listOf(ArcAssignment(arc, mapOf(0 to Slot.Folder(folderId)))),
            folders = listOf(Folder(folderId, "Work", appIds = listOf(workId))),
        )
        val scene = LauncherScenes.build(geometry, layout, listOf(work),
            ShellState(layer = Layer.Folder, folderId = folderId), "",
            profiles = profiles) { null }
        assertTrue(scene.slot(workId).isWork)
    }

    @Test fun aPausedProfileAppIsDimmedButNotMarkedAsSuspended() {
        val scene = home(profiles = paused)
        assertFalse(scene.slot(workId).enabled)
        assertTrue(scene.slot(personalId).enabled)
    }

    @Test fun aSuspendedAppStaysDimmedWithoutAWorkProfile() {
        val scene = home(profiles = ProfileSet(), apps = listOf(personal, work.copy(suspended = true)))
        assertFalse(scene.slot(workId).enabled)
        assertFalse(scene.slot(workId).isWork)
    }

    @Test fun theNoticeIsOnlyShownForProfileRefusals() {
        assertEquals("Work profile paused", workProfileNotice(LaunchOutcome.ProfilePaused))
        assertEquals("Work profile unavailable", workProfileNotice(LaunchOutcome.ProfileRemoved))
        assertNull(workProfileNotice(LaunchOutcome.Started))
        assertNull(workProfileNotice(LaunchOutcome.Unavailable))
    }


    private class Index(entries: List<AppEntry>) : AppIndex {
        override val apps = MutableStateFlow(entries)
        val launched = mutableListOf<AppId>()
        override fun icon(id: AppId, sizePx: Int): android.graphics.Bitmap? = null
        override fun launch(id: AppId) { launched += id }
        override fun shortcuts(id: AppId): List<AppShortcut> = emptyList()
    }

    private class Usage : UsageRepository {
        val recorded = mutableListOf<AppId>()
        override fun recordLaunch(id: AppId) { recorded += id }
        override fun recents(limit: Int) = flowOf(emptyList<AppId>())
        override fun rank(folderId: FolderId) = emptyList<AppId>()
    }

    private class Shortcuts : ShortcutHost {
        override fun isDefaultHome(): Boolean = true
        override fun launchShortcut(appId: AppId, shortcutId: String) = Unit
    }

    @Test fun aPausedLaunchIsRefusedAndExplainedWithoutRecordingUsage() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        try {
            val index = Index(listOf(work))
            val usage = Usage()
            val session = LauncherSession(ShellNavigation(), index, usage,
                FakeLayoutRepository(ParcLayout()), SearchSheetState(LabelSearchEngine(), index, usage, scope),
                {}, {}, Shortcuts(), scope,
                launchApp = { LaunchOutcome.ProfilePaused },
                onLaunchRefused = { refusal = it })
            session.launch(workId)
            assertEquals(LaunchOutcome.ProfilePaused, refusal)
            assertTrue(usage.recorded.isEmpty())
            assertTrue(index.launched.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun aStartedWorkLaunchRecordsUsageOnce() {
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        try {
            val index = Index(listOf(work))
            val usage = Usage()
            var refusals = 0
            val session = LauncherSession(ShellNavigation(), index, usage,
                FakeLayoutRepository(ParcLayout()), SearchSheetState(LabelSearchEngine(), index, usage, scope),
                {}, {}, Shortcuts(), scope,
                launchApp = { LaunchOutcome.Started },
                onLaunchRefused = { refusals++ })
            session.launch(workId)
            assertEquals(listOf(workId), usage.recorded)
            assertEquals(0, refusals)
        } finally { scope.cancel() }
    }
}
