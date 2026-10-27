package com.brightblu.parc.feature.settings.mainapps

import com.brightblu.parc.model.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picker rules (T62): suggestions, search, the four-app limit, reorder and skip. */
class MainAppsPickerTest {

    private val alpha = choice("Alpha", "com.alpha")
    private val bravo = choice("Bravo", "com.bravo")
    private val charlie = choice("charlie", "com.charlie")
    private val delta = choice("Delta", "com.delta")
    private val echo = choice("Echo", "com.echo")
    private val options = listOf(echo, delta, charlie, bravo, alpha)

    private fun choice(label: String, pkg: String) =
        MainAppChoice(AppId("$pkg/$pkg.Main@0"), label, pkg)

    private fun state() = MainAppsPicker.initial(options, emptyList(), listOf(alpha.id, bravo.id))

    @Test
    fun `a fresh picker starts on the suggestions`() {
        val state = state()

        assertTrue(state.loaded)
        assertEquals(listOf(alpha.id, bravo.id), state.selected)
        assertEquals(listOf("Alpha", "Bravo"), state.slots.filterNotNull().map { it.label })
        assertEquals(listOf("Alpha", "Bravo", "charlie", "Delta", "Echo"), state.visible.map { it.label })
    }

    @Test
    fun `a stored selection wins over the suggestions and unknown ids are dropped`() {
        val state = MainAppsPicker.initial(
            options,
            stored = listOf(charlie.id, AppId("com.gone/com.gone.Main@0")),
            suggestions = listOf(alpha.id, bravo.id),
        )

        assertEquals(listOf(charlie.id), state.selected)
    }

    @Test
    fun `an empty stored selection falls back to the suggestions`() {
        val state = MainAppsPicker.initial(options, stored = emptyList(), suggestions = listOf(delta.id))

        assertEquals(listOf(delta.id), state.selected)
    }

    @Test
    fun `tapping an app adds it once and tapping again removes it`() {
        val state = state()

        val added = MainAppsPicker.toggle(state, charlie.id)
        assertEquals(listOf(alpha.id, bravo.id, charlie.id), added.selected)
        assertTrue(added.isSelected(charlie.id))

        val removed = MainAppsPicker.toggle(added, alpha.id)
        assertEquals(listOf(bravo.id, charlie.id), removed.selected)
        assertFalse(removed.isSelected(alpha.id))
    }

    @Test
    fun `a fifth app cannot be added`() {
        val full = MainAppsPicker.toggle(MainAppsPicker.toggle(state(), charlie.id), delta.id)

        assertEquals(4, full.selected.size)
        assertTrue(full.isFull)
        assertEquals(full, MainAppsPicker.toggle(full, echo.id))
    }

    @Test
    fun `an unknown app is ignored`() {
        val state = state()

        assertEquals(state, MainAppsPicker.toggle(state, AppId("com.gone/com.gone.Main@0")))
    }

    @Test
    fun `search filters by label or package and ignores case`() {
        val state = state()

        assertEquals(listOf("charlie"), MainAppsPicker.withQuery(state, "CHARLIE").visible.map { it.label })
        assertEquals(listOf("Delta"), MainAppsPicker.withQuery(state, "com.del").visible.map { it.label })
        assertEquals(5, MainAppsPicker.withQuery(state, "  ").visible.size)
        assertTrue(MainAppsPicker.withQuery(state, "nothing").visible.isEmpty())
    }

    @Test
    fun `reorder moves a chosen app and clamps at both ends`() {
        val four = MainAppsPicker.toggle(MainAppsPicker.toggle(state(), charlie.id), delta.id)

        assertEquals(
            listOf(bravo.id, alpha.id, charlie.id, delta.id),
            MainAppsPicker.move(four, 1, 0).selected,
        )
        assertEquals(
            listOf(alpha.id, bravo.id, delta.id, charlie.id),
            MainAppsPicker.move(four, 2, 3).selected,
        )
        assertEquals(four, MainAppsPicker.move(four, 0, -5))
        assertEquals(four, MainAppsPicker.move(four, 9, 0))
        assertEquals(four, MainAppsPicker.move(four, 1, 1))
    }

    @Test
    fun `confirmed keeps the selection while skip falls back to the suggestions`() {
        val empty = state().copy(selected = emptyList())

        assertTrue(MainAppsPicker.confirmed(empty).isEmpty())
        assertEquals(listOf(alpha.id, bravo.id), MainAppsPicker.confirmedOrDefault(empty))
    }

    @Test
    fun `four slots always exist, empty after the chosen apps`() {
        val state = MainAppsPicker.initial(options, emptyList(), listOf(alpha.id))

        assertEquals(4, state.slots.size)
        assertEquals(alpha.id, state.slots[0]?.id)
        assertEquals(listOf(null, null, null), state.slots.drop(1))
    }
}
