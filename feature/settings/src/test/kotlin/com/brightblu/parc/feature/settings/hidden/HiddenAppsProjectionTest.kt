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

    @Test
    fun `the hidden and all apps sections split without duplicating a row`() {
        val all = listOf(apple, banana, cherry)

        val hidden = HiddenAppsProjection.hiddenRows(all, setOf(banana.id), "")
        val visible = HiddenAppsProjection.visibleRows(all, setOf(banana.id), "")

        assertEquals(listOf("Banana"), hidden.map { it.label })
        assertEquals(listOf("Apple", "cherry"), visible.map { it.label })
        assertTrue(hidden.map { it.appId }.intersect(visible.map { it.appId }.toSet()).isEmpty())
    }

    @Test
    fun `search filters both sections and a blank query keeps everything`() {
        val all = listOf(apple, banana, cherry)
        val hidden = setOf(banana.id)

        assertEquals(listOf("Banana"), HiddenAppsProjection.hiddenRows(all, hidden, "ban").map { it.label })
        assertEquals(listOf("Apple"), HiddenAppsProjection.visibleRows(all, hidden, "app").map { it.label })
        assertTrue(HiddenAppsProjection.visibleRows(all, hidden, "ban").isEmpty())
        assertEquals(3, HiddenAppsProjection.hiddenRows(all, hidden, " ").size + HiddenAppsProjection.visibleRows(all, hidden, " ").size)
    }

    @Test
    fun `hidden count is permanent hidden only and does not depend on the query`() {
        val harness = HiddenAppsUiState(
            rows = HiddenAppsProjection.rows(listOf(apple, banana), setOf(banana.id), "apple"),
            query = "apple",
            hiddenCount = 1,
            hiddenRows = HiddenAppsProjection.hiddenRows(listOf(apple, banana), setOf(banana.id), "apple"),
            visibleRows = HiddenAppsProjection.visibleRows(listOf(apple, banana), setOf(banana.id), "apple"),
        )

        assertEquals(1, harness.hiddenCount)
        assertTrue(harness.hiddenRows.isEmpty())
        assertEquals(listOf("Apple"), harness.visibleRows.map { it.label })
    }

    @Test
    fun `the visibility copy names all three concepts and the shade limit`() {
        listOf("Hide", "Focus", "Lock").forEach { assertTrue(VisibilityCopy.Scope, it in VisibilityCopy.Scope || it in VisibilityCopy.Hide + VisibilityCopy.Focus + VisibilityCopy.Lock) }
        assertTrue(VisibilityCopy.Hide.startsWith("Hide:"))
        assertTrue(VisibilityCopy.Focus.startsWith("Focus:"))
        assertTrue(VisibilityCopy.Lock.startsWith("Lock:"))
        assertTrue(VisibilityCopy.Shade.contains("Android notification shade and other apps"))
        assertTrue(VisibilityCopy.Scope.contains("arcs, search, All apps, folders, Messages and Media"))
        assertTrue(VisibilityCopy.Scope.contains("Focus and Lock are temporary"))
    }

    @Test
    fun `hidden folder summary is folder only and has no app count`() {
        assertEquals("None", HiddenFoldersText.summary(0))
        assertEquals("1 folder", HiddenFoldersText.summary(1))
        assertEquals("3 folders", HiddenFoldersText.summary(3))
        assertEquals("Hidden · 1 folder", HiddenFoldersText.sectionTitle(1))
        assertEquals("Hidden · 3 folders", HiddenFoldersText.sectionTitle(3))
    }
}
