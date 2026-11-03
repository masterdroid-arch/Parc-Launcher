package com.brightblu.parc

import com.brightblu.parc.core.data.backup.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class T113Test {
    @Test fun checklistParsingCoversPlainEmptyUnicodeAndPortableChecks() {
        val cases = listOf(
            "" to NoteLine(""), "hello" to NoteLine("hello"), "-" to NoteLine("-"),
            "- " to NoteLine("", false), "- milk" to NoteLine("milk", false),
            "- [ ] milk" to NoteLine("milk", false), "- [x] milk" to NoteLine("milk", true),
            "- [X] milk" to NoteLine("milk", true), "- [x] " to NoteLine("", true),
            " - milk" to NoteLine(" - milk"), "* milk" to NoteLine("* milk"),
            "- [x]milk" to NoteLine("[x]milk", false), "- [y] milk" to NoteLine("[y] milk", false),
            "- café 漢字" to NoteLine("café 漢字", false), "- - nested" to NoteLine("- nested", false),
            "- milk\r" to NoteLine("milk", false), "  " to NoteLine("  "), "a-b" to NoteLine("a-b"))
        for ((raw, expected) in cases) assertEquals(raw, listOf(expected), noteLines(raw))
        assertEquals(listOf(NoteLine("title"), NoteLine("milk", false), NoteLine("")), noteLines("title\n- milk\n"))
    }
    @Test fun checklistTogglePreservesOtherLinesAndHasNoEffectOnPlainText() {
        val original = "Title\n- milk\n- [x] bread\n\nlast"
        val checked = toggleNoteLine(original, 1)
        assertEquals("Title\n- [x] milk\n- [x] bread\n\nlast", checked)
        assertEquals(original, toggleNoteLine(checked, 1))
        assertEquals(original, toggleNoteLine(original, 0))
        assertEquals(original, toggleNoteLine(original, 99))
        assertEquals("Title\n- milk\n- bread\n\nlast", toggleNoteLine(original, 2))
    }
    @Test fun modelRoundTripRetainsIdentityTextPinTimeAndOrder() {
        val notes = listOf(Note("one", "Quotes \" / \\ \n- [x] café 漢字", true, Long.MAX_VALUE), Note("two", "", false, 0))
        assertEquals(notes, NotesJson.decode(NotesJson.encode(notes)))
        assertEquals(emptyList<Note>(), NotesJson.decode(""))
        assertThrows(IllegalArgumentException::class.java) { NotesJson.decode(NotesJson.encode(notes + notes.first())) }
    }
    @Test fun allPinnedNotesAppearFirstThenRecentWithStableTies() {
        val old = Note("a", "old", true, 1)
        val newerPin = Note("b", "pinned", true, 2)
        val recent = Note("c", "recent", false, 3)
        assertEquals(listOf(newerPin, old, recent), orderedNotes(listOf(recent, old, newerPin)))
    }
    @Test fun autosaveIsTrailingDebouncedAndFlushDoesNotRepeat() = runTest {
        val saved = mutableListOf<Note>()
        val autosave = NoteAutosave(backgroundScope, saved::add)
        val first = Note("a", "a")
        autosave.change(first)
        advanceTimeBy(Metrics.noteSaveDebounceMs - 1); runCurrent(); assertTrue(saved.isEmpty())
        autosave.change(first.copy(text = "ab"))
        advanceTimeBy(Metrics.noteSaveDebounceMs - 1); runCurrent(); assertTrue(saved.isEmpty())
        advanceTimeBy(1); runCurrent(); assertEquals(listOf(first.copy(text = "ab")), saved)
        autosave.flush(); assertEquals(1, saved.size)
        autosave.change(first.copy(text = "abc")); autosave.flush()
        assertEquals("abc", saved.last().text)
        advanceTimeBy(Metrics.noteSaveDebounceMs); runCurrent(); assertEquals(2, saved.size)
    }
    @Test fun deletingCancelsPendingWritesAndDisposalCannotResurrect() = runTest {
        val saved = mutableListOf<Note>()
        val autosave = NoteAutosave(backgroundScope, saved::add)
        autosave.change(Note("a", "delete")); autosave.discard(); autosave.flush()
        autosave.change(Note("a", "late")); advanceTimeBy(Metrics.noteSaveDebounceMs); runCurrent()
        assertTrue(saved.isEmpty())
    }
    @Test fun undoIsSingleUseExpiresAtEightSecondsAndKeepsTheLatestDraft() {
        var now = 0L
        val undo = NoteDeleteUndo { now }
        val note = Note("a", "unsaved newest draft", true, 123)
        undo.offer(note); now = Metrics.noteUndoMs - 1
        assertEquals(note, undo.take()); assertNull(undo.take())
        undo.offer(note); now += Metrics.noteUndoMs; assertNull(undo.take())
        undo.offer(note); undo.offer(note.copy(id = "b")); assertEquals("b", undo.take()?.id)
    }
    @Test fun notesAreInsertedForOldConfigsButRemainOffUntilExplicitlyEnabled() {
        val old = "clock:true::;comms:true::;system:false:ram:;focus:true::"
        val config = HubConfig.decode(old)
        assertTrue("notes" in config.orderedPages); assertFalse("notes" in config.pages)
        assertTrue(config.orderedPages.indexOf("notes") < config.orderedPages.indexOf("comms"))
        assertEquals(listOf("clock", "comms", "system", "focus"), config.orderedPages.filter { it in setOf("clock", "comms", "system", "focus") })
        assertTrue("notes" in HubConfig().pages) // T158: Notes is one of the four default tabs for a new user
        assertFalse("notes" in legacyHubConfig().pages)
        val shown = config.showPage("notes", true).movePage("notes", -1)
        assertEquals(shown.encode(), HubConfig.decode(shown.encode()).encode())
        assertEquals("notes", restoredHubPage("notes", shown))
    }
    @Test fun notesTravelThroughV2EnvelopeAndRestoreWithTheirOwnAllowList() = runTest {
        val source = FakeSharedPreferences()
        val notes = listOf(Note("a", "- [x] Checked\nPrivate jotting", true, 123), Note("b", "Another", false, 456))
        source.data[NotesJson.Key] = NotesJson.encode(notes)
        source.data["not_allowed"] = "secret"
        val spec = BackupSpecs.notes
        assertEquals(spec, BackupSpecs.byId("notes"))
        assertEquals(listOf(NotesJson.Key), spec.keys.map { it.name })
        val original = FullBackup(FakeLayoutRepository(), listOf(BackupStore(spec, SharedPreferencesStoreAccess(source))))
        val exported = original.export()
        assertTrue(exported.contains("\"backupVersion\":2")); assertFalse(exported.contains("not_allowed"))
        val target = FakeSharedPreferences()
        val restore = FullBackup(FakeLayoutRepository(), listOf(BackupStore(spec, NotesBackupAccess(SharedPreferencesStoreAccess(target)))))
        assertEquals(1, restore.apply(restore.parse(exported)).settings)
        assertEquals(notes, NotesJson.decode(target.data[NotesJson.Key] as String))
        assertEquals(source.data[NotesJson.Key], target.data[NotesJson.Key])
        val malicious = BackupCodec.encode(com.brightblu.parc.model.ParcLayout(), mapOf("notes" to mapOf(NotesJson.Key to "broken")))
        assertEquals(1, restore.apply(restore.parse(malicious)).skipped)
        assertEquals(notes, NotesJson.decode(target.data[NotesJson.Key] as String))
    }
    private class MemoryNotes : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        override val data = state
        override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences =
            transform(state.value).also { state.value = it }
    }
    @Test fun productionWriterSerializesEditsAndDeleteUndoWithoutLosingOtherNotes() = runTest {
        val store = NotesStore(MemoryNotes(), backgroundScope) { testScheduler.currentTime }
        val a = Note("a", "first", true, 1)
        val b = Note("b", "other", false, 2)
        store.save(a); store.save(b); store.save(a.copy(text = "latest")); runCurrent()
        assertEquals(setOf(a.copy(text = "latest"), b), store.notes.first().toSet())
        store.delete(a.copy(text = "newest unsaved")); runCurrent()
        assertEquals(listOf(b), store.notes.first()); assertTrue(store.undoAvailable.value)
        store.undoDelete(); runCurrent()
        assertEquals(setOf(a.copy(text = "newest unsaved"), b), store.notes.first().toSet())
        assertFalse(store.undoAvailable.value)
        store.delete(a); runCurrent(); advanceTimeBy(Metrics.noteUndoMs); runCurrent()
        store.undoDelete(); runCurrent(); assertEquals(listOf(b), store.notes.first())
    }

}
