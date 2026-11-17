package com.brightblu.parc.feature.search

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

/** T194: prefixes, the mode registry, reset on open, the Backspace rule, and that Apps is untouched. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchModesTest {
    private val all = SearchModes.standard(listOf(PlaceholderSearchMode.translate()))
    private val noInternet = SearchModes.standard()

    private fun detect(text: String, modes: List<SearchMode> = all) = SearchModes.detectPrefix(text, modes)

    @Test fun `each prefix with its space switches mode and is removed`() {
        assertEquals(ModePrefixMatch(SearchModeIds.Translate, "hello"), detect("tr hello"))
        assertEquals(ModePrefixMatch(SearchModeIds.Note, "buy milk"), detect("n buy milk"))
        assertEquals(ModePrefixMatch(SearchModeIds.Calculate, "2+2"), detect("= 2+2"))
    }

    @Test fun `a prefix alone with its space gives empty text`() {
        assertEquals(ModePrefixMatch(SearchModeIds.Translate, ""), detect("tr "))
        assertEquals(ModePrefixMatch(SearchModeIds.Note, ""), detect("n "))
        assertEquals(ModePrefixMatch(SearchModeIds.Calculate, ""), detect("= "))
    }

    @Test fun `real app searches never trigger`() {
        assertNull(detect("transit"))
        assertNull(detect("tr"))
        assertNull(detect("n"))
        assertNull(detect("notes"))
        assertNull(detect("=5"))
        assertNull(detect("trip planner"))
        assertNull(detect(""))
    }

    @Test fun `case does not matter`() {
        assertEquals(ModePrefixMatch(SearchModeIds.Translate, "Hello"), detect("TR Hello"))
        assertEquals(ModePrefixMatch(SearchModeIds.Note, "x"), detect("N x"))
    }

    @Test fun `leading spaces and tabs do not trigger and only one space counts`() {
        assertNull(detect(" tr hello"))
        assertNull(detect(" n x"))
        assertNull(detect("tr\thello"))
        assertEquals(ModePrefixMatch(SearchModeIds.Translate, " hello"), detect("tr  hello"))
    }

    @Test fun `translate is not a prefix where the mode is absent`() {
        assertNull(detect("tr hello", noInternet))
        assertEquals(listOf(SearchModeIds.Note, SearchModeIds.Calculate), noInternet.map { it.id })
    }

    @Test fun `modes are listed in chip order and a real mode replaces its placeholder`() {
        assertEquals(
            listOf(SearchModeIds.Translate, SearchModeIds.Note, SearchModeIds.Calculate),
            all.map { it.id },
        )
        val real = PlaceholderSearchMode(SearchModeIds.Note, "Note", "Real", "n ")
        val modes = SearchModes.standard(listOf(real))
        assertEquals("Real", modes.first { it.id == SearchModeIds.Note }.hint)
        assertEquals(2, modes.size)
    }

    @Test fun `backspace rule`() {
        assertEquals(SearchModeIds.Apps, SearchModes.afterBackspace(SearchModeIds.Note, ""))
        assertEquals(SearchModeIds.Note, SearchModes.afterBackspace(SearchModeIds.Note, "a"))
        assertEquals(SearchModeIds.Apps, SearchModes.afterBackspace(SearchModeIds.Apps, ""))
    }

    private fun TestScope.scope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private fun state(scope: CoroutineScope, modes: List<SearchMode> = all): SearchSheetState {
        val instagram = app("Instagram", "com.instagram.android")
        val transit = app("Transit", "com.example.transit")
        val engine = LabelSearchEngine().apply {
            update(listOf(
                SearchDoc(instagram.id, instagram.label, instagram.packageName),
                SearchDoc(transit.id, transit.label, transit.packageName),
            ))
        }
        return SearchSheetState(engine, MutableAppIndex(listOf(instagram, transit)), FakeUsageRepository(), scope, modes = modes)
    }

    @Test fun `search opens in Apps`() = runTest {
        val scope = scope()
        val s = state(scope)
        s.open()
        assertEquals(SearchModeIds.Apps, s.state.value.mode)
        scope.cancel()
    }

    @Test fun `a prefix typed in Apps switches mode and clears the prefix`() = runTest {
        val scope = scope()
        val s = state(scope)
        s.open()
        s.onQueryChange("tr ")
        assertEquals(SearchModeIds.Translate, s.state.value.mode)
        assertEquals("", s.state.value.query)
        s.onQueryChange("hello")
        assertEquals("hello", s.state.value.query)
        scope.cancel()
    }

    @Test fun `typing a prefix inside another mode is plain text`() = runTest {
        val scope = scope()
        val s = state(scope)
        s.open()
        s.setMode(SearchModeIds.Note)
        s.onQueryChange("tr stays text")
        assertEquals(SearchModeIds.Note, s.state.value.mode)
        assertEquals("tr stays text", s.state.value.query)
        scope.cancel()
    }

    @Test fun `transit still searches apps`() = runTest {
        val scope = scope()
        val s = state(scope)
        s.open()
        s.onQueryChange("transit")
        assertEquals(SearchModeIds.Apps, s.state.value.mode)
        assertEquals(listOf("Transit"), s.state.value.rows.map { it.label })
        assertEquals("Transit", s.state.value.rows.first().label)
        assertTrue(s.topHit() != null)
        scope.cancel()
    }

    @Test fun `a chip tap keeps the text and ignores unknown ids`() = runTest {
        val scope = scope()
        val s = state(scope, noInternet)
        s.open()
        s.onQueryChange("hello")
        s.setMode(SearchModeIds.Calculate)
        assertEquals(SearchModeIds.Calculate, s.state.value.mode)
        assertEquals("hello", s.state.value.query)
        s.setMode(SearchModeIds.Translate)
        assertEquals(SearchModeIds.Calculate, s.state.value.mode)
        s.setMode(SearchModeIds.Apps)
        assertEquals(SearchModeIds.Apps, s.state.value.mode)
        scope.cancel()
    }

    @Test fun `other modes show no app rows, no web query, no dimming and no top hit`() = runTest {
        val scope = scope()
        val s = state(scope)
        s.open()
        s.onQueryChange("insta")
        assertTrue(s.highlight() != null)
        s.setMode(SearchModeIds.Note)
        assertTrue(s.state.value.rows.isEmpty())
        assertNull(s.state.value.webQuery)
        assertNull(s.state.value.calculation)
        assertNull(s.highlight())
        assertNull(s.topHit())
        s.setMode(SearchModeIds.Apps)
        assertEquals(listOf("Instagram"), s.state.value.rows.map { it.label })
        scope.cancel()
    }

    @Test fun `mode resets when the sheet is closed and opened again`() = runTest {
        val scope = scope()
        val s = state(scope)
        s.open()
        s.setMode(SearchModeIds.Calculate)
        s.close()
        s.open()
        assertEquals(SearchModeIds.Apps, s.state.value.mode)
        scope.cancel()
    }

    @Test fun `open on an open sheet keeps its mode`() = runTest {
        val scope = scope()
        val s = state(scope)
        s.open()
        s.setMode(SearchModeIds.Note)
        s.open()
        assertEquals(SearchModeIds.Note, s.state.value.mode)
        scope.cancel()
    }

    @Test fun `backspace on empty text returns to Apps, on text it does nothing`() = runTest {
        val scope = scope()
        val s = state(scope)
        s.open()
        s.onQueryChange("n ")
        assertEquals(SearchModeIds.Note, s.state.value.mode)
        assertTrue(s.backspaceOnEmpty())
        assertEquals(SearchModeIds.Apps, s.state.value.mode)
        assertFalse(s.backspaceOnEmpty())
        s.setMode(SearchModeIds.Note)
        s.onQueryChange("x")
        assertFalse(s.backspaceOnEmpty())
        assertEquals(SearchModeIds.Note, s.state.value.mode)
        scope.cancel()
    }
}
