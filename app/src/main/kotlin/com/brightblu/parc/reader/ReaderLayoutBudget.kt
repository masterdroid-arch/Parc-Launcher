package com.brightblu.parc.reader

import com.brightblu.parc.model.Metrics

/**
 * T343: the pure side of the bounded reflowable layout. The WebView owns the chunk elements and
 * their measured page counts; these rules say which chapters start in the document, how many
 * pages an unmeasured chunk is assumed to take, and where the measured average comes from.
 */
internal object ReaderLayoutBudget {
    /** The chapters placed in the first document: the saved one and [Metrics.readerChunkWindow]. */
    fun window(center: Int, chapterCount: Int): IntRange {
        if (chapterCount <= 0) return IntRange.EMPTY
        val last = chapterCount - 1
        val safe = center.coerceIn(0, last)
        val before = (Metrics.readerChunkWindow - 1) / 2
        val after = Metrics.readerChunkWindow - 1 - before
        return (safe - before).coerceAtLeast(0)..(safe + after).coerceAtMost(last)
    }

    /** Characters per page learned from chunks that have actually been laid out. */
    fun charsPerPage(measuredChars: Int, measuredPages: Int, fallback: Double = Metrics.readerCharsPerPageFallback): Double {
        if (measuredChars <= 0 || measuredPages <= 0) return fallback
        return measuredChars.toDouble() / measuredPages.toDouble()
    }

    /** A page estimate for text that has not been laid out yet, never less than one page. */
    fun estimatePages(chars: Int, charsPerPage: Double): Int {
        val rate = if (charsPerPage.isFinite() && charsPerPage > 0.0) charsPerPage
        else Metrics.readerCharsPerPageFallback
        if (chars <= 0) return 1
        return (chars / rate).toInt().coerceAtLeast(1)
    }

    /** A global page start from the chapter and chunk page counts known so far. */
    fun pageStart(chapterPages: List<Int>, chapter: Int, chunkPages: List<Int>, chunk: Int): Int {
        var start = 0
        for (index in 0 until chapter.coerceIn(0, chapterPages.size)) {
            start += chapterPages[index].coerceAtLeast(1)
        }
        for (index in 0 until chunk.coerceIn(0, chunkPages.size)) {
            start += chunkPages[index].coerceAtLeast(1)
        }
        return start
    }
}
