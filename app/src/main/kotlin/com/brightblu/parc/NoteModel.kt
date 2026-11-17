package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

/**
 * Plain text is authoritative (marks are described in NoteMarkup.kt). [marks] is the spelling version:
 * 1 = before 1.2.0 (every `- ` line was a checklist item), 2 = from 1.2.0 (`- ` is a bullet).
 */
internal data class Note(
    val id: String, val text: String, val pinned: Boolean = false, val updatedAt: Long = 0,
    val marks: Int = NoteMarks.Current,
    /** T183: when to remind (epoch ms), or 0 for no reminder. Cleared once it has fired. */
    val remindAt: Long = 0,
    /** T237: optional explicit title. When blank the first line is used as before. */
    val title: String = "",
)
internal object NoteMarks {
    const val Legacy = 1
    const val Current = 2
}
/** Rewrites a pre-1.2.0 note once. Notes already at the current version are returned untouched. */
internal fun Note.migrated(): Note =
    if (marks >= NoteMarks.Current) this else copy(text = migrateLegacyChecklist(text), marks = NoteMarks.Current)
/** Thrown by the store when all notes together would pass [com.brightblu.parc.model.Metrics.notesMaxChars]. */
internal class NotesTooLargeException : Exception("Notes are over the size limit")
internal fun orderedNotes(notes: List<Note>): List<Note> = notes.sortedWith(
    compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt }.thenBy { it.id })

/** T250: the Notes page search. Plain, case-insensitive text over the title and the note body. */
internal object NoteSearch {
    fun matches(note: Note, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return NoteTitles.normalize(note.title).contains(needle, ignoreCase = true) ||
            note.text.contains(needle, ignoreCase = true)
    }

    /** Matching notes in the page's normal order (pinned first, then most recently edited). */
    fun results(notes: List<Note>, query: String): List<Note> = orderedNotes(notes.filter { matches(it, query) })

    /** The quiet empty line when a search found nothing; null when the list itself is empty. */
    fun emptyMessage(notes: List<Note>, query: String): String? {
        val needle = query.trim()
        return if (needle.isNotEmpty() && notes.isNotEmpty() && results(notes, needle).isEmpty())
            "No notes match “$needle”." else null
    }
}
internal object NotesJson {
    const val Key = "notes_v1"
    fun encode(notes: List<Note>): String = JsonArray(notes.map { note -> buildJsonObject {
        put("id", note.id); put("text", note.text); put("pinned", note.pinned); put("updatedAt", note.updatedAt)
        put("marks", note.marks)
        if (note.remindAt > 0) put("remindAt", note.remindAt)
        NoteTitles.normalize(note.title).takeIf { it.isNotEmpty() }?.let { put("title", it) }
    } }).toString()
    /** Re-spells the notes of a stored or restored document once; a document that needs nothing is returned as is. */
    fun migrate(raw: String): String {
        if (raw.isBlank()) return raw
        val notes = decode(raw)
        return if (notes.all { it.marks >= NoteMarks.Current }) raw else encode(notes.map { it.migrated() })
    }
    fun decode(raw: String): List<Note> {
        if (raw.isBlank()) return emptyList()
        val array = Json.parseToJsonElement(raw).jsonArray
        return array.map { value ->
            val obj = value.jsonObject
            Note(obj.getValue("id").jsonPrimitive.content, obj.getValue("text").jsonPrimitive.content,
                obj["pinned"]?.jsonPrimitive?.booleanOrNull ?: false,
                obj["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0,
                obj["marks"]?.jsonPrimitive?.intOrNull ?: NoteMarks.Legacy,
                (obj["remindAt"]?.jsonPrimitive?.longOrNull ?: 0).coerceAtLeast(0),
                NoteTitles.normalize(obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty()))
        }.also { notes -> require(notes.all { it.id.isNotBlank() } && notes.map { it.id }.distinct().size == notes.size) }
    }
}

/**
 * T237: the explicit title wins; a blank title keeps the old first-line heading. Titles are plain
 * text (not markdown) and are kept on one line.
 */
internal object NoteTitles {
    fun normalize(raw: String): String = raw.replace('\n', ' ').replace('\r', ' ').trim()
    /** The title to show, or the first non-blank line when none was given. May be empty for an empty note. */
    fun raw(note: Note): String = normalize(note.title).ifEmpty { NoteReminderRules.firstLine(note.text) }
    /** Same, with the word "Note" for a note that has nothing to show. */
    fun display(note: Note): String = raw(note).ifBlank { "Note" }
}

/** T237: the "Saved to Notes" confirmation, with the same 8 s undo window as a deletion. */
internal data class NoteSaveNotice(val note: Note, val expiresAt: Long)
internal object NoteSaveFeedback {
    fun hasContent(note: Note): Boolean = note.text.isNotBlank() || NoteTitles.normalize(note.title).isNotEmpty()
    fun forNewNote(note: Note, now: Long): NoteSaveNotice? =
        if (hasContent(note)) NoteSaveNotice(note, now + Metrics.noteUndoMs) else null
    fun visible(notice: NoteSaveNotice?, now: Long): Boolean = notice != null && now < notice.expiresAt
    fun undo(notice: NoteSaveNotice?, now: Long): Note? = notice?.takeIf { now < it.expiresAt }?.note
}

/** Only the timer is cancelled. Once queued, writes finish in order on the process-owned scope. */
internal class NoteAutosave(scope: CoroutineScope, private val save: (Note) -> Unit) {
    private val scope = scope
    private var pending: Note? = null
    private var timer: Job? = null
    private var deleted = false
    fun change(note: Note) {
        if (deleted) return
        pending = note
        timer?.cancel()
        timer = scope.launch { delay(Metrics.noteSaveDebounceMs); flush() }
    }
    fun flush() {
        timer?.cancel(); timer = null
        pending?.let { if (!deleted) save(it) }; pending = null
    }
    fun discard() { deleted = true; timer?.cancel(); pending = null }
}
internal class NoteDeleteUndo(private val clock: () -> Long) {
    private var note: Note? = null
    private var expires = 0L
    fun offer(value: Note) { note = value; expires = clock() + Metrics.noteUndoMs }
    fun take(): Note? { val value = note; note = null; return value.takeIf { clock() < expires } }
}
