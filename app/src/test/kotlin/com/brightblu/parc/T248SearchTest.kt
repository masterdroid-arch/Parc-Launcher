package com.brightblu.parc

import com.brightblu.parc.feature.search.SearchRow
import com.brightblu.parc.feature.search.SearchSheetItems
import com.brightblu.parc.feature.search.SearchSheetState
import com.brightblu.parc.feature.search.extras.ExtraResults
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.fakes.FakeAppIndex
import com.brightblu.parc.model.fakes.FakeSearchEngine
import com.brightblu.parc.model.fakes.FakeUsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T248: the empty-field suggestions, the Enter target and the section order. */
@OptIn(ExperimentalCoroutinesApi::class)
class T248SearchTest {

    private fun app(label: String) = AppEntry(AppId("p.$label/Main@0"), label, "p.$label", false, 0)

    private val maps = app("Maps")
    private val music = app("Music")
    private val notes = app("Notes")
    private val files = app("Files")
    private val clock = app("Clock")
    private val camera = app("Camera")

    private val all = listOf(maps, music, notes, files, clock, camera)

    private fun TestScope.state(
        recents: List<AppId> = emptyList(),
        mostUsed: List<AppId> = emptyList(),
    ): Pair<SearchSheetState, CoroutineScope> {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val usage = FakeUsageRepository(mostUsedIds = mostUsed)
        // recordLaunch puts each id at the front, so feed the desired newest-first list backwards.
        recents.asReversed().forEach(usage::recordLaunch)
        val state = SearchSheetState(FakeSearchEngine(), FakeAppIndex(all), usage, scope)
        state.open()
        return state to scope
    }

    @Test
    fun `an empty field shows most used first then recent apps, without duplicates`() = runTest {
        val (state, scope) = state(
            recents = listOf(music.id, maps.id),
            mostUsed = listOf(maps.id, notes.id),
        )

        val rows = state.state.value.rows
        assertEquals(listOf("Notes", "Music", "Maps"), rows.map { it.label })
        assertTrue(rows.first().isMostUsed)
        assertTrue(rows.drop(1).all { it.isRecent })
        assertFalse(rows.drop(1).any { it.isMostUsed })
        scope.cancel()
    }

    @Test
    fun `unknown most-used apps are dropped like unknown recents`() = runTest {
        val (state, scope) = state(
            recents = listOf(maps.id),
            mostUsed = listOf(AppId("ghost/ghost.Main@0"), notes.id),
        )

        assertEquals(listOf("Notes", "Maps"), state.state.value.rows.map { it.label })
        scope.cancel()
    }

    @Test
    fun `most used is capped at four and recent apps at six`() = runTest {
        val (used, usedScope) = state(mostUsed = listOf(notes.id, files.id, clock.id, camera.id, maps.id))
        assertEquals(SearchSheetState.MostUsedLimit, used.state.value.rows.size)
        assertEquals(listOf("Notes", "Files", "Clock", "Camera"), used.state.value.rows.map { it.label })
        assertTrue(used.state.value.rows.all { it.isMostUsed })
        usedScope.cancel()

        val (recent, recentScope) = state(
            recents = listOf(notes.id, files.id, clock.id, camera.id, maps.id, music.id),
        )
        assertEquals(SearchSheetState.RecentAppsLimit, recent.state.value.rows.size)
        assertEquals(listOf("Notes", "Files", "Clock", "Camera", "Maps", "Music"), recent.state.value.rows.map { it.label })
        assertTrue(recent.state.value.rows.all { it.isRecent })
        recentScope.cancel()
    }

    @Test
    fun `Enter opens the first suggestion even while the field is empty`() = runTest {
        val (state, scope) = state(recents = listOf(maps.id), mostUsed = listOf(notes.id))

        assertEquals(notes.id, state.topHit())
        scope.cancel()
    }

    @Test
    fun `the suggestion groups and remembered searches are ordered from the field upward`() = runTest {
        val used = SearchRow(notes.id, "Notes", isMostUsed = true)
        val recent = SearchRow(maps.id, "Maps", isRecent = true)
        val items = SearchSheetItems.build(
            query = "",
            earlierQueries = listOf("cal"),
            calculation = null,
            rows = listOf(used, recent),
            contacts = emptyList(),
            extras = ExtraResults.Empty,
        )

        assertEquals(
            listOf(
                notes.id.value,
                "header-${SearchSheetItems.MostUsedSection}",
                maps.id.value,
                "header-${SearchSheetItems.RecentAppsSection}",
                "recent-search-cal",
                "recent-searches-header",
            ),
            items.map { it.key },
        )
    }
}
