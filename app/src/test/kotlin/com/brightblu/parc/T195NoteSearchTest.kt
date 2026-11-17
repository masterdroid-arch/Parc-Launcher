package com.brightblu.parc

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class T195NoteSearchTest {
    private class MemoryNotes : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        override val data = state
        override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test fun cleanedDropsOuterBlankSpaceAndRejectsEmpty() {
        assertEquals("a\nb", NoteSearchRules.cleaned("\n a\nb \n"))
        assertNull(NoteSearchRules.cleaned("  \n "))
    }

    @Test fun aNewNoteIsUnpinnedAtTheCurrentMarksAndNewestFirst() {
        val n = NoteSearchRules.newNote("x", "  hello ", 500L)!!
        assertEquals(Note("x", "hello", pinned = false, updatedAt = 500L, marks = NoteMarks.Current), n)
        assertNull(NoteSearchRules.newNote("y", "  ", 1L))
        val older = Note("o", "old", updatedAt = 100L)
        assertEquals(listOf("x", "o"), orderedNotes(listOf(older, n)).map { it.id })
    }

    @Test fun lastEditedIsTheNewestAndTiesAreStable() {
        val notes = listOf(Note("b", "1", updatedAt = 5), Note("a", "2", updatedAt = 5), Note("c", "3", pinned = true, updatedAt = 1))
        assertEquals("a", NoteSearchRules.lastEdited(notes)?.id)
        assertNull(NoteSearchRules.lastEdited(emptyList()))
    }

    @Test fun appendingAddsALineAndHandlesEmptyAndTrailingNewlines() {
        assertEquals("one\ntwo", NoteSearchRules.appended("one", "two"))
        assertEquals("one\ntwo", NoteSearchRules.appended("one\n\n", "  two\n"))
        assertEquals("two", NoteSearchRules.appended("", "two"))
    }

    @Test fun savingReportsSuccessAndLandsInTheHubStore() = runTest {
        val store = NotesStore(MemoryNotes(), backgroundScope) { testScheduler.currentTime }
        var ok: Boolean? = null
        store.saveReporting(NoteSearchRules.newNote("n1", "# Milk\n- eggs", 10L)!!) { ok = it }
        runCurrent()
        assertEquals(true, ok)
        assertEquals("# Milk\n- eggs", store.notes.first().single().text)
    }

    @Test fun addToLastNoteAppendsToTheMostRecentlyEditedOne() = runTest {
        val store = NotesStore(MemoryNotes(), backgroundScope) { testScheduler.currentTime }
        store.save(Note("old", "old", updatedAt = 1)); store.save(Note("new", "new one", updatedAt = 9)); runCurrent()
        var result: Triple<Note?, Note?, Boolean>? = null
        store.appendToLastEdited("also this", 20L) { b, a, f -> result = Triple(b, a, f) }
        runCurrent()
        assertEquals("new one", result!!.first!!.text)
        val stored = store.notes.first().associateBy { it.id }
        assertEquals("new one\nalso this", stored.getValue("new").text)
        assertEquals(20L, stored.getValue("new").updatedAt)
        assertEquals("old", stored.getValue("old").text)
    }

    @Test fun addToLastNoteWithNoNotesSaysSoWithoutFailing() = runTest {
        val store = NotesStore(MemoryNotes(), backgroundScope) { testScheduler.currentTime }
        var result: Triple<Note?, Note?, Boolean>? = null
        store.appendToLastEdited("x", 1L) { b, a, f -> result = Triple(b, a, f) }
        runCurrent()
        assertEquals(Triple<Note?, Note?, Boolean>(null, null, false), result)
        assertTrue(store.notes.first().isEmpty())
    }

    @Test fun undoRemovesANewNoteAndRestoresAnAppendedOne() = runTest {
        val store = NotesStore(MemoryNotes(), backgroundScope) { testScheduler.currentTime }
        store.save(Note("a", "keep", updatedAt = 5)); runCurrent()
        val added = NoteSearchRules.newNote("n", "fresh", 6L)!!
        store.saveReporting(added) {}; runCurrent()
        assertEquals(2, store.notes.first().size)
        store.remove("n"); runCurrent()
        assertEquals(listOf("a"), store.notes.first().map { it.id })
        var before: Note? = null
        store.appendToLastEdited("more", 7L) { b, _, _ -> before = b }; runCurrent()
        assertEquals("keep\nmore", store.notes.first().single().text)
        store.save(before!!); runCurrent()
        assertEquals("keep", store.notes.first().single().text)
        assertEquals(5L, store.notes.first().single().updatedAt)
    }

    @Test fun overTheSizeLimitReportsFailureAndKeepsTheStore() = runTest {
        val store = NotesStore(MemoryNotes(), backgroundScope) { testScheduler.currentTime }
        store.save(Note("a", "small")); runCurrent()
        var ok: Boolean? = null
        store.saveReporting(Note("big", "x".repeat(com.brightblu.parc.model.Metrics.notesMaxChars + 10), updatedAt = 3)) { ok = it }
        runCurrent()
        assertEquals(false, ok)
        assertTrue(store.overLimit.value)
        assertEquals(listOf("a"), store.notes.first().map { it.id })
    }
}
