package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.model.Metrics
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** T237: saved feedback for a quick jot, and an optional explicit note title. */
class T237Test {
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private fun input() = TodayInput(now, ZoneOffset.UTC, setOf("notes"))

    // ---- (b) title model, display and backup

    @Test fun titleIsOptionalRoundTripsAndOldNotesDecodeUnchanged() {
        val titled = Note("a", "body", title = "  My title \n", remindAt = 123L)
        val raw = NotesJson.encode(listOf(titled))
        assertTrue(raw.contains("\"title\":\"My title\""))
        assertEquals("My title", NotesJson.decode(raw).single().title)

        val old = """[{"id":"o","text":"old","pinned":true,"updatedAt":5,"marks":2}]"""
        val oldNote = NotesJson.decode(old).single()
        assertEquals("", oldNote.title)
        assertFalse(NotesJson.encode(listOf(oldNote)).contains("\"title\""))

        assertEquals("", NotesJson.decode("""[{"id":"x","text":"t","title":"   "}]""").single().title)
        assertEquals(titled.copy(title = "My title"), NotesJson.decode(NotesJson.encode(listOf(titled))).single())
    }

    @Test fun titleFallsBackToTheFirstLineAndNeverBreaksOneLine() {
        assertEquals("Buy milk", NoteTitles.raw(Note("a", "\n\n# **Buy** milk\n- eggs")))
        assertEquals("My title", NoteTitles.raw(Note("a", "body", title = "My title")))
        assertEquals("Note", NoteTitles.display(Note("a", "")))
        assertEquals("a b", NoteTitles.normalize(" a\nb\r "))

        val old = Note("old", "# **Water plants**\nLater")
        assertEquals("Water plants", NoteTitles.display(old))
        val named = old.copy(title = "Garden")
        assertEquals("Garden", NoteTitles.display(named))
    }

    @Test fun titleSurvivesTheNotesBackupSection() = runTest {
        val source = FakeSharedPreferences()
        source.data[NotesJson.Key] = NotesJson.encode(listOf(Note("a", "body", title = "Kept")))
        val target = FakeSharedPreferences()
        NotesBackupAccess(SharedPreferencesStoreAccess(target)).write(
            BackupSpecs.notes, mapOf<String, Any>(NotesJson.Key to (source.data[NotesJson.Key] as String)))
        val restored = NotesJson.decode(target.data[NotesJson.Key] as String).single()
        assertEquals("Kept", restored.title)
        assertEquals("body", restored.text)
        assertEquals(listOf(NotesJson.Key), BackupSpecs.notes.keys.map { it.name })
    }

    @Test fun todayUsesTheTitleForThePinnedLineAndTheNextReminder() {
        val note = Note("n", "Body", pinned = true, remindAt = now.toEpochMilli() + 3_600_000, title = "Heading")
        assertEquals("Heading", TodayLines.pinned(input().copy(notes = listOf(note))))
        val reminder = TodayLines.reminder(input().copy(notes = listOf(note)))!!
        assertTrue(reminder, reminder.endsWith(" · Heading"))
    }

    @Test fun titleIsUsedByTheReminderNotificationAndTheTimerLabel() {
        val reminders = File("src/main/kotlin/com/brightblu/parc/NoteReminders.kt").readText()
        val timerUi = File("src/main/kotlin/com/brightblu/parc/NoteReminderUi.kt").readText()
        assertTrue(reminders.contains("NoteTitles.display(note)"))
        assertTrue(timerUi.contains("NoteTitles.raw(note)"))
    }

    // ---- (a) saved feedback, full time and Undo

    @Test fun newNoteConfirmationLastsTheFullUndoTimeAndUndoHasTheSavedNote() {
        val note = Note("n", "fresh", title = "Title")
        val notice = NoteSaveFeedback.forNewNote(note, 1_000L)!!
        assertTrue(NoteSaveFeedback.visible(notice, 1_000L + Metrics.noteUndoMs - 1))
        assertEquals(note, NoteSaveFeedback.undo(notice, 1_000L + Metrics.noteUndoMs - 1))
        assertFalse(NoteSaveFeedback.visible(notice, 1_000L + Metrics.noteUndoMs))
        assertNull(NoteSaveFeedback.undo(notice, 1_000L + Metrics.noteUndoMs))
        assertNull(NoteSaveFeedback.forNewNote(Note("empty", ""), 1_000L))
        assertNotNull(NoteSaveFeedback.forNewNote(Note("title-only", "", title = "T"), 1_000L))
    }

    @Test fun aNewSearchNoteIsNewestFirstInTheHubOrder() {
        val older = Note("older", "old", updatedAt = 10L)
        val saved = NoteSearchRules.newNote("new", "fresh", 20L)!!
        assertEquals(listOf("new", "older"), orderedNotes(listOf(older, saved)).map { it.id })
        assertTrue(NoteSaveFeedback.hasContent(saved))
    }

    @Test fun searchConfirmationIsRenderedOutsideTheDraftForItsWholeTime() {
        val source = File("src/main/kotlin/com/brightblu/parc/NoteSearchMode.kt").readText()
        val body = source.substringAfter("override fun Body(").substringBefore("private enum class Phase")
        assertTrue("the saved card is its own row, not the empty-field branch", body.contains("current?.let"))
        assertFalse("the confirmation must not depend on the field being blank", body.contains("Phase.Saved"))
        val animated = body.substringAfter("AnimatedContent(").substringBefore("current?.let")
        assertFalse("the draft animation must not own the confirmation", animated.contains("SavedCard"))
        assertTrue(body.contains("SavedCard("))
    }

    @Test fun hubEditorHasTitleFieldAndBottomSavedRowAboveTheKeyboard() {
        val source = File("src/main/kotlin/com/brightblu/parc/NoteEditorUi.kt").readText()
        assertTrue(source.contains("private fun NoteTitleField("))
        assertTrue(source.contains("NoteTitleField(draft.title, skin)"))
        assertTrue(source.contains("NoteSaveFeedback.forNewNote"))
        val body = source.indexOf("if (editing) {", source.indexOf("internal fun NoteEditor("))
        val saved = source.indexOf("Saved to Notes", body)
        assertTrue("the saved row is after the text/toolbar, at the bottom above the keyboard", saved > body)
    }

    @Test fun theHubShowsTheTitleAsTheHeadingAndReturnsToTheNewTopNote() {
        val source = File("src/main/kotlin/com/brightblu/parc/NotesHubPage.kt").readText()
        assertTrue(source.contains("NoteTitles.normalize(note.title)"))
        assertTrue(source.contains("FontWeight.SemiBold"))
        assertTrue(source.contains("listScroll.scrollTo(0)"))
        assertTrue("the editor must carry the stored title, not start blank", source.contains("title = editorTitle"))
        assertTrue(source.contains("editorTitle = it.title"))
    }
}
