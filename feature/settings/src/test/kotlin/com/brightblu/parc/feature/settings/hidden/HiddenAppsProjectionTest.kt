package com.brightblu.parc.feature.settings.hidden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The projection rules: label sort, hidden flag, suspended flag, and the query filter (T17). */
class HiddenAppsProjectionTest {

    private val apple = hiddenEntry(hiddenId("apple"), "Apple")
    private val banana = hiddenEntry(hiddenId("banana"), "Banana")
    private val cherry = hiddenEntry(hiddenId("cherry"), "cherry", suspended = true)

    @Test
    fun `rows are sorted by label ignoring case, then by id`() {
        val rows = HiddenAppsProjection.rows(listOf(banana, cherry, apple), emptySet(), "")

        assertEquals(listOf("Apple", "Banana", "cherry"), rows.map { it.label })
    }

    @Test
    fun `equal labels break the tie by id so the order is deterministic`() {
        val first = hiddenEntry(hiddenId("a"), "Same")
        val second = hiddenEntry(hiddenId("b"), "Same")

        val rows = HiddenAppsProjection.rows(listOf(second, first), emptySet(), "")

        assertEquals(listOf("a", "b"), rows.map { it.appId.value.substringAfter("com.test.").substringBefore('/') })
    }

    @Test
    fun `the hidden flag follows the layout set`() {
        val rows = HiddenAppsProjection.rows(listOf(apple, banana), setOf(banana.id), "")

        assertEquals(listOf(false, true), rows.map { it.hidden })
    }

    @Test
    fun `the suspended flag is carried through`() {
        val rows = HiddenAppsProjection.rows(listOf(cherry), emptySet(), "")

        assertTrue(rows.single().suspended)
    }

    @Test
    fun `a blank query keeps every app`() {
        assertEquals(3, HiddenAppsProjection.rows(listOf(apple, banana, cherry), emptySet(), "  ").size)
    }

    @Test
    fun `the query filters labels case-insensitively and is trimmed`() {
        val all = listOf(apple, banana, cherry)

        assertEquals(listOf("Apple"), HiddenAppsProjection.rows(all, emptySet(), "APP").map { it.label })
        assertEquals(listOf("Banana"), HiddenAppsProjection.rows(all, emptySet(), " ban ").map { it.label })
        assertTrue(HiddenAppsProjection.rows(all, emptySet(), "zzz").isEmpty())
    }

    @Test
    fun `matches explains the rule used by rows`() {
        assertTrue(HiddenAppsProjection.matches("Telegram", "gram"))
        assertFalse(HiddenAppsProjection.matches("Telegram", "grams"))
    }
}
