package com.brightblu.parc.feature.search

import android.graphics.Bitmap
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.AppIndex
import com.brightblu.parc.model.AppShortcut
import com.brightblu.parc.model.fakes.FakeUsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T67 additions to the sheet state: calculator, web handoff and shortcut sub-rows. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchExtrasStateTest {

    private val instagram = app("Instagram", "com.instagram.android")
    private val threads = app("Threads", "com.instagram.barcelona")

    private class ShortcutIndex(
        entries: List<AppEntry>,
        private val shortcuts: Map<AppId, List<AppShortcut>> = emptyMap(),
    ) : AppIndex {
        private val state = MutableStateFlow(entries)
        override val apps: StateFlow<List<AppEntry>> = state.asStateFlow()
        override fun icon(id: AppId, sizePx: Int): Bitmap? = null
        override fun launch(id: AppId) = Unit
        override fun shortcuts(id: AppId): List<AppShortcut> = shortcuts[id].orEmpty()
    }

    private fun TestScope.collectorScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private fun engine(vararg docs: SearchDoc): LabelSearchEngine =
        LabelSearchEngine().apply { update(docs.toList()) }

    private fun standardEngine(): LabelSearchEngine = engine(
        SearchDoc(instagram.id, instagram.label, instagram.packageName),
        SearchDoc(threads.id, threads.label, threads.packageName),
    )

    private fun newState(
        scope: CoroutineScope,
        appIndex: AppIndex = ShortcutIndex(listOf(instagram, threads)),
        usage: FakeUsageRepository = FakeUsageRepository(),
    ): SearchSheetState = SearchSheetState(standardEngine(), appIndex, usage, scope)

    @Test
    fun `an arithmetic query produces the calculator answer`() = runTest {
        val scope = collectorScope()
        val state = newState(scope)
        state.open()

        state.onQueryChange("12*(3+4)")

        assertEquals(84.0, state.state.value.calculation!!.value, 0.0)
        assertEquals("84", state.state.value.calculation!!.display)
        scope.cancel()
    }

    @Test
    fun `a calculator answer does not invent app rows`() = runTest {
        val scope = collectorScope()
        val state = newState(scope)

        state.onQueryChange("2^8")

        assertEquals("256", state.state.value.calculation!!.display)
        assertTrue(state.state.value.rows.isEmpty())
        scope.cancel()
    }

    @Test
    fun `an ordinary query has no calculator row`() = runTest {
        val scope = collectorScope()
        val state = newState(scope)

        state.onQueryChange("insta")

        assertNull(state.state.value.calculation)
        scope.cancel()
    }

    @Test
    fun `an invalid expression has no calculator row`() = runTest {
        val scope = collectorScope()
        val state = newState(scope)

        state.onQueryChange("1/0")

        assertNull(state.state.value.calculation)
        scope.cancel()
    }

    @Test
    fun `the web row carries the trimmed query and disappears when blank`() = runTest {
        val scope = collectorScope()
        val state = newState(scope)

        state.onQueryChange("  parc launcher  ")
        assertEquals("parc launcher", state.state.value.webQuery)

        state.onQueryChange("   ")
        assertNull(state.state.value.webQuery)
        scope.cancel()
    }

    @Test
    fun `only the top app result carries its shortcuts`() = runTest {
        val scope = collectorScope()
        val shortcut = AppShortcut("directions", "Directions", instagram.id)
        val state = newState(
            scope,
            appIndex = ShortcutIndex(
                listOf(instagram, threads),
                mapOf(instagram.id to listOf(shortcut)),
            ),
        )

        state.onQueryChange("insta")

        val rows = state.state.value.rows
        assertEquals(listOf(shortcut), rows[0].shortcuts)
        assertTrue(rows[1].shortcuts.isEmpty())
        scope.cancel()
    }

    @Test
    fun `shortcut sub-rows are capped at five`() = runTest {
        val scope = collectorScope()
        val many = (1..8).map { AppShortcut("s$it", "Shortcut $it", instagram.id) }
        val state = newState(
            scope,
            appIndex = ShortcutIndex(listOf(instagram, threads), mapOf(instagram.id to many)),
        )

        state.onQueryChange("insta")

        assertEquals(5, state.state.value.rows.first().shortcuts.size)
        scope.cancel()
    }

    @Test
    fun `recent app rows do not carry shortcuts`() = runTest {
        val scope = collectorScope()
        val usage = FakeUsageRepository()
        usage.recordLaunch(instagram.id)
        val shortcut = AppShortcut("directions", "Directions", instagram.id)
        val state = newState(
            scope,
            appIndex = ShortcutIndex(listOf(instagram), mapOf(instagram.id to listOf(shortcut))),
            usage = usage,
        )

        state.open()

        assertTrue(state.state.value.rows.all { it.shortcuts.isEmpty() })
        scope.cancel()
    }

    @Test
    fun `closing forgets the calculator and web query`() = runTest {
        val scope = collectorScope()
        val state = newState(scope)
        state.open()
        state.onQueryChange("2+2")

        state.close()

        assertEquals(SearchUiState(), state.state.value)
        scope.cancel()
    }
}
