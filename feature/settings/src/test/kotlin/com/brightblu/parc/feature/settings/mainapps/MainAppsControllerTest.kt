package com.brightblu.parc.feature.settings.mainapps

import com.brightblu.parc.core.data.firstrun.AppKind
import com.brightblu.parc.core.data.firstrun.InstalledApp
import com.brightblu.parc.core.data.firstrun.InstalledAppsSource
import com.brightblu.parc.model.AppId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picker controller (T62): platform read off main, selection rules, save on Next/Skip. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MainAppsControllerTest {

    private val alpha = app("Alpha", "com.alpha")
    private val bravo = app("Bravo", "com.bravo")
    private val charlie = app("charlie", "com.charlie")

    private fun app(label: String, pkg: String) =
        InstalledApp(AppId("$pkg/$pkg.Main@0"), label, pkg, AppKind.Uncategorised)

    private class FakeSource(
        private val apps: List<InstalledApp>,
        private val failure: Throwable? = null,
    ) : InstalledAppsSource {
        override fun read(): List<InstalledApp> = failure?.let { throw it } ?: apps

        override fun kindOf(packageName: String): AppKind = AppKind.Uncategorised
    }

    private class FakeStore(
        private val initial: List<AppId> = emptyList(),
        private val failure: Throwable? = null,
    ) : MainAppsStore {
        var saved: List<AppId>? = null

        override suspend fun selected(): List<AppId> = saved ?: initial

        override suspend fun save(ids: List<AppId>) {
            failure?.let { throw it }
            saved = ids
        }
    }

    private fun controller(
        source: InstalledAppsSource,
        store: MainAppsStore,
        scope: kotlinx.coroutines.CoroutineScope,
        suggestions: (List<InstalledApp>) -> List<AppId> = { listOf(alpha.id, bravo.id) },
    ) = MainAppsController(
        source = source,
        suggestions = suggestions,
        store = store,
        scope = scope,
        io = Dispatchers.Unconfined,
    )

    @Test fun `settings preserves an explicitly empty selection after save and reopen`() = runTest {
        val store = FakeStore()
        fun open() = MainAppsController(FakeSource(listOf(alpha, bravo)), { listOf(alpha.id) },
            store, this, Dispatchers.Unconfined, useSuggestions = false)
        val editor = open()
        advanceUntilIdle()
        assertTrue(editor.state.value.selected.isEmpty())
        editor.toggle(alpha.id)
        editor.saveSelection(editor.state.value.selected)
        assertEquals(listOf(alpha.id), store.saved)
        assertEquals(listOf(alpha.id), open().also { advanceUntilIdle() }.state.value.selected)
        editor.toggle(alpha.id)
        editor.saveSelection(editor.state.value.selected)
        assertEquals(emptyList<AppId>(), store.saved)
        assertTrue(open().also { advanceUntilIdle() }.state.value.selected.isEmpty())
    }

    @Test
    fun `the picker loads installed apps alphabetically and preselects the suggestions`() = runTest {
        val controller = controller(FakeSource(listOf(charlie, alpha, bravo)), FakeStore(), this)
        advanceUntilIdle()

        val state = controller.state.value
        assertTrue(state.loaded)
        assertEquals(listOf("Alpha", "Bravo", "charlie"), state.visible.map { it.label })
        assertEquals(listOf(alpha.id, bravo.id), state.selected)
    }

    @Test
    fun `a stored selection replaces the suggestions`() = runTest {
        val controller = controller(
            FakeSource(listOf(alpha, bravo, charlie)),
            FakeStore(initial = listOf(charlie.id)),
            this,
        )
        advanceUntilIdle()

        assertEquals(listOf(charlie.id), controller.state.value.selected)
    }

    @Test
    fun `Next saves the current selection and then advances`() = runTest {
        val store = FakeStore()
        val controller = controller(FakeSource(listOf(alpha, bravo, charlie)), store, this)
        advanceUntilIdle()
        controller.toggle(charlie.id)

        var advanced = false
        controller.save { advanced = true }
        advanceUntilIdle()

        assertTrue(advanced)
        assertEquals(listOf(alpha.id, bravo.id, charlie.id), store.saved)
    }

    @Test
    fun `Skip with nothing chosen saves the suggestions`() = runTest {
        val store = FakeStore(initial = emptyList())
        val controller = controller(
            FakeSource(listOf(alpha, bravo)),
            store,
            this,
            suggestions = { listOf(bravo.id) },
        )
        advanceUntilIdle()
        controller.toggle(bravo.id)

        controller.save { }
        advanceUntilIdle()

        assertEquals(listOf(bravo.id), store.saved)
    }

    @Test
    fun `a read failure leaves an empty picker and never blocks the flow`() = runTest {
        val store = FakeStore()
        val controller = controller(FakeSource(emptyList(), IllegalStateException("no launcher")), store, this)
        advanceUntilIdle()

        assertTrue(controller.state.value.loaded)
        assertTrue(controller.state.value.visible.isEmpty())

        var advanced = false
        controller.save { advanced = true }
        advanceUntilIdle()

        assertTrue(advanced)
        assertTrue(store.saved!!.isEmpty())
    }

    @Test
    fun `a write failure still advances the flow`() = runTest {
        val controller = controller(
            FakeSource(listOf(alpha, bravo)),
            FakeStore(failure = IllegalStateException("disk full")),
            this,
        )
        advanceUntilIdle()

        var advanced = false
        controller.save { advanced = true }
        advanceUntilIdle()

        assertTrue(advanced)
        assertTrue(controller.state.value.loaded)
    }
}
