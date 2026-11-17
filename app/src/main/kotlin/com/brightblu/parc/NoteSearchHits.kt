package com.brightblu.parc

import com.brightblu.parc.feature.search.SearchNote

/**
 * T259: turns the notes page's own search rules into the rows the Apps section shows. Pure: no
 * Android, no store and no Compose. The note itself never leaves the phone.
 */
internal object NoteSearchHits {
    /** The sheet stays a few rows tall; a note search rarely needs more. */
    const val MaxHits = 3

    fun of(notes: List<Note>, query: String): List<SearchNote> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return NoteSearch.results(notes, needle).take(MaxHits).map { note ->
            SearchNote(note.id, NoteTitles.display(note), detail(note, needle))
        }
    }

    /**
     * The first line of the note body that mentions the query, otherwise the first line of the body.
     * The title line is not repeated under itself. Marks are stripped, and the line is shortened so
     * the row keeps one caption line.
     */
    fun detail(note: Note, query: String): String {
        val needle = query.trim()
        val lines = NoteMarkup.parse(note.text).map { NoteMarkup.stripped(it).trim() }.filter { it.isNotEmpty() }
        val title = NoteTitles.display(note)
        val match = if (needle.isEmpty()) null else lines.firstOrNull { it.contains(needle, ignoreCase = true) }
        val line = match ?: lines.firstOrNull() ?: return ""
        if (line.equals(title, ignoreCase = true)) return ""
        return if (line.length <= MaxDetailChars) line else line.take(MaxDetailChars - 1).trimEnd() + "…"
    }

    const val MaxDetailChars = 80
}
