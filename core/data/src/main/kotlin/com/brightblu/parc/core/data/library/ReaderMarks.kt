package com.brightblu.parc.core.data.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** T236: the three quiet highlight colours. The actual colours are skin tokens. */
@Serializable
enum class ReaderHighlightTone { Sand, Sage, Rose }

/**
 * T236: a bookmark is stored as chapter plus character offset (EPUB/text) or a PDF page. A PDF
 * bookmark has [page] >= 0; an EPUB/text bookmark keeps page -1. [snippet] is a little context
 * for the Marks panel and is never used to find the place again.
 */
@Serializable
data class ReaderBookmark(
    val id: String,
    val chapter: Int = 0,
    val characterOffset: Int = 0,
    val page: Int = -1,
    val snippet: String = "",
    val createdAt: Long = 0,
) {
    init {
        require(id.isNotBlank())
        require(chapter >= 0 && characterOffset >= 0)
        require(page >= -1)
        require(snippet.length <= ReaderMarksLimits.MaxSnippet)
    }

    val isPdf: Boolean get() = page >= 0
}

/** A saved highlight. The range is always chapter plus character range, never a page number. */
@Serializable
data class ReaderHighlight(
    val id: String,
    val chapter: Int,
    val start: Int,
    val end: Int,
    val tone: ReaderHighlightTone = ReaderHighlightTone.Sand,
    val text: String = "",
    val note: String = "",
    val createdAt: Long = 0,
) {
    init {
        require(id.isNotBlank() && chapter >= 0)
        require(start >= 0 && end > start)
        require(text.length <= ReaderMarksLimits.MaxSelectedText)
        require(note.length <= ReaderMarksLimits.MaxNote)
    }
}

@Serializable
data class ReaderBookMarks(
    val bookmarks: List<ReaderBookmark> = emptyList(),
    val highlights: List<ReaderHighlight> = emptyList(),
) {
    val isEmpty: Boolean get() = bookmarks.isEmpty() && highlights.isEmpty()
}

/** The whole phone-local document. Books are keyed by the stable reader/book id. */
@Serializable
data class ReaderMarksDocument(
    val books: Map<String, ReaderBookMarks> = emptyMap(),
) {
    fun forBook(bookId: String): ReaderBookMarks = books[bookId] ?: ReaderBookMarks()

    fun withBook(bookId: String, marks: ReaderBookMarks): ReaderMarksDocument {
        if (marks.isEmpty) return copy(books = books - bookId)
        return copy(books = books + (bookId to marks))
    }
}

object ReaderMarksLimits {
    const val MaxChars = 1_000_000
    const val MaxSnippet = 320
    const val MaxSelectedText = 4_000
    const val MaxNote = 4_000
    const val MaxQuote = 2_000
    const val MarksPanelSnippet = 160
}

/** Empty, old or malformed data reads as no marks; one bad row never loses the rest. */
object ReaderMarksJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(document: ReaderMarksDocument): String {
        val text = json.encodeToString(ReaderMarksDocument.serializer(), document)
        require(text.length <= ReaderMarksLimits.MaxChars) { "Marks are too large" }
        return text
    }

    fun decode(raw: String?): ReaderMarksDocument {
        if (raw.isNullOrBlank() || raw.length > ReaderMarksLimits.MaxChars) return ReaderMarksDocument()
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? kotlinx.serialization.json.JsonObject
            ?: return ReaderMarksDocument()
        val books = root["books"] as? kotlinx.serialization.json.JsonObject ?: return ReaderMarksDocument()
        return ReaderMarksDocument(books.mapNotNull { (id, value) ->
            if (id.isBlank()) return@mapNotNull null
            val marks = runCatching { json.decodeFromJsonElement(ReaderBookMarks.serializer(), value) }.getOrNull()
                ?: return@mapNotNull null
            val clean = ReaderBookMarks(
                bookmarks = marks.bookmarks.filter { it.id.isNotBlank() && it.chapter >= 0 && it.characterOffset >= 0 }
                    .distinctBy { it.id },
                highlights = marks.highlights.filter {
                    it.id.isNotBlank() && it.chapter >= 0 && it.start >= 0 && it.end > it.start
                }.distinctBy { it.id },
            )
            id to clean
        }.filter { !it.second.isEmpty }.toMap())
    }

    /** The minimal JSON the WebView needs to draw highlights, with no note or book text. */
    fun renderHighlights(highlights: List<ReaderHighlight>): String = buildString {
        append('[')
        highlights.forEachIndexed { index, highlight ->
            if (index > 0) append(',')
            append("{\"c\":").append(highlight.chapter)
            append(",\"s\":").append(highlight.start)
            append(",\"e\":").append(highlight.end)
            append(",\"t\":\"").append(highlight.tone.name.lowercase()).append("\"}")
        }
        append(']')
    }

    /** The minimal JSON the WebView needs to mark bookmarked pages. */
    fun renderBookmarks(bookmarks: List<ReaderBookmark>): String = buildString {
        append('[')
        bookmarks.forEachIndexed { index, bookmark ->
            if (index > 0) append(',')
            append("{\"c\":").append(bookmark.chapter)
            append(",\"o\":").append(bookmark.characterOffset)
            append(",\"p\":").append(bookmark.page)
            append('}')
        }
        append(']')
    }
}

/** The fields Add to Lightly puts in the Lightly confirm sheet. */
data class ReaderQuoteFields(val text: String, val author: String, val work: String)

/**
 * T236's pure rules. A stored range is chapter plus character offsets, so a font-size change only
 * clamps it; it never needs a page translation. New highlights merge with anything they overlap,
 * which keeps the rendered marks from nesting and keeps deletion unambiguous.
 */
object ReaderMarksMath {
    fun normalizedRange(length: Int, start: Int, end: Int): Pair<Int, Int>? {
        if (length <= 0) return null
        val from = start.coerceIn(0, length)
        val to = end.coerceIn(0, length)
        return if (to > from) from to to else null
    }

    /** Range mapping after a re-layout: the chapter length may have changed, the stored range does not. */
    fun rangeAfterLayout(highlight: ReaderHighlight, chapterLength: Int): IntRange? {
        val range = normalizedRange(chapterLength, highlight.start, highlight.end) ?: return null
        return range.first until range.second
    }

    fun snippet(text: String, start: Int, end: Int, max: Int = ReaderMarksLimits.MarksPanelSnippet): String {
        val range = normalizedRange(text.length, start, end) ?: return ""
        val raw = text.substring(range.first, range.second).trim().replace(Regex("\\s+"), " ")
        if (raw.length <= max) return raw
        return raw.take((max - 1).coerceAtLeast(1)).trimEnd() + "…"
    }

    fun bookmarkSnippet(text: String, offset: Int, max: Int = ReaderMarksLimits.MaxSnippet): String {
        if (text.isBlank()) return ""
        val from = offset.coerceIn(0, text.length)
        val raw = text.substring(from).trim().replace(Regex("\\s+"), " ")
        if (raw.length <= max) return raw
        return raw.take((max - 1).coerceAtLeast(1)).trimEnd() + "…"
    }

    fun toggleBookmark(bookmarks: List<ReaderBookmark>, candidate: ReaderBookmark): List<ReaderBookmark> {
        val existing = bookmarks.firstOrNull { sameSpot(it, candidate) }
        if (existing != null) return bookmarks.filterNot { it.id == existing.id }
        return (bookmarks + candidate).sortedWith(compareBy<ReaderBookmark> { it.page >= 0 }.thenBy { it.page }
            .thenBy { it.chapter }.thenBy { it.characterOffset })
    }

    fun sameSpot(a: ReaderBookmark, b: ReaderBookmark): Boolean =
        if (a.page >= 0 || b.page >= 0) a.page == b.page
        else a.chapter == b.chapter && a.characterOffset == b.characterOffset

    fun hasBookmark(bookmarks: List<ReaderBookmark>, chapter: Int, offset: Int, page: Int): Boolean =
        bookmarks.any { if (it.page >= 0) it.page == page else it.chapter == chapter && it.characterOffset == offset }

    fun onPage(bookmarks: List<ReaderBookmark>, page: Int): Boolean = bookmarks.any { it.page == page }

    fun bookmarkForText(id: String, position: BookPosition, text: String, createdAt: Long): ReaderBookmark =
        ReaderBookmark(id = id, chapter = position.chapter, characterOffset = position.characterOffset,
            page = -1, snippet = bookmarkSnippet(text, position.characterOffset), createdAt = createdAt)

    fun bookmarkForPdf(id: String, page: Int, createdAt: Long): ReaderBookmark =
        ReaderBookmark(id = id, chapter = 0, characterOffset = 0, page = page.coerceAtLeast(0),
            snippet = "Page ${page.coerceAtLeast(0) + 1}", createdAt = createdAt)

    /** Merge an added highlight with every range it overlaps or touches. */
    fun merge(highlights: List<ReaderHighlight>, incoming: ReaderHighlight): List<ReaderHighlight> {
        val validIncoming = incoming.copy(
            start = incoming.start.coerceAtLeast(0),
            end = incoming.end.coerceAtLeast(incoming.start + 1),
            text = incoming.text.take(ReaderMarksLimits.MaxSelectedText),
            note = incoming.note.take(ReaderMarksLimits.MaxNote),
        )
        val touching = highlights.filter {
            it.chapter == validIncoming.chapter && it.start <= validIncoming.end && validIncoming.start <= it.end
        }
        if (touching.isEmpty()) return (highlights + validIncoming).sortedWith(compareBy<ReaderHighlight> { it.chapter }
            .thenBy { it.start }.thenBy { it.end })
        val start = (touching + validIncoming).minOf { it.start }
        val end = (touching + validIncoming).maxOf { it.end }
        val merged = validIncoming.copy(start = start, end = end)
        return (highlights.filterNot { it in touching } + merged)
            .sortedWith(compareBy<ReaderHighlight> { it.chapter }.thenBy { it.start }.thenBy { it.end })
    }

    fun addHighlight(highlights: List<ReaderHighlight>, highlight: ReaderHighlight): List<ReaderHighlight> =
        merge(highlights, highlight)

    fun removeHighlight(highlights: List<ReaderHighlight>, id: String): List<ReaderHighlight> =
        highlights.filterNot { it.id == id }

    fun removeBookmark(bookmarks: List<ReaderBookmark>, id: String): List<ReaderBookmark> =
        bookmarks.filterNot { it.id == id }

    fun setNote(highlights: List<ReaderHighlight>, id: String, note: String): List<ReaderHighlight> =
        highlights.map { if (it.id == id) it.copy(note = note.take(ReaderMarksLimits.MaxNote)) else it }

    /** Build the fields for the Lightly confirm sheet; the text is trimmed and capped at 2,000 chars. */
    fun quoteFields(bookTitle: String, bookAuthor: String, selectedText: String): ReaderQuoteFields =
        ReaderQuoteFields(
            text = selectedText.trim().take(ReaderMarksLimits.MaxQuote),
            author = bookAuthor.trim().take(240),
            work = bookTitle.trim().take(240),
        )

    /** Text for a new highlight, capped so one huge selection cannot fill the marks document. */
    fun highlightText(text: String): String = text.take(ReaderMarksLimits.MaxSelectedText)

    /** Used by the PDF Marks panel to say what is possible without a text layer. */
    const val PdfMarksExplanation = "Bookmarks only for PDF. Highlights are available in EPUB and text books."

    /** A quiet name for each tone, used by TalkBack and the selection menu. */
    fun toneLabel(tone: ReaderHighlightTone): String = when (tone) {
        ReaderHighlightTone.Sand -> "Sand"
        ReaderHighlightTone.Sage -> "Sage"
        ReaderHighlightTone.Rose -> "Rose"
    }
}

/** Exposed here so the reader and tests agree on the document cap with the backup spec. */
val ReaderMarksMaxChars: Int get() = ReaderMarksLimits.MaxChars

