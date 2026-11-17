package com.brightblu.parc.reader

import com.brightblu.parc.core.data.library.Book
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderBookMarks
import com.brightblu.parc.core.data.library.ReaderHighlight
import com.brightblu.parc.core.data.library.ReaderHighlightTone

/**
 * T268: the demo Library books have `demo://` locations and no file behind them. In demo mode only,
 * the reader shows this short public-domain sample instead, with one quiet demo highlight. Nothing
 * here reads a real file, store or person; normal builds never enter this path.
 */
internal object ReaderDemo {
    const val UriPrefix = "demo://"
    const val HighlightId = "demo-highlight"
    const val HighlightPhrase = "There were four walls, a floor and a roof"

    /** A short public-domain passage, with its source named in the text itself. */
    val SampleText: String = """
        Demo sample text.

        From The Wonderful Wizard of Oz by L. Frank Baum (1900), in the public domain.

        Dorothy lived in the midst of the great Kansas prairies, with Uncle Henry, who was a farmer, and Aunt Em, who was the farmer's wife. Their house was small, for the lumber to build it had to be carried by wagon many miles. There were four walls, a floor and a roof, which made one room; and this room contained a rusty looking cookstove, a cupboard for the dishes, a table, three or four chairs, and the beds.
    """.trimIndent()

    fun isDemoBook(book: Book): Boolean = book.uri.startsWith(UriPrefix)

    fun doc(book: Book): ReaderBookDoc =
        ReaderBookDoc.plainText(book.id, book.title, book.author, SampleText)

    fun highlight(): ReaderHighlight {
        val start = SampleText.indexOf(HighlightPhrase)
        return ReaderHighlight(
            id = HighlightId,
            chapter = 0,
            start = start,
            end = start + HighlightPhrase.length,
            tone = ReaderHighlightTone.Sand,
            text = HighlightPhrase,
            createdAt = 0,
        )
    }

    /** Keep one demo highlight without hiding or replacing any mark the reader already showed. */
    fun withHighlight(stored: ReaderBookMarks): ReaderBookMarks =
        if (stored.highlights.any { it.id == HighlightId }) stored
        else stored.copy(highlights = listOf(highlight()) + stored.highlights)

    fun marks(): ReaderBookMarks = ReaderBookMarks(highlights = listOf(highlight()))
}
