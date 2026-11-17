package com.brightblu.parc.core.data.library

import com.brightblu.parc.model.Metrics
import kotlin.math.abs
import kotlin.math.roundToInt

/** One spine item, already sanitised and safe to put in the reader's single in-memory HTML document. */
data class ReaderChapter(
    val index: Int,
    val title: String,
    val html: String,
    val text: String,
) {
    init {
        require(index >= 0 && title.isNotBlank())
        require(html.length <= Metrics.readerMaxDocumentChars)
        require(text.length <= Metrics.readerMaxDocumentChars)
    }
}

/** A contents row. [offset] is a character offset inside [chapter], never a page number. */
data class ReaderTocEntry(val title: String, val chapter: Int, val offset: Int = 0) {
    init {
        require(title.isNotBlank() && chapter >= 0 && offset >= 0)
    }
}

/** A parsed EPUB or plain-text book. Everything the reader needs is already in memory. */
data class ReaderBookDoc(
    val id: String,
    val title: String,
    val author: String,
    val chapters: List<ReaderChapter>,
    val toc: List<ReaderTocEntry>,
) {
    init {
        require(id.isNotBlank() && title.isNotBlank())
        require(chapters.isNotEmpty())
    }

    val totalChars: Int get() = chapters.sumOf { it.text.length }

    fun chapter(index: Int): ReaderChapter = chapters[index.coerceIn(0, chapters.lastIndex)]

    fun tocOrChapters(): List<ReaderTocEntry> = toc.ifEmpty {
        chapters.map { ReaderTocEntry(it.title, it.index, 0) }
    }

    companion object {
        /** A plain-text book is one chapter; the offset is a character offset in the file. */
        fun plainText(id: String, title: String, author: String, text: String): ReaderBookDoc {
            require(id.isNotBlank() && title.isNotBlank())
            val escaped = ReaderHtml.escape(text)
            return ReaderBookDoc(
                id = id,
                title = title,
                author = author,
                chapters = listOf(ReaderChapter(0, title, "<pre>$escaped</pre>", text)),
                toc = emptyList(),
            )
        }
    }
}

/** A malformed or unsupported book. The activity shows [message] in plain words. */
class ReaderBookException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Position maths. The stored position is chapter + character offset, so changing the font size,
 * line spacing, margins or rotation never changes it. Pure and tested.
 */
object ReaderPositionMath {
    fun clamp(doc: ReaderBookDoc, chapter: Int, offset: Int): BookPosition {
        val index = chapter.coerceIn(0, doc.chapters.lastIndex)
        val text = doc.chapters[index].text
        return BookPosition(chapter = index, characterOffset = offset.coerceIn(0, text.length))
    }

    fun clamp(doc: ReaderBookDoc, position: BookPosition): BookPosition =
        clamp(doc, position.chapter, position.characterOffset)

    /** A stable progress fraction for the shelf and the reader bar; 0 at the start, 1 at the end. */
    fun progress(doc: ReaderBookDoc, position: BookPosition): Double {
        val safe = clamp(doc, position)
        if (doc.totalChars <= 0) return 0.0
        val before = doc.chapters.take(safe.chapter).sumOf { it.text.length }
        val value = (before + safe.characterOffset).toDouble() / doc.totalChars
        return value.coerceIn(0.0, 1.0)
    }

    fun progressPercent(doc: ReaderBookDoc, position: BookPosition): Int =
        (progress(doc, position) * 100).roundToInt().coerceIn(0, 100)

    /** The closest character offset for a fraction. Used only by tests and the page progress bar. */
    fun positionAt(doc: ReaderBookDoc, fraction: Double): BookPosition {
        if (doc.totalChars <= 0) return BookPosition(0, 0)
        var left = (fraction.coerceIn(0.0, 1.0) * doc.totalChars).roundToInt()
        for (chapter in doc.chapters) {
            if (left <= chapter.text.length) return BookPosition(chapter.index, left)
            left -= chapter.text.length
        }
        return BookPosition(doc.chapters.lastIndex, doc.chapters.last().text.length)
    }

    /** True when two positions point at the same character (used to avoid duplicate saves). */
    fun same(a: BookPosition?, b: BookPosition?): Boolean =
        a != null && b != null && a.chapter == b.chapter && abs(a.characterOffset - b.characterOffset) <= 1
}
