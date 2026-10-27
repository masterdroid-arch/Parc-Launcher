package com.brightblu.parc

import com.brightblu.parc.core.data.profiles.LaunchOutcome
import com.brightblu.parc.feature.search.LabelSearchEngine
import com.brightblu.parc.feature.search.SearchSheetState
import com.brightblu.parc.model.*
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import com.brightblu.parc.ring.HubGesture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T82: end-to-end hub tap chain with a fake clock. Drives the real [HubGesture] state machine,
 * the real [HubTapDispatcher] inside [LauncherSession], and [LauncherSession.onHubTap], which is
 * exactly the path a released hub tap takes from the renderer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HubTapChainTest {
    private val lastAppId = AppId("fixture/.Last@0")
    private val entry = AppEntry(lastAppId, "Last", "fixture", false, 0)

    private class Index(entries: List<AppEntry>) : AppIndex {
        override val apps = MutableStateFlow(entries)
        override fun icon(id: AppId, sizePx: Int): android.graphics.Bitmap? = null
        override fun launch(id: AppId) = Unit
        override fun shortcuts(id: AppId) = emptyList<AppShortcut>()
    }

    private class Usage : UsageRepository {
        override fun recordLaunch(id: AppId) = Unit
        override fun recents(limit: Int) = flowOf(emptyList<AppId>())
        override fun rank(folderId: FolderId) = emptyList<AppId>()
    }

    private class Shortcuts : ShortcutHost {
        override fun isDefaultHome() = true
        override fun launchShortcut(appId: AppId, shortcutId: String) = Unit
    }

    /** Owns the session scope so the test body can end without waiting for its collectors. */
    private class Chain(
        val gesture: HubGesture = HubGesture(),
        val navigation: ShellNavigation,
        val session: LauncherSession,
        val launched: MutableList<AppId>,
        private val scope: CoroutineScope,
    ) {
        fun close() = scope.cancel()
    }

    /** Builds the real session on the test scheduler; the dispatcher clock is virtual time. */
    private fun TestScope.chain(doubleTap: Boolean = true, lastApp: AppId? = lastAppId): Chain {
        val index = Index(listOf(entry))
        val usage = Usage()
        val launched = mutableListOf<AppId>()
        val navigation = ShellNavigation()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val search = SearchSheetState(LabelSearchEngine(), index, usage, scope)
        val session = LauncherSession(navigation, index, usage, FakeLayoutRepository(), search, {}, {},
            Shortcuts(), scope, lastApp = { lastApp }, hubDoubleTapEnabled = doubleTap,
            hubTapClock = { testScheduler.currentTime }, launchApp = { launched += it; LaunchOutcome.Started })
        return Chain(navigation = navigation, session = session, launched = launched, scope = scope)
    }

    /**
     * One physical tap: down, optional jitter move, release. Returns the gesture result. The
     * dispatcher is fed only when the state machine reports TAP, as the renderer does.
     */
    private fun TestScope.press(chain: Chain, durationMs: Long, reachDp: Float, jitterDp: Float): Int {
        val down = testScheduler.currentTime
        chain.gesture.down(true, down)
        if (jitterDp > 0f) chain.gesture.move(reachDp + jitterDp)
        val result = chain.gesture.release(reachDp + jitterDp, down + durationMs, -1, jitterDp)
        if (result == HubGesture.TAP) chain.session.onHubTap()
        return result
    }

    @Test fun everyTimingOffsetAndJitterRowTogglesExactlyOnce() = runTest {
        val c = chain()
        try {
            var expected = Layer.Home
            for (duration in listOf(0L, 40L, 80L, 120L, 250L))
                for (offset in listOf(0f, 18f, 25f, 34f))
                    for (jitter in listOf(0f, 1f, 3f, 6f)) {
                        val row = "duration=$duration offset=$offset jitter=$jitter"
                        assertEquals(row, HubGesture.TAP, press(c, duration, offset, jitter))
                        assertEquals(row, expected, c.navigation.state.value.layer)
                        advanceTimeBy(Metrics.hubDoubleTapMs)
                        runCurrent()
                        expected = if (expected == Layer.Home) Layer.AllApps else Layer.Home
                        assertEquals(row, expected, c.navigation.state.value.layer)
                    }
            assertEquals(0, c.launched.size)
        } finally { c.close() }
    }

    @Test fun instantTapWithNoMoveEventsTogglesAndIsNotLost() = runTest {
        val c = chain()
        try {
            val down = testScheduler.currentTime
            c.gesture.down(true, down)
            assertEquals(HubGesture.TAP, c.gesture.release(0f, down, -1, 0f))
            c.session.onHubTap()
            advanceTimeBy(Metrics.hubDoubleTapMs - 1)
            runCurrent()
            assertEquals(Layer.Home, c.navigation.state.value.layer)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(Layer.AllApps, c.navigation.state.value.layer)
        } finally { c.close() }
    }

    @Test fun doubleTapWithinWindowLaunchesLastAppOnceAndDoesNotToggle() = runTest {
        val c = chain(doubleTap = true)
        try {
            assertEquals(HubGesture.TAP, press(c, 80, 0f, 0f))
            advanceTimeBy(120)
            assertEquals(HubGesture.TAP, press(c, 80, 25f, 2f))
            advanceUntilIdle()
            assertEquals(listOf(lastAppId), c.launched)
            assertEquals(Layer.Home, c.navigation.state.value.layer)
        } finally { c.close() }
    }

    @Test fun doubleTapInsideWindowStillPairs() = runTest {
        val c = chain(doubleTap = true)
        try {
            press(c, 40, 0f, 0f)
            advanceTimeBy(Metrics.hubDoubleTapMs - 1)
            press(c, 40, 0f, 0f)
            advanceUntilIdle()
            assertEquals(listOf(lastAppId), c.launched)
            assertEquals(Layer.Home, c.navigation.state.value.layer)
        } finally { c.close() }
    }

    @Test fun doubleTapAfterWindowIsTwoIndependentToggles() = runTest {
        val c = chain(doubleTap = true)
        try {
            press(c, 40, 0f, 0f)
            advanceTimeBy(Metrics.hubDoubleTapMs + 1)
            runCurrent()
            assertEquals(Layer.AllApps, c.navigation.state.value.layer)
            press(c, 40, 0f, 0f)
            advanceUntilIdle()
            assertEquals(Layer.Home, c.navigation.state.value.layer)
            assertEquals(0, c.launched.size)
        } finally { c.close() }
    }

    @Test fun settingOffTogglesImmediatelyAndNeverOpensLastApp() = runTest {
        val c = chain(doubleTap = false)
        try {
            press(c, 0, 0f, 0f)
            assertEquals(Layer.AllApps, c.navigation.state.value.layer)
            press(c, 0, 30f, 4f)
            assertEquals(Layer.Home, c.navigation.state.value.layer)
            advanceUntilIdle()
            assertEquals(0, c.launched.size)
        } finally { c.close() }
    }

    @Test fun missingLastAppIsASafeNoOpForDoubleTap() = runTest {
        val c = chain(doubleTap = true, lastApp = null)
        try {
            press(c, 60, 0f, 0f)
            advanceTimeBy(100)
            press(c, 60, 0f, 0f)
            advanceUntilIdle()
            assertEquals(0, c.launched.size)
            assertEquals(Layer.Home, c.navigation.state.value.layer)
        } finally { c.close() }
    }

    @Test fun longPressDoesNotToggle() = runTest {
        val c = chain()
        try {
            val down = testScheduler.currentTime
            c.gesture.down(true, down)
            assertTrue(c.gesture.longPress(down + Metrics.longPressMs))
            assertEquals(-1, c.gesture.release(0f, down + Metrics.longPressMs + 10, -1, 0f))
            advanceUntilIdle()
            assertEquals(Layer.Home, c.navigation.state.value.layer)
            assertEquals(0, c.launched.size)
        } finally { c.close() }
    }
}
