package com.brightblu.parc

import com.brightblu.parc.core.data.focus.FocusDay
import com.brightblu.parc.core.data.focus.FocusMode
import com.brightblu.parc.core.data.focus.FocusSchedule
import com.brightblu.parc.core.data.focus.FocusState
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.AppIndex
import com.brightblu.parc.model.AppShortcut
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T73: the view filter, the manual switch and the editable mode draft. */
class FocusModesTest {

    private fun app(label: String, packageName: String) =
        AppEntry(AppId("$packageName/.Main"), label, packageName, isSystem = false, installTime = 0L)

    private val social = app("Social", "com.test.social")
    private val news = app("News", "com.test.news")
    private val maps = app("Maps", "com.test.maps")

    private fun mode(
        name: String,
        packages: Set<String> = emptySet(),
        badges: Boolean = false,
        days: Set<FocusDay> = setOf(FocusDay.Mon, FocusDay.Tue),
        start: Int = 22 * 60,
        end: Int = 7 * 60,
        enabled: Boolean = true,
    ) = FocusMode(name, packages, badges, FocusSchedule(days, start, end, enabled))

    @Test fun theClockLineAppearsOnlyWhileAModeIsActive() {
        assertEquals("", FocusDisplay.clockLine(FocusState()))
        assertEquals("focus · Work", FocusDisplay.clockLine(FocusState(active = mode("Work"))))
    }

    @Test fun summaryAndHubLabelsDistinguishNoModesFromAnInactiveMode() {
        assertEquals("No modes", FocusDisplay.summary(FocusState(), emptyList()))
        assertEquals("Off", FocusDisplay.summary(FocusState(), listOf(mode("Work"))))
        assertEquals("Work", FocusDisplay.summary(FocusState(active = mode("Work")), listOf(mode("Work"))))
        assertEquals("Focus", FocusDisplay.hubLabel(FocusState()))
        assertEquals("Work", FocusDisplay.hubLabel(FocusState(active = mode("Work"))))
        assertTrue(FocusDisplay.hubDescription(FocusState()).contains("on"))
        assertTrue(FocusDisplay.hubDescription(FocusState(active = mode("Work"))).contains("off"))
    }

    @Test fun badgesFollowTheSettingAndTheActiveModesFlag() {
        assertTrue(FocusFilter.badgesVisible(true, FocusState()))
        assertFalse(FocusFilter.badgesVisible(false, FocusState()))
        assertFalse(FocusFilter.badgesVisible(true, FocusState(active = mode("Work", badges = true))))
        assertTrue(FocusFilter.badgesVisible(true, FocusState(active = mode("Work", badges = false))))
    }

    @Test fun theModeRowDetailReadsNaturallyForOneApp() {
        assertEquals("Mon, Tue · 22:00-07:00 · 1 app",
            FocusDisplay.modeDetail(mode("Work", setOf("com.test.social"))))
        assertEquals("Mon, Tue · 22:00-07:00 · 2 apps",
            FocusDisplay.modeDetail(mode("Work", setOf("com.test.social", "com.test.news"))))
        assertEquals("No days · 22:00-07:00 · 0 apps",
            FocusDisplay.modeDetail(mode("Work", days = emptySet())))
    }

    @Test fun theManualSwitchTurnsTheFirstModeOnAndTheActiveModeOff() {
        assertEquals(FocusManualAction.None, FocusManual.toggle(FocusState(), emptyList()))
        assertEquals(FocusManualAction.On("Work"),
            FocusManual.toggle(FocusState(), listOf(mode("Work"), mode("Evening"))))
        assertEquals(FocusManualAction.Off("Evening"),
            FocusManual.toggle(FocusState(active = mode("Evening")), listOf(mode("Work"), mode("Evening"))))
    }

    @Test fun theFilterHidesByPackageAndKeepsTheListWhenNothingIsHidden() {
        val all = listOf(social, news, maps)
        assertSame(all, FocusFilter.visibleApps(all, emptySet()))
        assertEquals(listOf(news, maps),
            FocusFilter.visibleApps(all, setOf("com.test.social")))
        assertFalse(FocusFilter.isVisible(social, setOf("com.test.social")))
        assertTrue(FocusFilter.isVisible(news, setOf("com.test.social")))
    }

    @Test fun aNewDraftIsInertUntilDaysArePicked() {
        val draft = FocusDraft.new()
        assertTrue(draft.isNew)
        assertTrue(draft.days.isEmpty())
        assertEquals("22:00", formatMinuteOfDay(draft.startMinute))
        assertEquals("07:00", formatMinuteOfDay(draft.endMinute))
        assertEquals("22:00-07:00 · No days", draft.scheduleSummary())
        val monday = draft.toggleDay(FocusDay.Mon)
        assertTrue(FocusDay.Mon in monday.days)
        assertTrue(FocusDay.Mon !in monday.toggleDay(FocusDay.Mon).days)
    }

    @Test fun timeStepsWrapAtMidnight() {
        assertEquals(0, stepMinuteOfDay(23 * 60 + 45, 15))
        assertEquals(23 * 60 + 45, stepMinuteOfDay(0, -15))
        assertEquals(0, stepMinuteOfDay(0, 0))
        assertEquals("00:00", formatMinuteOfDay(0))
        assertEquals("23:59", formatMinuteOfDay(FocusSchedule.MinutesPerDay - 1))
        assertEquals("00:00", formatMinuteOfDay(FocusSchedule.MinutesPerDay))
    }

    @Test fun draftValidationRejectsBlankLengthAndDuplicateNames() {
        assertEquals("Name required", FocusDraft.new().issue(emptyList()))
        assertEquals("Name required", FocusDraft.new().copy(name = "   ").issue(emptyList()))
        assertEquals("Name already used", FocusDraft.new().copy(name = "Work").issue(listOf("Work")))
        assertEquals("", FocusDraft.new().copy(name = "Work").issue(listOf("Evening")))
        assertEquals("", FocusDraft.of(mode("Work")).copy(name = "Work").issue(listOf("Work")))
        val long = FocusDraft.new().copy(name = "x".repeat(FocusDisplay.MaxNameLength + 1))
        assertEquals("Name is too long", long.issue(emptyList()))
    }

    @Test fun aSavedDraftCarriesEveryFieldBackToTheRepositoryModel() {
        val draft = FocusDraft.new().copy(
            name = "  Work  ",
            hiddenPackages = setOf("com.test.social"),
            silenceBadges = true,
            days = setOf(FocusDay.Mon, FocusDay.Fri),
            startMinute = 8 * 60,
            endMinute = 18 * 60,
            scheduleEnabled = false,
        )
        val saved = draft.toMode()
        assertEquals("Work", saved.name)
        assertEquals(setOf("com.test.social"), saved.hiddenPackages)
        assertTrue(saved.silenceBadges)
        assertEquals(setOf(FocusDay.Mon, FocusDay.Fri), saved.schedule.days)
        assertFalse(saved.schedule.enabled)
        assertEquals(draft.copy(name = "Work", originalName = "Work"), FocusDraft.of(saved))
    }

    @Test fun theAppPickerMatchesLabelOrPackageAndIgnoresCase() {
        val apps = listOf(social, news, maps)
        assertSame(apps, filterFocusApps(apps, "  "))
        assertEquals(listOf(news), filterFocusApps(apps, "NEW"))
        assertEquals(listOf(social), filterFocusApps(apps, "com.test.soc"))
        assertTrue(filterFocusApps(apps, "zzz").isEmpty())
    }


    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun theSearchIndexViewFollowsTheActiveModeAndDelegatesTheRest() = runTest {
        val delegate = FakeAppIndex(listOf(social, news, maps))
        val state = MutableStateFlow(FocusState())
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler) +
                kotlinx.coroutines.Job())
        val view = FocusAppIndex(delegate, state, scope)
        runCurrent()
        assertEquals(listOf(social, news, maps), view.apps.value)

        state.value = FocusState(active = mode("Work", setOf("com.test.news")))
        runCurrent()
        assertEquals(listOf(social, maps), view.apps.value)

        state.value = FocusState()
        runCurrent()
        assertEquals(listOf(social, news, maps), view.apps.value)
        scope.cancel()

        view.launch(social.id)
        assertEquals(listOf(social.id), delegate.launched)
        assertEquals(emptyList<AppShortcut>(), view.shortcuts(social.id))
        assertNull(view.icon(social.id, 112))
    }

    @Test fun hubQuickStartSkipsDisabledModes() {
        val disabled = mode("First").copy(enabled = false)
        val enabled = mode("Second")
        assertEquals(FocusManualAction.On("Second"), FocusManual.toggle(FocusState(), listOf(disabled, enabled)))
        assertEquals(FocusManualAction.None, FocusManual.toggle(FocusState(), listOf(disabled)))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun searchStartsWithTheCurrentAllowListAndTracksFolderMembershipAndFriction() = runTest {
        val delegate = FakeAppIndex(listOf(social, news, maps))
        val mode = com.brightblu.parc.core.data.focus.FocusMode("Quiet", setOf(maps.packageName),
            appPolicy = com.brightblu.parc.core.data.focus.FocusAppPolicy.AllowList, folderIds = setOf("tools"))
        val state = MutableStateFlow(FocusState(active = mode))
        val folder = com.brightblu.parc.model.Folder(com.brightblu.parc.model.FolderId("tools"), "Tools", appIds = listOf(social.id))
        val layout = MutableStateFlow(com.brightblu.parc.model.ParcLayout(folders = listOf(folder)))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler) + kotlinx.coroutines.Job())
        try {
            val view = FocusAppIndex(delegate, state, scope, layout)
            assertEquals(listOf(social, maps), view.apps.value)
            runCurrent()
            layout.value = layout.value.copy(folders = listOf(folder.copy(appIds = listOf(news.id))))
            runCurrent()
            assertEquals(listOf(news, maps), view.apps.value)
            state.value = FocusState(active = mode.copy(pauseBeforeOpen = true))
            runCurrent()
            assertEquals(listOf(social, news, maps), view.apps.value)
        } finally { scope.cancel() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun emergencyPassRestoresFocusAppsButNeverLockedApps() = runTest {
        val delegate = FakeAppIndex(listOf(social, news, maps))
        val state = MutableStateFlow(FocusState(active = mode("Study", setOf(news.packageName))))
        val locked = MutableStateFlow(setOf(social.packageName))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.Job())
        try {
            val view = FocusAppIndex(delegate, state, scope, locked = locked)
            runCurrent(); assertEquals(listOf(maps), view.apps.value)
            state.value = FocusState(breakUntilMillis = 300_000)
            runCurrent(); assertEquals(listOf(news, maps), view.apps.value)
            assertTrue(view.shortcuts(social.id).isEmpty())
            assertFalse(view.apps.value.any { it.id == social.id })
        } finally { scope.cancel() }
    }

    private class FakeAppIndex(initial: List<AppEntry>) : AppIndex {
        override val apps: StateFlow<List<AppEntry>> = MutableStateFlow(initial)
        val launched = mutableListOf<AppId>()
        override fun icon(id: AppId, sizePx: Int): android.graphics.Bitmap? = null
        override fun launch(id: AppId) { launched += id }
        override fun shortcuts(id: AppId): List<AppShortcut> = emptyList()
    }
}
