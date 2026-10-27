package com.brightblu.parc.feature.settings.hidden

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller contract (T17): the layout is the source of truth for the hidden flag, a toggle is
 * a real repository write, the filter is UI-only, and an outside change refreshes the rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HiddenAppsControllerTest {

    private val apple = hiddenEntry(hiddenId("apple"), "Apple")
    private val banana = hiddenEntry(hiddenId("banana"), "Banana")
    private val cherry = hiddenEntry(hiddenId("cherry"), "Cherry")
    private val apps = listOf(apple, banana, cherry)

    private class Harness(scope: TestScope, apps: List<com.brightblu.parc.model.AppEntry>) {
        val repository = FakeLayoutRepository(ParcLayout(version = 1))
        val appsFlow = MutableStateFlow(apps)
        val controller = HiddenAppsController(repository, appsFlow, scope.backgroundScope)
    }

    private fun TestScope.harness(): Harness = Harness(this, apps)

    @Test
    fun `a fresh controller lists every app with the hidden flags from the layout`() = runTest {
        val harness = harness()
        harness.repository.hide(banana.id)
        runCurrent()

        val state = harness.controller.state.value
        assertEquals(listOf("Apple", "Banana", "Cherry"), state.rows.map { it.label })
        assertEquals(listOf(false, true, false), state.rows.map { it.hidden })
        assertEquals(1, state.hiddenCount)
    }

    @Test
    fun `hiding writes through the repository and the rows follow`() = runTest {
        val harness = harness()
        runCurrent()

        harness.controller.setHidden(apple.id, true)
        runCurrent()

        assertTrue(apple.id in harness.repository.layout.value.hidden)
        assertTrue(harness.controller.state.value.rows.first { it.appId == apple.id }.hidden)
        assertEquals(1, harness.controller.state.value.hiddenCount)
    }

    @Test
    fun `unhiding clears the flag`() = runTest {
        val harness = harness()
        harness.repository.hide(cherry.id)
        runCurrent()

        harness.controller.setHidden(cherry.id, false)
        runCurrent()

        assertFalse(cherry.id in harness.repository.layout.value.hidden)
        assertEquals(0, harness.controller.state.value.hiddenCount)
    }

    @Test
    fun `the query filters the rows but not the count of hidden apps`() = runTest {
        val harness = harness()
        harness.repository.hide(banana.id)
        runCurrent()

        harness.controller.setQuery("App")
        runCurrent()

        assertEquals(listOf("Apple"), harness.controller.state.value.rows.map { it.label })
        // Banana is hidden but filtered out of the list; the count still reports what is hidden.
        assertEquals(1, harness.controller.state.value.hiddenCount)
    }

    @Test
    fun `an outside layout change is reflected without a tap`() = runTest {
        val harness = harness()
        runCurrent()

        harness.repository.hide(cherry.id)
        runCurrent()

        assertTrue(harness.controller.state.value.rows.first { it.appId == cherry.id }.hidden)
    }

    @Test
    fun `a new installed app appears in the list`() = runTest {
        val harness = harness()
        runCurrent()

        harness.appsFlow.value = apps + hiddenEntry(hiddenId("delta"), "Delta")
        runCurrent()

        assertEquals(listOf("Apple", "Banana", "Cherry", "Delta"), harness.controller.state.value.rows.map { it.label })
    }

    @Test
    fun `hiding an unknown app id is still stored, which matches the contract`() = runTest {
        val harness = harness()
        runCurrent()
        val unknown = AppId("com.test.ghost/com.test.ghost.Main@0")

        harness.controller.setHidden(unknown, true)
        runCurrent()

        assertTrue(unknown in harness.repository.layout.value.hidden)
    }
}
