package com.brightblu.parc.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brightblu.parc.core.data.library.BookPosition
import com.brightblu.parc.core.data.library.ReaderBookDoc
import com.brightblu.parc.core.data.library.ReaderPdfMath
import com.brightblu.parc.core.data.library.ReaderPositionMath
import com.brightblu.parc.core.data.library.ReaderTocEntry
import com.brightblu.parc.model.Metrics
import kotlin.math.roundToInt

/** T344: what the bottom panel counts. Text books move by percent; fixed pages move by page. */
internal enum class ReaderJumpKind { Percent, Page }

/** T344: the order Back unwinds the reader, keyboard first and the book last. */
internal enum class ReaderBack { HideKeyboard, CloseSheet, ReopenList, HidePanel, ExitReader }

internal object ReaderJump {
    fun isSinglePage(kind: ReaderJumpKind, pageCount: Int): Boolean =
        kind == ReaderJumpKind.Page && pageCount <= 1

    fun percentForFraction(fraction: Float): Int =
        (fraction.coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100)

    fun fractionForPercent(percent: Int): Float = percent.coerceIn(0, 100) / 100f

    fun fractionForPage(page: Int, pageCount: Int): Float {
        val count = pageCount.coerceAtLeast(1)
        if (count == 1) return 0f
        return page.coerceIn(0, count - 1).toFloat() / (count - 1).toFloat()
    }

    fun pageForFraction(fraction: Float, pageCount: Int): Int =
        ReaderPdfMath.pageForFraction(fraction, pageCount)

    /** EPUB: a percentage of the whole text mapped to a chapter and character. */
    fun positionAt(doc: ReaderBookDoc, fraction: Float): BookPosition =
        ReaderPositionMath.positionAt(doc, fraction.coerceIn(0f, 1f).toDouble())

    fun positionFraction(doc: ReaderBookDoc, position: BookPosition): Float =
        ReaderPositionMath.progress(doc, position).toFloat()

    /** The line under the slider. EPUB names the chapter; PDF names the page. */
    fun panelLabel(kind: ReaderJumpKind, doc: ReaderBookDoc?, position: BookPosition,
        pageCount: Int): String = when (kind) {
        ReaderJumpKind.Percent -> {
            val title = doc?.chapters?.getOrNull(position.chapter)?.title.orEmpty()
            val percent = doc?.let { ReaderPositionMath.progressPercent(it, position) } ?: 0
            if (title.isBlank()) "$percent% read" else "$title · $percent% read"
        }
        ReaderJumpKind.Page -> {
            if (pageCount <= 1) "Only page"
            else "Page ${position.page.coerceIn(0, pageCount - 1) + 1} of $pageCount · " +
                "${(ReaderPdfMath.progress(position, pageCount) * 100).roundToInt()}% read"
        }
    }

    /**
     * T350: the reflowable panel. The page is one column of the reader's own layout, and the
     * chapter name and number sit beside it. While the columns are still being counted the label
     * says so instead of claiming a final total.
     */
    fun pagePanelLabel(doc: ReaderBookDoc?, position: BookPosition, page: Int, pageCount: Int,
        counting: Boolean): String {
        val safeTotal = pageCount.coerceAtLeast(1)
        val safePage = page.coerceIn(0, safeTotal - 1) + 1
        val pageText = if (safeTotal <= 1) "Only page" else "Page $safePage of $safeTotal"
        if (counting) return "$pageText · counting pages…"
        val chapter = chapterLabel(doc, position.chapter)
        return if (chapter.isBlank()) pageText else "$pageText · $chapter"
    }

    /** T350: "Chapter 3 · The Lift", without repeating a title that already says Chapter 3. */
    fun chapterLabel(doc: ReaderBookDoc?, chapter: Int): String {
        val number = "Chapter ${chapter.coerceAtLeast(0) + 1}"
        val title = doc?.chapters?.getOrNull(chapter)?.title?.trim().orEmpty()
        return when {
            title.isBlank() -> number
            // Some EPUBs carry the literal placeholder "Unknown" in their contents index.
            title.equals("Unknown", ignoreCase = true) -> number
            title.equals("Untitled", ignoreCase = true) -> number
            title.startsWith("Chapter", ignoreCase = true) -> title
            else -> "$number · $title"
        }
    }

    /** T350: the tiny corner label while the bars are hidden. */
    fun pageBadgeLabel(page: Int, pageCount: Int): String {
        val total = pageCount.coerceAtLeast(1)
        val safe = page.coerceIn(0, total - 1) + 1
        return if (total <= 1) "Only page" else "Page $safe of $total"
    }

    /** T350: the bookmark row's page, from the same zero-based column index as the reader. */
    fun bookmarkLabel(page: Int?): String = if (page == null) "Bookmark" else "Bookmark · Page ${page + 1}"

    /** The bubble that follows the finger. Never changes the saved place by itself. */
    fun preview(kind: ReaderJumpKind, fraction: Float, doc: ReaderBookDoc?, pageCount: Int): String =
        when (kind) {
            ReaderJumpKind.Percent -> {
                val safe = fraction.coerceIn(0f, 1f)
                val percent = percentForFraction(safe)
                val title = doc?.let { positionAt(it, safe).chapter }
                    ?.let { doc.chapters.getOrNull(it)?.title }.orEmpty()
                if (title.isBlank()) "$percent%" else "$percent% · $title"
            }
            ReaderJumpKind.Page -> {
                val page = pageForFraction(fraction, pageCount)
                if (pageCount <= 1) "Only page" else "Page ${page + 1} of $pageCount"
            }
        }

    /** The number on the button that opens direct entry. */
    fun entryText(kind: ReaderJumpKind, fraction: Float, pageCount: Int): String =
        when (kind) {
            ReaderJumpKind.Percent -> "${percentForFraction(fraction)}%"
            ReaderJumpKind.Page -> "${pageForFraction(fraction, pageCount) + 1}"
        }

    fun entryTitle(kind: ReaderJumpKind): String =
        if (kind == ReaderJumpKind.Percent) "Go to percent" else "Go to page"

    fun entryHint(kind: ReaderJumpKind, pageCount: Int): String =
        if (kind == ReaderJumpKind.Percent) "0 to 100" else "1 to ${pageCount.coerceAtLeast(1)}"

    /**
     * Direct numeric entry. Accepts a trailing percent sign for percent, and only whole numbers in
     * range. Anything else is null, so the field can explain the range instead of jumping.
     */
    fun parseEntry(kind: ReaderJumpKind, raw: String, pageCount: Int): Float? {
        val text = raw.trim().removeSuffix("%").trim()
        if (text.isEmpty() || text.length > 6) return null
        val number = text.toIntOrNull() ?: return null
        return when (kind) {
            ReaderJumpKind.Percent -> if (number in 0..100) number / 100f else null
            ReaderJumpKind.Page -> {
                val count = pageCount.coerceAtLeast(1)
                if (number in 1..count) fractionForPage(number - 1, count) else null
            }
        }
    }

    /** An accessibility range value (0 to 100, or page 1 to N) back to a seek fraction. */
    fun rangeFraction(kind: ReaderJumpKind, value: Float, pageCount: Int): Float =
        when (kind) {
            ReaderJumpKind.Percent -> (value / 100f).coerceIn(0f, 1f)
            ReaderJumpKind.Page -> fractionForPage(value.roundToInt() - 1, pageCount)
        }

    /** One small step: a page for fixed pages, one percent for reflowable text. */
    fun step(kind: ReaderJumpKind, fraction: Float, steps: Int, pageCount: Int): Float =
        when (kind) {
            ReaderJumpKind.Percent ->
                (fraction.coerceIn(0f, 1f) + steps * Metrics.readerJumpPercentStep).coerceIn(0f, 1f)
            ReaderJumpKind.Page ->
                fractionForPage(pageForFraction(fraction, pageCount) + steps, pageCount)
        }

    /** A chapter step, or null at either end. The panel disables the button it cannot use. */
    fun chapterStep(chapter: Int, delta: Int, chapterCount: Int): Int? {
        if (chapterCount <= 0) return null
        val target = chapter + delta
        return if (target in 0 until chapterCount) target else null
    }

    /** The Contents row to mark: the last heading at or before the reading position. */
    fun contentsIndex(entries: List<ReaderTocEntry>, position: BookPosition): Int {
        if (entries.isEmpty()) return -1
        var found = -1
        for (index in entries.indices) {
            val entry = entries[index]
            val before = entry.chapter < position.chapter ||
                (entry.chapter == position.chapter && entry.offset <= position.characterOffset)
            if (before) found = index
        }
        return found
    }

    fun backAction(keyboard: Boolean, sheet: Boolean, returnList: Boolean, panel: Boolean): ReaderBack =
        when {
            keyboard -> ReaderBack.HideKeyboard
            sheet -> ReaderBack.CloseSheet
            returnList -> ReaderBack.ReopenList
            panel -> ReaderBack.HidePanel
            else -> ReaderBack.ExitReader
        }
}

/**
 * T344: drag state kept outside the drawing code. A drag only changes [draft]; the reading place
 * moves when [release] returns a target. A cancelled gesture restores the committed value.
 */
internal class ReaderJumpSliderState {
    var committed by mutableFloatStateOf(0f)
        private set
    var draft by mutableFloatStateOf(0f)
        private set
    var dragging by mutableStateOf(false)
        private set
    var touched by mutableStateOf(false)
        private set

    val shown: Float get() = if (dragging) draft else committed

    fun follow(fraction: Float) {
        if (dragging) return
        committed = fraction.coerceIn(0f, 1f)
        draft = committed
    }

    fun press(fraction: Float) {
        dragging = true
        touched = true
        draft = fraction.coerceIn(0f, 1f)
    }

    fun move(fraction: Float) {
        if (dragging) draft = fraction.coerceIn(0f, 1f)
    }

    /** The seek target, or null when no drag was in progress. */
    fun release(): Float? {
        if (!dragging) return null
        dragging = false
        return draft
    }

    fun cancel() {
        dragging = false
        draft = committed
    }

    fun resetTouch() {
        touched = false
    }
}
