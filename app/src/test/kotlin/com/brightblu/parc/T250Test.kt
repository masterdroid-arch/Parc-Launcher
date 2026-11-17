package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** T250: notes share in as a new note, the Notes page searches its own text, and a delete can be undone. */
class T250Test {
    private fun note(id: String, text: String, title: String = "", updatedAt: Long = 0, pinned: Boolean = false) =
        Note(id, text, pinned = pinned, updatedAt = updatedAt, title = title)

    @Test fun aBlankSearchKeepsTheNormalPageOrder() {
        val notes = listOf(
            note("old", "old body", updatedAt = 10),
            note("new", "new body", updatedAt = 20),
            note("pinned", "pinned body", updatedAt = 1, pinned = true),
        )
        assertEquals(listOf("pinned", "new", "old"), NoteSearch.results(notes, "").map { it.id })
        assertEquals(listOf("pinned", "new", "old"), NoteSearch.results(notes, "   ").map { it.id })
    }

    @Test fun searchLooksAtTheTitleAndTheBodyIgnoringCaseAndOuterSpace() {
        val notes = listOf(
            note("title", "unrelated", title = "Garden plan"),
            note("body", "Buy **milk** and bread"),
            note("other", "nothing here"),
        )
        assertEquals(listOf("title"), NoteSearch.results(notes, "  garden ").map { it.id })
        assertEquals(listOf("body"), NoteSearch.results(notes, "MILK").map { it.id })
        assertTrue(NoteSearch.results(notes, "absent").isEmpty())
    }

    @Test fun noMatchesLineOnlyWhenNotesExistAndAQueryIsTyped() {
        val notes = listOf(note("a", "Alpha"))
        assertNull(NoteSearch.emptyMessage(notes, ""))
        assertNull(NoteSearch.emptyMessage(emptyList(), "Alpha"))
        assertNull(NoteSearch.emptyMessage(notes, "Alpha"))
        assertEquals("No notes match “Beta”.", NoteSearch.emptyMessage(notes, " Beta "))
    }

    @Test fun sharedTextAcceptsOnlyPlainTextSendAndBecomesANewNote() {
        assertEquals("Selected text",
            NoteShareRules.text("android.intent.action.SEND", "text/plain", "Selected text"))
        assertNull(NoteShareRules.text("android.intent.action.VIEW", "text/plain", "Selected text"))
        assertNull(NoteShareRules.text("android.intent.action.SEND", "text/html", "<b>text</b>"))
        assertNull(NoteShareRules.text("android.intent.action.SEND", "text/plain", "  "))

        val note = NoteShareRules.newNote("shared-1", "  two lines\nof text ", 42L)!!
        assertEquals("shared-1", note.id)
        assertEquals("two lines\nof text", note.text)
        assertFalse(note.pinned)
        assertEquals(42L, note.updatedAt)
        assertEquals(NoteMarks.Current, note.marks)
    }

    @Test fun deletingANoteCanBeUndoneForTheUndoWindowOnly() {
        var now = 1_000L
        val undo = NoteDeleteUndo { now }
        val note = note("a", "Keep this")
        undo.offer(note)
        assertEquals(note, undo.take())
        assertNull(undo.take())

        undo.offer(note)
        now += Metrics.noteUndoMs - 1
        assertEquals(note, undo.take())

        undo.offer(note)
        now += Metrics.noteUndoMs
        assertNull(undo.take())
    }

    @Test fun theNotesPageHasTheSearchFieldAndTheUndoRow() {
        val source = File("src/main/kotlin/com/brightblu/parc/NotesHubPage.kt").readText()
        assertTrue(source.contains("NoteSearchField(query, skin"))
        assertTrue(source.contains("HubSearchField(query, \"Search notes\", \"Search notes\""))
        assertTrue(source.contains("NoteSearch.results("))
        assertTrue(source.contains("NoteAction(\"Undo\""))
        assertTrue(source.contains("Note deleted"))
    }

    @Test fun theNewNoteShareTargetOpensTheEditorAndSavesOnOpen() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".ShareNoteActivity\""))
        assertTrue(manifest.contains("android:label=\"New note\""))
        assertTrue(manifest.contains("android.intent.action.SEND"))

        val share = File("src/main/kotlin/com/brightblu/parc/ShareNoteActivity.kt").readText()
        assertTrue(share.contains("NoteShareRules.ExtraText"))
        assertTrue(share.contains("MainActivity::class.java"))

        val launcher = File("src/main/kotlin/com/brightblu/parc/MainActivity.kt").readText()
        assertTrue(launcher.contains("postNoteShare(intent)"))
        assertTrue(launcher.contains("NoteEditor(note, skin, noteStore"))
        assertTrue(launcher.contains("saveOnOpen = true"))

        val editor = File("src/main/kotlin/com/brightblu/parc/NoteEditorUi.kt").readText()
        assertTrue(editor.contains("saveOnOpen: Boolean = false"))
        assertTrue(editor.contains("if (saveOnOpen) change(draft)"))
    }
}
