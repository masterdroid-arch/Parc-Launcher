package com.brightblu.parc.core.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T97: selection, filtering and privacy rules for notifications in the menus. */
class NotificationPeekTest {
    private fun entry(
        key: String,
        pkg: String = "p.one",
        title: String = "T$key",
        text: String = "body $key",
        time: Long = 0,
        visibility: PeekVisibility = PeekVisibility.Public,
        ongoing: Boolean = false,
        summary: Boolean = false,
    ) = PeekEntry(key, pkg, title, text, time, visibility, ongoing, summary)

    @Test fun ongoingSummarySecretAndEmptyNotificationsAreSkipped() {
        assertTrue(PeekRules.accepts(entry("a")))
        assertFalse(PeekRules.accepts(entry("a", ongoing = true)))
        assertFalse(PeekRules.accepts(entry("a", summary = true)))
        assertFalse(PeekRules.accepts(entry("a", visibility = PeekVisibility.Secret)))
        assertFalse(PeekRules.accepts(entry("a", title = " ", text = "")))
        assertTrue(PeekRules.accepts(entry("a", title = "", text = "only text")))
    }

    @Test fun anAppShowsAtMostThreeNewestFirstAndOnlyItsOwn() {
        val list = listOf(
            entry("1", time = 10), entry("2", time = 40), entry("3", time = 30), entry("4", time = 20),
            entry("x", pkg = "p.two", time = 99), entry("o", time = 50, ongoing = true),
        )
        assertEquals(listOf("2", "3", "4"), PeekRules.forApp(list, "p.one").map { it.key })
        assertEquals(listOf("x"), PeekRules.forApp(list, "p.two").map { it.key })
        assertEquals(emptyList<PeekEntry>(), PeekRules.forApp(list, "p.none"))
    }

    @Test fun equalTimesOrderByKeyForAStableMenu() {
        val list = listOf(entry("b", time = 5), entry("a", time = 5))
        assertEquals(listOf("a", "b"), PeekRules.forApp(list, "p.one").map { it.key })
    }

    @Test fun privateVisibilityStillShowsTextBecauseTheLauncherIsOnlyShownUnlocked() {
        val line = PeekRules.line(entry("1", visibility = PeekVisibility.Private), showText = true)
        assertEquals("T1", line.title)
        assertEquals("body 1", line.text)
    }

    @Test fun secretVisibilityNeverShowsText() {
        val line = PeekRules.line(entry("1", visibility = PeekVisibility.Secret), showText = true)
        assertEquals("", line.text)
    }

    @Test fun turningMessageTextOffShowsOnlyTheTitleEvenWhenPublic() {
        val line = PeekRules.line(entry("1"), showText = false)
        assertEquals("", line.text)
        assertEquals("body 1", PeekRules.line(entry("1"), showText = true).text)
    }

    @Test fun aBlankTitleNeverLeaksSecretTextAndFallsBackToAGenericTitle() {
        val hidden = PeekRules.line(entry("1", title = "", visibility = PeekVisibility.Secret), showText = true)
        assertEquals(PeekRules.FallbackTitle, hidden.title)
        assertEquals("", hidden.text)
        val shown = PeekRules.line(entry("1", title = "", text = "hello"), showText = true)
        assertEquals("hello", shown.title)
        assertEquals("", shown.text)
    }

    @Test fun countsSkipRejectedNotifications() {
        val list = listOf(entry("1"), entry("2"), entry("3", pkg = "p.two"), entry("4", ongoing = true))
        assertEquals(mapOf("p.one" to 2, "p.two" to 1), PeekRules.counts(list))
    }

    @Test fun oneLineKeepsTheFirstLineAndCutsLongText() {
        assertEquals("first", PeekRules.oneLine("\n  first \nsecond"))
        assertEquals("", PeekRules.oneLine(null))
        val cut = PeekRules.oneLine("x".repeat(500))
        assertEquals(PeekRules.MaxLineChars, cut.length)
        assertTrue(cut.endsWith("…"))
    }

    @Test fun platformVisibilityMapping() {
        assertEquals(PeekVisibility.Public, PeekVisibility.fromPlatform(1))
        assertEquals(PeekVisibility.Private, PeekVisibility.fromPlatform(0))
        assertEquals(PeekVisibility.Secret, PeekVisibility.fromPlatform(-1))
        assertEquals(PeekVisibility.Private, PeekVisibility.fromPlatform(-1000))
    }

    @Test fun theStoreKeepsNothingUntilCollectingAndForgetsWhenTurnedOff() {
        val store = NotificationPeekStore()
        store.post(entry("1"))
        assertTrue(store.snapshot().isEmpty())
        store.collecting = true
        store.post(entry("1"))
        store.post(entry("2", ongoing = true))
        assertEquals(listOf("1"), store.snapshot().map { it.key })
        store.collecting = false
        assertTrue(store.snapshot().isEmpty())
    }

    @Test fun repostingReplacesAndRemovalIsIdempotent() {
        val store = NotificationPeekStore().apply { collecting = true }
        store.post(entry("1", title = "old"))
        store.post(entry("1", title = "new"))
        assertEquals(listOf("new"), store.snapshot().map { it.title })
        // A repost that becomes ongoing leaves the menu.
        store.post(entry("1", ongoing = true))
        assertTrue(store.snapshot().isEmpty())
        store.remove("1")
        store.remove("1")
    }

    @Test fun replaceAllRebuildsOnlyFromAcceptedEntriesAndIsBounded() {
        val store = NotificationPeekStore().apply { collecting = true }
        store.post(entry("stale"))
        store.replaceAll(listOf(entry("a"), entry("b", ongoing = true)))
        assertEquals(listOf("a"), store.snapshot().map { it.key })
        store.replaceAll((0 until NotificationPeekStore.MaxEntries + 20).map { entry("k$it", time = it.toLong()) })
        assertEquals(NotificationPeekStore.MaxEntries, store.snapshot().size)
    }
}
