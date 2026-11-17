package com.brightblu.parc

/** T195: the pure rules of Note mode in the search sheet. No Android, no Compose. */
internal object NoteSearchRules {
    /** The text that would be saved (outer blank space removed), or null when there is nothing to save. */
    fun cleaned(text: String): String? = text.trim().takeIf { it.isNotEmpty() }

    /** A new note: newest first, not pinned, current spelling of the marks. */
    fun newNote(id: String, text: String, now: Long): Note? =
        cleaned(text)?.let { Note(id = id, text = it, pinned = false, updatedAt = now, marks = NoteMarks.Current) }

    /** The most recently edited note (pinned or not); ties go to the lower id so the choice is stable. */
    fun lastEdited(notes: List<Note>): Note? =
        notes.sortedWith(compareByDescending<Note> { it.updatedAt }.thenBy { it.id }).firstOrNull()

    /** [existing] with [addition] on a new line at the end. An empty note simply becomes the addition. */
    fun appended(existing: String, addition: String): String {
        val add = addition.trim()
        val base = existing.trimEnd('\n', ' ', '\t')
        return if (base.isEmpty()) add else base + "\n" + add
    }
}
