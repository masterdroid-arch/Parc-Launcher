package com.brightblu.parc.feature.search

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.MatchRange
import com.brightblu.parc.model.SearchEngine
import com.brightblu.parc.model.SearchHit
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state holder follows flows; an unconfined collector scope makes every emission land before
 * the assertion, so the tests read as straight-line code.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchSheetStateTest {

    private val instagram = app("Instagram", "com.instagram.android")
    private val reddit = app("Reddit", "com.reddit.frontpage")
    private val threads = app("Threads", "com.instagram.barcelona")

    private fun TestScope.collectorScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private fun hitsEngine(vararg docs: SearchDoc): LabelSearchEngine =
        LabelSearchEngine().apply { update(docs.toList()) }

    private fun newState(
        scope: CoroutineScope,
        apps: MutableAppIndex = MutableAppIndex(listOf(instagram, reddit, threads)),
        usage: FakeUsageRepository = FakeUsageRepository(),
        engine: SearchEngine = hitsEngine(
            SearchDoc(instagram.id, instagram.label, instagram.packageName),
            SearchDoc(reddit.id, reddit.label, reddit.packageName),
            SearchDoc(threads.id, threads.label, threads.packageName),
        ),
        recentsLimit: Int = SearchSheetState.DefaultRecentsLimit,
    ): SearchSheetState = SearchSheetState(engine, apps, usage, scope, recentsLimit)

    @Test
    fun `starts closed with no rows`() = runTest {
        val scope = collectorScope()
        val state = newState(scope)
        assertFalse(state.isOpen)
        assertTrue(state.state.value.rows.isEmpty())
        assertFalse(state.state.value.showingRecents)
        scope.cancel()
    }

    @Test
    fun `open marks the sheet open`() = runTest {
        val scope = collectorScope()
        val state = newState(scope)
        state.open()
        assertTrue(state.isOpen)
        assertTrue(state.state.value.showingRecents)
        scope.cancel()
    }

    @Test
    fun `a blank query shows recents, most recent first`() = runTest {
        val scope = collectorScope()
        val usage = FakeUsageRepository()
        usage.recordLaunch(reddit.id)
        usage.recordLaunch(instagram.id)
        val state = newState(scope, usage = usage)
        state.open()
        assertEquals(listOf("Instagram", "Reddit"), state.state.value.rows.map { it.label })
        assertTrue(state.state.value.rows.all { it.isRecent })
        scope.cancel()
    }

    @Test
    fun `a non-blank query shows engine hits with labels and ranges`() = runTest {
        val scope = collectorScope()
        val state = newState(scope)
        state.open()
        state.onQueryChange("insta")
        val rows = state.state.value.rows
        assertEquals(listOf("Instagram", "Threads"), rows.map { it.label })
        assertEquals(listOf(MatchRange(0, 5)), rows.first().matchRanges)
        assertFalse(state.state.value.showingRecents)
        scope.cancel()
    }

    @Test
    fun `whitespace-only query falls back to recents`() = runTest {
        val scope = collectorScope()
        val usage = FakeUsageRepository()
        usage.recordLaunch(reddit.id)
        val state = newState(scope, usage = usage)
        state.open()
        state.onQueryChange("   ")
        assertTrue(state.state.value.showingRecents)
        assertEquals(listOf("Reddit"), state.state.value.rows.map { it.label })
        scope.cancel()
    }

    @Test
    fun `rows for unknown apps are dropped`() = runTest {
        val scope = collectorScope()
        val engine = FakeSearchEngine(listOf(SearchHit(AppId("ghost/ghost.Main@0"), 5.0, emptyList())))
        val state = newState(scope, engine = engine)
        state.open()
        state.onQueryChange("ghost")
        assertTrue(state.state.value.rows.isEmpty())
        assertFalse(state.state.value.showingRecents)
        scope.cancel()
    }

    @Test
    fun `duplicate hits collapse to one row`() = runTest {
        val scope = collectorScope()
        val hit = SearchHit(instagram.id, 5.0, emptyList())
        val state = newState(scope, engine = FakeSearchEngine(listOf(hit, hit)))
        state.onQueryChange("instagram")
        assertEquals(listOf("Instagram"), state.state.value.rows.map { it.label })
        scope.cancel()
    }

    @Test
    fun `top hit is the first row`() = runTest {
        val scope = collectorScope()
        val state = newState(scope)
        assertNull(state.topHit())
        state.onQueryChange("insta")
        assertEquals(instagram.id, state.topHit())
        scope.cancel()
    }

    @Test
    fun `close clears the query, rows and the ABC flag`() = runTest {
        val scope = collectorScope()
        val state = newState(scope)
        state.open()
        state.onQueryChange("insta")
        state.openAbcList()
        state.close()
        assertEquals(SearchUiState(), state.state.value)
        scope.cancel()
    }

    @Test
    fun `the ABC list flag toggles`() = runTest {
        val scope = collectorScope()
        val state = newState(scope)
        state.openAbcList()
        assertTrue(state.state.value.abcListOpen)
        state.closeAbcList()
        assertFalse(state.state.value.abcListOpen)
        scope.cancel()
    }

    @Test
    fun `highlight is null until a real query exists`() = runTest {
        val scope = collectorScope()
        val state = newState(scope)
        assertNull(state.highlight())
        state.open()
        assertNull(state.highlight())
        state.onQueryChange("insta")
        assertEquals(setOf(instagram.id, threads.id), state.highlight())
        scope.cancel()
    }

    @Test
    fun `a query with no matches highlights an empty set`() = runTest {
        val scope = collectorScope()
        val state = newState(scope, engine = FakeSearchEngine(emptyList()))
        state.open()
        state.onQueryChange("zzz")
        assertEquals(emptySet<AppId>(), state.highlight())
        scope.cancel()
    }

    @Test
    fun `relabelled apps refresh on the next index emission`() = runTest {
        val scope = collectorScope()
        val apps = MutableAppIndex(listOf(instagram))
        val state = newState(scope, apps = apps)
        state.onQueryChange("instagram")
        assertEquals(listOf("Instagram"), state.state.value.rows.map { it.label })
        apps.set(listOf(instagram.copy(label = "Instagram Beta")))
        assertEquals(listOf("Instagram Beta"), state.state.value.rows.map { it.label })
        scope.cancel()
    }

    @Test
    fun `recents respect the configured limit`() = runTest {
        val scope = collectorScope()
        val usage = FakeUsageRepository()
        usage.recordLaunch(reddit.id)
        usage.recordLaunch(instagram.id)
        val state = newState(scope, usage = usage, recentsLimit = 1)
        state.open()
        assertEquals(listOf("Instagram"), state.state.value.rows.map { it.label })
        scope.cancel()
    }

    @Test
    fun `cancelling stops following the app index`() = runTest {
        val scope = collectorScope()
        val apps = MutableAppIndex(listOf(instagram, threads))
        val state = newState(scope, apps = apps)
        state.onQueryChange("insta")
        assertEquals(listOf("Instagram", "Threads"), state.state.value.rows.map { it.label })
        state.cancel()
        apps.set(listOf(instagram.copy(label = "Renamed")))
        assertEquals(listOf("Instagram", "Threads"), state.state.value.rows.map { it.label })
        scope.cancel()
    }
}
