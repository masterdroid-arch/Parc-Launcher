package com.brightblu.parc

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.brightblu.parc.core.data.backup.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class QuotesStoreTest {
    private val starters = listOf(Quote("starter-a", "First"), Quote("starter-b", "Second"), Quote("starter-c", "Third"))
    private class Memory : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        override val data = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }
    @Test fun oldAndEmptyDocumentsDecodeWithDefaults() {
        assertEquals(QuoteLibrary(), QuotesJson.decode())
        assertEquals(QuoteLibrary(), QuotesJson.decode("[]", "[]", "{}"))
        val old = QuotesJson.quotes("""[{"id":"own-a","text":"Text","future":true}]""").single()
        assertEquals(Quote("own-a", "Text"), old)
    }
    @Test fun unicodeNewlinesCreditsVisibilityAndOrderRoundTrip() {
        val own = listOf(Quote("own-a", "“Kindness”\n\"Light\"", "Author", "Work", "Translator"))
        val order = QuoteRules.pick(QuoteOrder(), listOf("starter-a", "own-a"), LocalDate.of(2026, 11, 10))
        val library = QuoteLibrary(own, setOf("starter-b"), order)
        assertEquals(library, QuotesJson.decode(QuotesJson.encodeQuotes(own), QuotesJson.encodeIds(library.hidden), QuotesJson.encodeOrder(order)))
    }
    @Test fun malformedAndOverlengthDocumentsFailWithoutSilentlyDiscardingContent() {
        for (raw in listOf("bad", "{}", """[{"id":"own-a"}]""", QuotesJson.encodeQuotes(listOf(Quote("own-a", "x".repeat(2001)))))) {
            assertTrue(runCatching { QuotesJson.quotes(raw) }.isFailure)
        }
        assertTrue(runCatching { QuotesJson.decode(QuotesJson.encodeQuotes(starters)) }.isFailure)
    }
    @Test fun restartAndMidnightKeepThePersistedCycle() = runTest {
        val data = Memory()
        var date = LocalDate.of(2026, 11, 10)
        fun store() = QuotesStore(data, { starters }, { date })
        assertEquals("starter-a", store().quoteForToday()!!.id)
        assertEquals("starter-a", store().quoteForToday()!!.id)
        date = date.plusDays(1)
        assertEquals("starter-b", store().quoteForToday()!!.id)
        assertEquals("starter-c", store().another()!!.id)
        assertEquals("starter-c", store().quoteForToday()!!.id)
        date = date.plusDays(1)
        assertEquals("starter-a", store().quoteForToday()!!.id)
    }
    @Test fun saveDeleteHideRestoreAndUnhideArePersisted() = runTest {
        val store = QuotesStore(Memory(), { starters })
        val own = Quote("own-a", "My words")
        store.save(own, "starter-a")
        assertTrue("starter-a" in store.library.first().hidden)
        assertEquals(listOf(own), store.library.first().own)
        store.hide(own.id)
        assertFalse(own in store.library.first().visible(starters))
        store.restoreStarters()
        assertTrue(own.id in store.library.first().hidden)
        assertFalse("starter-a" in store.library.first().hidden)
        store.show(own.id)
        assertTrue(own in store.library.first().visible(starters))
        store.delete(own.id)
        assertTrue(store.library.first().own.isEmpty())
        starters.forEach { store.hide(it.id) }
        assertNull(store.quoteForToday())
        store.restoreStarters()
        assertNotNull(store.quoteForToday())
    }
    @Test fun shareDraftDoesNotWriteUntilSaveAndARejectedSaveKeepsTheCollection() = runTest {
        val data = Memory()
        val store = QuotesStore(data, { starters })
        val draft = QuoteDraft(Quote("own-shared", QuoteRules.sharedText("android.intent.action.SEND", "text/plain", "Shared")!!))
        assertTrue(data.state.value.asMap().isEmpty())
        store.save(draft.quote)
        assertEquals(listOf(draft.quote), store.library.first().own)
        val before = data.state.value
        assertTrue(runCatching { store.save(draft.quote.copy(text = "x".repeat(2001))) }.isFailure)
        assertEquals(before, data.state.value)
    }
    @Test fun backupRoundTripKeepsPersonalQuotesHiddenStartersAndTheSameDailyPick() = runTest {
        val data = Memory()
        val store = QuotesStore(data, { starters })
        store.save(Quote("own-a", "Saved text"))
        store.hide("starter-a")
        val picked = store.another()
        val saved = QuotesBackupAccess(PreferencesStoreAccess(data)).read(BackupSpecs.quotes)
        val restored = Memory()
        val access = QuotesBackupAccess(PreferencesStoreAccess(restored))
        access.write(BackupSpecs.quotes, saved)
        val next = QuotesStore(restored, { starters })
        assertEquals(store.library.first(), next.library.first())
        assertEquals(picked, next.quoteForToday())
        val before = restored.state.value
        assertTrue(runCatching { access.write(BackupSpecs.quotes, mapOf(QuotesJson.OwnKey to "corrupt")) }.isFailure)
        assertEquals(before, restored.state.value)
        access.write(BackupSpecs.quotes, emptyMap()) // old backup: no new keys, no crash
    }
    @Test fun storageErrorsPropagateSoTheEditorCannotClaimASuccessfulSave() = runTest {
        val broken = object : DataStore<Preferences> {
            override val data = MutableStateFlow(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw java.io.IOException("test")
        }
        assertTrue(runCatching { QuotesStore(broken, { starters }).save(Quote("own-a", "Text")) }.isFailure)
    }
}
