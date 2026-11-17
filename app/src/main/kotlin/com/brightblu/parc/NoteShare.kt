package com.brightblu.parc

/**
 * T250: plain text shared from another app can start a new note. The share target only accepts the
 * same text/plain SEND that the quote share accepts; the launcher then opens the note editor with
 * the text already saved, and its normal Undo removes it.
 */
internal object NoteShareRules {
    /** The private intent extra that carries the shared text from [ShareNoteActivity] to the launcher. */
    const val ExtraText = "com.brightblu.parc.extra.NOTE_SHARE_TEXT"

    fun text(action: String?, type: String?, shared: CharSequence?): String? =
        QuoteRules.sharedText(action, type, shared)

    /** A new, unpinned note carrying the shared text as typed (outer blank space removed). */
    fun newNote(id: String, text: String, now: Long): Note? = NoteSearchRules.newNote(id, text, now)
}
