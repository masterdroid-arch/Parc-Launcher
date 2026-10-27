package com.brightblu.parc.core.data.search

import com.brightblu.parc.core.data.FakePreferencesStore
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPrefsTest {

    private suspend fun SearchPrefs.snapshot(): SearchPrefsSnapshot = snapshot.first()

    @Test
    fun `a fresh install has no recents, recents on and Google`() = runTest {
        val prefs = SearchPrefs(FakePreferencesStore())

        assertEquals(SearchPrefsSnapshot(), prefs.snapshot())
        assertTrue(prefs.recentsEnabled.first())
        assertEquals(WebSearchEngine.Google, prefs.webEngine.first())
    }

    @Test
    fun `a recorded search becomes the first entry`() = runTest {
        val prefs = SearchPrefs(FakePreferencesStore())

        prefs.recordSearch("maps")
        prefs.recordSearch("music")

        assertEquals(listOf("music", "maps"), prefs.snapshot().recentSearches)
    }

    @Test
    fun `recording the same query again moves it to the front`() = runTest {
        val prefs = SearchPrefs(FakePreferencesStore())

        prefs.recordSearch("maps")
        prefs.recordSearch("music")
        prefs.recordSearch("MAPS")

        assertEquals(listOf("MAPS", "music"), prefs.snapshot().recentSearches)
    }

    @Test
    fun `recents keep at most eight entries`() = runTest {
        val prefs = SearchPrefs(FakePreferencesStore())

        (1..10).forEach { prefs.recordSearch("query $it") }

        val stored = prefs.snapshot().recentSearches
        assertEquals(8, stored.size)
        assertEquals(listOf("query 10", "query 9", "query 8"), stored.take(3))
    }

    @Test
    fun `a blank query is not recorded`() = runTest {
        val prefs = SearchPrefs(FakePreferencesStore())

        prefs.recordSearch("maps")
        prefs.recordSearch("   ")

        assertEquals(listOf("maps"), prefs.snapshot().recentSearches)
    }

    @Test
    fun `recording is a no-op while recents are switched off`() = runTest {
        val prefs = SearchPrefs(FakePreferencesStore())
        prefs.recordSearch("maps")
        prefs.setRecentsEnabled(false)

        prefs.recordSearch("music")

        assertEquals(listOf("maps"), prefs.snapshot().recentSearches)
        assertFalse(prefs.snapshot().recentsEnabled)
    }

    @Test
    fun `switching recents back on keeps the earlier list`() = runTest {
        val prefs = SearchPrefs(FakePreferencesStore())
        prefs.recordSearch("maps")
        prefs.setRecentsEnabled(false)
        prefs.setRecentsEnabled(true)

        assertEquals(listOf("maps"), prefs.snapshot().recentSearches)
        assertTrue(prefs.snapshot().recentsEnabled)
    }

    @Test
    fun `clearing removes every recorded search`() = runTest {
        val prefs = SearchPrefs(FakePreferencesStore())
        prefs.recordSearch("maps")

        prefs.clearRecentSearches()

        assertTrue(prefs.snapshot().recentSearches.isEmpty())
    }

    @Test
    fun `the engine choice round trips and survives a reload`() = runTest {
        val store = FakePreferencesStore()
        val prefs = SearchPrefs(store)

        prefs.setWebEngine(WebSearchEngine.Brave)

        assertEquals(WebSearchEngine.Brave, prefs.webEngine.first())
        assertEquals(WebSearchEngine.Brave, SearchPrefs(store).webEngine.first())
    }

    @Test
    fun `an unknown stored engine falls back to google`() = runTest {
        val prefs = SearchPrefs(
            FakePreferencesStore(preferencesOf(stringPreferencesKey("web_engine") to "gone")))

        assertEquals(WebSearchEngine.Google, prefs.webEngine.first())
    }

    @Test
    fun `stored order and de-duplication survive a reload`() = runTest {
        val store = FakePreferencesStore()
        val prefs = SearchPrefs(store)
        prefs.recordSearch("one")
        prefs.recordSearch("two")
        prefs.recordSearch("one")

        assertEquals(listOf("one", "two"), SearchPrefs(store).snapshot().recentSearches)
    }

    @Test
    fun `a stored newline cannot create a phantom entry`() = runTest {
        val prefs = SearchPrefs(FakePreferencesStore())
        prefs.recordSearch("line one\nline two")

        assertEquals(listOf("line one line two"), prefs.snapshot().recentSearches)
    }

    @Test
    fun `the three sources are on by default and the prompt marker is unset`() = runTest {
        val snap = SearchPrefs(FakePreferencesStore()).snapshot()

        assertTrue(snap.phoneSettingsEnabled)
        assertTrue(snap.filesEnabled)
        assertTrue(snap.eventsEnabled)
        assertFalse(snap.extrasPromptDone)
    }

    @Test
    fun `each source switch changes only itself and survives a reload`() = runTest {
        val store = FakePreferencesStore()
        val prefs = SearchPrefs(store)

        prefs.setFilesEnabled(false)
        var snap = SearchPrefs(store).snapshot()
        assertFalse(snap.filesEnabled)
        assertTrue(snap.phoneSettingsEnabled)
        assertTrue(snap.eventsEnabled)

        prefs.setPhoneSettingsEnabled(false)
        prefs.setEventsEnabled(false)
        prefs.setFilesEnabled(true)
        snap = SearchPrefs(store).snapshot()
        assertFalse(snap.phoneSettingsEnabled)
        assertTrue(snap.filesEnabled)
        assertFalse(snap.eventsEnabled)
    }

    @Test
    fun `the one-time prompt marker is stored and the source switches do not touch recents`() = runTest {
        val prefs = SearchPrefs(FakePreferencesStore())
        prefs.recordSearch("maps")

        prefs.markExtrasPromptDone()
        prefs.setEventsEnabled(false)

        val snap = prefs.snapshot()
        assertTrue(snap.extrasPromptDone)
        assertEquals(listOf("maps"), snap.recentSearches)
    }

    @Test
    fun `the source switches round trip through the backup spec and the prompt marker stays out`() = runTest {
        val source = FakePreferencesStore()
        val prefs = SearchPrefs(source)
        prefs.setPhoneSettingsEnabled(false)
        prefs.setEventsEnabled(false)
        prefs.markExtrasPromptDone()
        prefs.recordSearch("private query")

        val saved = com.brightblu.parc.core.data.backup.PreferencesStoreAccess(source).read(com.brightblu.parc.core.data.backup.BackupSpecs.search)
        assertEquals(false, saved["source_settings"])
        assertEquals(false, saved["source_events"])
        assertFalse(saved.containsKey("extras_prompt_done"))
        assertFalse(saved.containsKey("recent_searches"))

        val target = FakePreferencesStore()
        com.brightblu.parc.core.data.backup.PreferencesStoreAccess(target).write(com.brightblu.parc.core.data.backup.BackupSpecs.search, saved)
        val restored = SearchPrefs(target).snapshot()
        assertFalse(restored.phoneSettingsEnabled)
        assertTrue(restored.filesEnabled)
        assertFalse(restored.eventsEnabled)
        assertFalse(restored.extrasPromptDone)
    }

    @Test
    fun `a wrongly typed stored switch is refused by the backup`() = runTest {
        val target = FakePreferencesStore()
        com.brightblu.parc.core.data.backup.PreferencesStoreAccess(target).write(com.brightblu.parc.core.data.backup.BackupSpecs.search, mapOf("source_files" to "yes"))

        assertTrue(SearchPrefs(target).snapshot().filesEnabled)
    }
}
