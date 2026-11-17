package com.brightblu.parc

import com.brightblu.parc.core.data.backup.*
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class T182Test {
    private fun runsOf(raw: String) = NoteMarkup.parseLine(raw).runs
    private fun shown(raw: String) = NoteMarkup.stripped(NoteMarkup.parseLine(raw))

    // ---- parser: block marks
    @Test fun everyBlockMarkIsRecognised() {
        assertEquals(LineKind.Heading, NoteMarkup.parseLine("# Title").kind)
        assertEquals(LineKind.Bullet, NoteMarkup.parseLine("- a").kind)
        val n = NoteMarkup.parseLine("12. twelve")
        assertEquals(LineKind.Numbered, n.kind); assertEquals(12, n.number); assertEquals("twelve", shown("12. twelve"))
        assertEquals(LineKind.Checkbox, NoteMarkup.parseLine("- [ ] a").kind)
        assertTrue(NoteMarkup.parseLine("- [x] a").checked)
        assertEquals(LineKind.Plain, NoteMarkup.parseLine("#Title").kind)
        assertEquals(LineKind.Plain, NoteMarkup.parseLine("1.5 litres").kind)
        assertEquals(LineKind.Plain, NoteMarkup.parseLine("1234567890. too long").kind)
    }
    @Test fun emptyLinesAndOffsets() {
        val lines = NoteMarkup.parse("a\n\n- b\n")
        assertEquals(4, lines.size)
        assertEquals(listOf(0, 2, 3, 7), lines.map { it.offset })
        assertEquals("", shown("")); assertTrue(runsOf("").isEmpty())
        assertEquals(LineKind.Bullet, lines[2].kind); assertEquals("", lines[3].raw)
        assertEquals(listOf(LineKind.Plain), NoteMarkup.parse("").map { it.kind })
    }

    // ---- parser: inline marks
    @Test fun boldItalicStrikeAreStyledAndMarksAreFlagged() {
        val bold = runsOf("a **b** c")
        assertEquals(listOf("a ", "**", "b", "**", " c"), bold.map { "a **b** c".substring(it.start, it.end) })
        assertEquals(listOf(false, true, false, true, false), bold.map { it.mark })
        assertTrue(bold[2].bold && !bold[2].italic && !bold[0].bold)
        assertTrue(runsOf("*i*")[1].italic)
        assertTrue(runsOf("~~s~~")[1].strike)
        assertEquals("a b c", shown("a **b** c"))
        assertEquals("one two", shown("*one* ~~two~~"))
    }
    @Test fun boldAndItalicNest() {
        val inner = runsOf("**bold *both* bold**")
        val both = inner.first { it.start == 8 && !it.mark }
        assertTrue(both.bold && both.italic)
        assertEquals("bold both bold", shown("**bold *both* bold**"))
        val triple = runsOf("***x***")
        assertEquals(1, triple.count { !it.mark }); assertTrue(triple.first { !it.mark }.let { it.bold && it.italic })
        val italicOutside = runsOf("*a **b** c*").first { !it.mark && "*a **b** c*".substring(it.start, it.end) == "b" }
        assertTrue(italicOutside.bold && italicOutside.italic)
    }
    @Test fun unclosedAndSpacedMarksAreShownAsTyped() {
        for (raw in listOf("**open", "a * b * c", "2 * 3", "~~half", "**", "****", "* spaced *", "*a", "x ** y")) {
            assertEquals(raw, shown(raw))
            assertTrue(raw, runsOf(raw).none { it.mark })
        }
        assertEquals("**a *b", shown("**a *b"))
        assertEquals("keep **open and done", shown("keep **open and *done*"))
    }
    @Test fun markersInsideBlockLines() {
        assertEquals("a bold", shown("- a **bold**"))
        assertEquals("Big title", shown("# Big **title**"))
        assertTrue(runsOf("# Big").first { !it.mark }.bold)
        val line = NoteMarkup.parseLine("- [x] done *it*")
        assertEquals("done it", NoteMarkup.stripped(line)); assertTrue(line.checked)
    }

    // ---- toggle and migration
    @Test fun migrationRewritesPlainDashLinesOnce() {
        val old = "Title\n- milk\n- [x] bread\n- [ ] eggs\n-not a list\n- \n"
        val moved = migrateLegacyChecklist(old)
        assertEquals("Title\n- [ ] milk\n- [x] bread\n- [ ] eggs\n-not a list\n- [ ] \n", moved)
        assertEquals(moved, migrateLegacyChecklist(moved))
    }
    @Test fun migrationRunsOncePerNoteAndNeverTouchesNewNotes() {
        val legacy = Note("a", "- milk", false, 1, NoteMarks.Legacy)
        val fresh = Note("b", "- bullet", false, 2)
        assertEquals(NoteMarks.Current, fresh.marks)
        val raw = NotesJson.encode(listOf(legacy, fresh))
        val once = NotesJson.migrate(raw)
        assertEquals(listOf("- [ ] milk", "- bullet"), NotesJson.decode(once).map { it.text })
        assertTrue(NotesJson.decode(once).all { it.marks == NoteMarks.Current })
        assertEquals(once, NotesJson.migrate(once))
        val onlyNew = NotesJson.encode(listOf(fresh))
        assertEquals(onlyNew, NotesJson.migrate(onlyNew))
        assertEquals("", NotesJson.migrate(""))
    }
    @Test fun oldStoredNotesWithoutAMarksFieldDecodeAsLegacy() {
        val old = """[{"id":"a","text":"- milk","pinned":true,"updatedAt":5}]"""
        val note = NotesJson.decode(old).single()
        assertEquals(NoteMarks.Legacy, note.marks); assertTrue(note.pinned); assertEquals(5L, note.updatedAt)
        assertEquals("- [ ] milk", note.migrated().text)
    }
    @Test fun storeMigratesOnLoadAndWritesItBackOnce() = runTest {
        val data = MemoryNotes()
        val key = androidx.datastore.preferences.core.stringPreferencesKey(NotesJson.Key)
        data.updateData { it.toMutablePreferences().apply { this[key] = """[{"id":"a","text":"- milk\n- [x] bread","pinned":false,"updatedAt":1}]""" } }
        val store = NotesStore(data, backgroundScope) { testScheduler.currentTime }
        runCurrent()
        assertEquals("- [ ] milk\n- [x] bread", store.notes.first().single().text)
        val stored = NotesJson.decode(data.data.first()[key]!!).single()
        assertEquals("- [ ] milk\n- [x] bread", stored.text); assertEquals(NoteMarks.Current, stored.marks)
        store.save(stored.copy(text = "- now a bullet")); runCurrent()
        assertEquals("- now a bullet", store.notes.first().single().text)
    }
    @Test fun restoringAnOldBackupMigratesItAndANewOneIsLeftAlone() = runTest {
        val target = FakeSharedPreferences()
        val spec = BackupSpecs.notes
        val restore = FullBackup(FakeLayoutRepository(), listOf(BackupStore(spec, NotesBackupAccess(SharedPreferencesStoreAccess(target)))))
        val oldDoc = BackupCodec.encode(com.brightblu.parc.model.ParcLayout(), mapOf("notes" to mapOf(NotesJson.Key to """[{"id":"a","text":"- milk","pinned":false,"updatedAt":1}]""")))
        restore.apply(restore.parse(oldDoc))
        assertEquals("- [ ] milk", NotesJson.decode(target.data[NotesJson.Key] as String).single().text)
        val newRaw = NotesJson.encode(listOf(Note("b", "- bullet")))
        restore.apply(restore.parse(BackupCodec.encode(com.brightblu.parc.model.ParcLayout(), mapOf("notes" to mapOf(NotesJson.Key to newRaw)))))
        assertEquals(newRaw, target.data[NotesJson.Key])
    }
    @Test fun sizeLimitIsFiveMillionAndOverflowSaysSo() = runTest {
        assertEquals(5_000_000, Metrics.notesMaxChars)
        assertEquals(Metrics.notesMaxChars, BackupSpecs.notes.keys.single().maxLength)
        val store = NotesStore(MemoryNotes(), backgroundScope) { testScheduler.currentTime }
        store.save(Note("big", "x".repeat(20_000) + "\n".repeat(400))); runCurrent()
        assertFalse(store.overLimit.value); assertEquals(1, store.notes.first().size)
        store.save(Note("huge", "y".repeat(Metrics.notesMaxChars))); runCurrent()
        assertTrue(store.overLimit.value); assertTrue(store.error.value)
        assertEquals(listOf("big"), store.notes.first().map { it.id }) // nothing lost, nothing crashed
        store.save(Note("small", "ok")); runCurrent()
        assertFalse(store.overLimit.value)
    }
    @Test fun noteOf20000CharsAnd400LinesParses() {
        val text = (1..400).joinToString("\n") { "line $it " + "w".repeat(46) }
        assertTrue(text.length >= 20_000)
        val lines = NoteMarkup.parse(text)
        assertEquals(400, lines.size)
        assertEquals(text.length - lines.last().raw.length, lines.last().offset)
    }

    // ---- list toggles
    @Test fun toggleOneLineOnAndOff() {
        val on = NoteEditing.toggleLine("milk\nbread", 1, 1, LineKind.Bullet)
        assertEquals("- milk\nbread", on.text); assertEquals(3, on.selStart)
        val off = NoteEditing.toggleLine(on.text, on.selStart, on.selEnd, LineKind.Bullet)
        assertEquals("milk\nbread", off.text); assertEquals(1, off.selStart)
    }
    @Test fun toggleSwitchesKindAndHandlesSelectionOfSeveralLines() {
        val text = "a\n- b\nc\nd"
        val r = NoteEditing.toggleLine(text, 0, 7, LineKind.Checkbox)
        assertEquals("- [ ] a\n- [ ] b\n- [ ] c\nd", r.text)
        val back = NoteEditing.toggleLine(r.text, r.selStart, r.selEnd, LineKind.Checkbox)
        assertEquals("a\nb\nc\nd", back.text)
        val numbered = NoteEditing.toggleLine("x\ny\nz", 0, 5, LineKind.Numbered)
        assertEquals("1. x\n2. y\n3. z", numbered.text)
        assertEquals("# x", NoteEditing.toggleLine("x", 0, 0, LineKind.Heading).text)
        assertEquals("- ", NoteEditing.toggleLine("", 0, 0, LineKind.Bullet).text)
    }
    @Test fun selectionEndingAtLineStartDoesNotTouchNextLine() {
        assertEquals("- a\nb", NoteEditing.toggleLine("a\nb", 0, 2, LineKind.Bullet).text)
    }

    // ---- wrap
    @Test fun wrapSelectionAndUnwrap() {
        val w = NoteEditing.wrap("say hello now", 4, 9, "**")
        assertEquals("say **hello** now", w.text); assertEquals(6, w.selStart); assertEquals(11, w.selEnd)
        assertEquals("say hello now", NoteEditing.wrap(w.text, w.selStart, w.selEnd, "**").text)
        assertEquals("a ~~b~~", NoteEditing.wrap("a b", 2, 3, "~~").text)
    }
    @Test fun wrapWithoutSelectionInsertsPairWithCaretInside() {
        val r = NoteEditing.wrap("ab", 1, 1, "*")
        assertEquals("a**b", r.text); assertEquals(2, r.selStart); assertEquals(2, r.selEnd)
        val b = NoteEditing.wrap("", 0, 0, "**")
        assertEquals("****", b.text); assertEquals(2, b.selStart)
    }
    @Test fun italicDoesNotUnwrapBold() {
        assertEquals("***x***", NoteEditing.wrap("**x**", 2, 3, "*").text)
    }

    // ---- Enter
    private fun enter(text: String, caret: Int): NoteEdit? =
        NoteEditing.onEnter(text, text.substring(0, caret) + "\n" + text.substring(caret), caret + 1)
    @Test fun enterContinuesBulletNumberAndUntickedCheckbox() {
        assertEquals("- a\n- ", enter("- a", 3)!!.text)
        assertEquals(6, enter("- a", 3)!!.selStart)
        assertEquals("1. a\n2. ", enter("1. a", 4)!!.text)
        assertEquals("9. a\n10. ", enter("9. a", 4)!!.text)
        val box = enter("- [x] done", 10)!!
        assertEquals("- [x] done\n- [ ] ", box.text); assertEquals(box.text.length, box.selStart)
    }
    @Test fun enterInTheMiddleSplitsTheItem() {
        val r = enter("- abcd", 4)!!
        assertEquals("- ab\n- cd", r.text); assertEquals(7, r.selStart)
    }
    @Test fun enterOnEmptyItemEndsTheList() {
        val r = enter("- a\n- ", 6)!!
        assertEquals("- a\n", r.text); assertEquals(4, r.selStart)
        assertEquals("", enter("- [ ] ", 6)!!.text)
        assertEquals("1. a\n", enter("1. a\n2. ", 8)!!.text)
        assertEquals("x\n- a\n\ntail", enter("x\n- a\n- \ntail", 8)!!.text)
    }
    @Test fun enterElsewhereIsLeftToTheField() {
        assertNull(enter("plain", 5)); assertNull(enter("# head", 6))
        assertNull(enter("- a", 1)) // caret inside the mark
        assertNull(NoteEditing.onEnter("ab", "abc", 3)) // not an Enter
        assertNull(NoteEditing.onEnter("- a", "- ax\n", 5))
    }

    private class MemoryNotes : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        private val state = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        override val data = state
        override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences =
            transform(state.value).also { state.value = it }
    }
}
