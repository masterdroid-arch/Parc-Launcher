package com.brightblu.parc.core.data.library

import com.brightblu.parc.model.Metrics
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** How a PDF moves: one page at a time, or one continuous vertical strip. */
enum class PdfLayout { Page, Continuous }

/** A page size in pixels. Used for the rendered bitmap and for the raw PDF page. */
data class PdfRenderSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0)
    }
}

/**
 * T208: the pure side of the PDF reader. The renderer, bitmaps and gestures live in the app
 * module; the render window, page geometry and cache limits can be tested without Android.
 */
object ReaderPdfMath {
    /** Positions are page + fraction of that page; a PDF has no chapters or character offsets. */
    fun clamp(position: BookPosition, pageCount: Int): BookPosition {
        val count = pageCount.coerceAtLeast(1)
        val page = position.page.coerceIn(0, count - 1)
        val offset = if (position.pageOffset.isFinite()) position.pageOffset.coerceIn(0.0, 1.0) else 0.0
        return BookPosition(chapter = 0, characterOffset = 0, page = page, pageOffset = offset)
    }

    /** 0 at the first page, 1 at the last page; the in-page offset fills the gaps between them. */
    fun progress(position: BookPosition, pageCount: Int): Double {
        val count = pageCount.coerceAtLeast(1)
        val safe = clamp(position, count)
        if (safe.page >= count - 1) return 1.0
        return ((safe.page + safe.pageOffset) / (count - 1)).coerceIn(0.0, 1.0)
    }

    fun same(a: BookPosition?, b: BookPosition?, tolerance: Double = 0.01): Boolean =
        a != null && b != null && a.page == b.page && kotlin.math.abs(a.pageOffset - b.pageOffset) <= tolerance

    /**
     * Render at the screen width, but never taller than [maxHeight]. Very tall pages get a smaller
     * width. T235 adds [scale] for a zoomed re-render; the result never exceeds
     * [Metrics.pdfMaxRenderPixels], so a 5x gesture cannot allocate an unbounded bitmap.
     */
    fun renderSize(pageWidth: Int, pageHeight: Int, viewportWidth: Int, maxHeight: Int,
        scale: Float = 1f): PdfRenderSize {
        val rawWidth = pageWidth.coerceAtLeast(1)
        val rawHeight = pageHeight.coerceAtLeast(1)
        val safeScale = if (scale.isFinite()) scale.coerceAtLeast(1f) else 1f
        val width = (viewportWidth.coerceAtLeast(1) * safeScale).roundToInt().coerceAtLeast(1)
        val heightLimit = (maxHeight.coerceAtLeast(1) * safeScale).roundToInt().coerceAtLeast(1)
        var height = (width.toDouble() * rawHeight / rawWidth).roundToInt().coerceAtLeast(1)
        var actualWidth = width
        if (height > heightLimit) {
            height = heightLimit
            actualWidth = (height.toDouble() * rawWidth / rawHeight).roundToInt().coerceAtLeast(1)
        }
        val pixels = actualWidth.toLong() * height.toLong()
        val budget = Metrics.pdfMaxRenderPixels.toLong()
        if (pixels > budget) {
            val factor = sqrt(budget.toDouble() / pixels.toDouble())
            actualWidth = (actualWidth * factor).roundToInt().coerceAtLeast(1)
            height = (height * factor).roundToInt().coerceAtLeast(1)
        }
        return PdfRenderSize(actualWidth, height)
    }

    /** T235: how sharp the next render should be for a settled zoom value. */
    fun renderScaleForZoom(zoom: Float): Float =
        if (zoom.isFinite()) zoom.coerceIn(1f, Metrics.pdfMaxZoom) else 1f

    /** The displayed height for a page when it fills a width, before zoom. */
    fun displayHeight(pageWidth: Int, pageHeight: Int, width: Int): Int =
        (width.coerceAtLeast(1).toDouble() * pageHeight.coerceAtLeast(1) / pageWidth.coerceAtLeast(1))
            .roundToInt().coerceAtLeast(1)

    fun bytes(size: PdfRenderSize): Int = size.width * size.height * 4

    /**
     * How many bitmaps may live in the cache. Always keeps at least [minPages] so the current page
     * and a neighbour are never both dropped; never exceeds [maxPages].
     */
    fun cacheLimit(bytesPerPage: Int, budgetBytes: Int, minPages: Int, maxPages: Int): Int {
        val minimum = minPages.coerceAtLeast(1)
        val maximum = maxPages.coerceAtLeast(minimum)
        if (bytesPerPage <= 0 || budgetBytes <= 0) return minimum
        return (budgetBytes / bytesPerPage).coerceIn(minimum, maximum)
    }

    /** The current page plus its nearest neighbours, used by page-by-page mode. */
    fun pageTargets(current: Int, pageCount: Int, maxPages: Int): List<Int> {
        val count = pageCount.coerceAtLeast(1)
        val keep = maxPages.coerceAtLeast(1)
        val center = current.coerceIn(0, count - 1)
        val pages = mutableListOf(center)
        var distance = 1
        while (pages.size < keep && (center - distance >= 0 || center + distance < count)) {
            if (center - distance >= 0) pages += center - distance
            if (pages.size < keep && center + distance < count) pages += center + distance
            distance++
        }
        return pages
    }

    /** The pages that intersect the viewport, with optional overscan for pre-rendering. */
    fun visiblePages(
        tops: List<Float>,
        heights: List<Float>,
        scroll: Float,
        viewport: Float,
        overscan: Int = 0,
    ): List<Int> {
        if (tops.isEmpty() || tops.size != heights.size || viewport <= 0f) return emptyList()
        val safeScroll = scroll.coerceAtLeast(0f)
        val bottom = safeScroll + viewport
        var first = -1
        var last = -1
        for (index in tops.indices) {
            val top = tops[index]
            val height = heights[index].coerceAtLeast(0f)
            if (top + height > safeScroll && top < bottom) {
                if (first < 0) first = index
                last = index
            }
        }
        if (first < 0) {
            // A gap between pages: keep the nearest page so the strip never goes blank while settling.
            val nearest = tops.indices.minByOrNull { kotlin.math.abs(tops[it] - safeScroll) } ?: return emptyList()
            first = nearest
            last = nearest
        }
        val extra = overscan.coerceAtLeast(0)
        val from = (first - extra).coerceAtLeast(0)
        val to = (last + extra).coerceAtMost(tops.lastIndex)
        return (from..to).toList()
    }

    /** The saved page plus in-page fraction for a scroll offset in the continuous strip. */
    fun positionAt(tops: List<Float>, heights: List<Float>, scroll: Float): BookPosition {
        if (tops.isEmpty() || tops.size != heights.size) return BookPosition()
        val safeScroll = scroll.coerceAtLeast(0f)
        for (index in tops.indices) {
            val top = tops[index]
            val height = heights[index].coerceAtLeast(0f)
            if (safeScroll < top + height) {
                val offset = if (height > 0f) ((safeScroll - top) / height).coerceIn(0f, 1f).toDouble() else 0.0
                return BookPosition(page = index, pageOffset = offset)
            }
        }
        val last = tops.lastIndex
        val height = heights[last].coerceAtLeast(0f)
        val offset = if (height > 0f) ((safeScroll - tops[last]) / height).coerceIn(0f, 1f).toDouble() else 0.0
        return BookPosition(page = last, pageOffset = offset)
    }

    /** The inverse of [positionAt], used when opening or restoring a book. */
    fun scrollFor(tops: List<Float>, heights: List<Float>, page: Int, pageOffset: Double): Float {
        if (tops.isEmpty() || tops.size != heights.size) return 0f
        val index = page.coerceIn(0, tops.lastIndex)
        val offset = if (pageOffset.isFinite()) pageOffset.coerceIn(0.0, 1.0) else 0.0
        return (tops[index] + heights[index].coerceAtLeast(0f) * offset.toFloat()).coerceAtLeast(0f)
    }

    /** Scrubber position 0..1 to a page number. */
    fun pageForFraction(fraction: Float, pageCount: Int): Int {
        val count = pageCount.coerceAtLeast(1)
        if (count == 1) return 0
        return (fraction.coerceIn(0f, 1f) * (count - 1)).roundToInt().coerceIn(0, count - 1)
    }

    /** The default render height cap is part of the card's memory policy, not a UI choice. */
    fun maxRenderHeight(viewportHeight: Int): Int =
        (viewportHeight.coerceAtLeast(1) * Metrics.pdfMaxRenderHeightFactor).roundToInt().coerceAtLeast(1)

    /** T235: the pan range of a zoomed page. Zero at 1x, larger with zoom. */
    data class PdfPan(val x: Float, val y: Float) {
        val isZero: Boolean get() = x == 0f && y == 0f
    }

    /**
     * Horizontal pan is always allowed; vertical pan is used only in page-by-page mode (the
     * continuous strip scrolls instead). Both axes are measured from the centre.
     */
    fun panLimit(viewportWidth: Int, viewportHeight: Int, pageHeight: Float, zoom: Float,
        vertical: Boolean = true): PdfPan {
        val z = renderScaleForZoom(zoom)
        val maxX = ((viewportWidth.coerceAtLeast(1) * z - viewportWidth.coerceAtLeast(1)) / 2f)
            .coerceAtLeast(0f)
        val maxY = if (vertical) {
            ((pageHeight.coerceAtLeast(1f) * z - viewportHeight.coerceAtLeast(1)) / 2f).coerceAtLeast(0f)
        } else 0f
        return PdfPan(maxX, maxY)
    }

    fun clampPan(x: Float, y: Float, limit: PdfPan): PdfPan = PdfPan(
        x.coerceIn(-limit.x, limit.x).takeIf { it.isFinite() } ?: 0f,
        y.coerceIn(-limit.y, limit.y).takeIf { it.isFinite() } ?: 0f,
    )

    /** True when the page is already panned against the edge the finger is pushing past. */
    fun atHorizontalEdge(panX: Float, limit: PdfPan, direction: Int): Boolean = when {
        limit.x <= 0f -> false
        direction > 0 -> panX >= limit.x - Metrics.pdfPanEdgeSlopPx
        direction < 0 -> panX <= -limit.x + Metrics.pdfPanEdgeSlopPx
        else -> false
    }

    /**
     * T235: while zoomed, a page turns only when the finger keeps pushing past a panned edge.
     * [dragPastEdge] is the part of the horizontal drag the pan clamp refused. Returns the target
     * page number, or null when the gesture is only a pan.
     */
    fun zoomedEdgeTurn(current: Int, pageCount: Int, dragPastEdge: Float, panX: Float,
        limit: PdfPan, velocityX: Float, viewportWidth: Int): Int? {
        if (limit.x <= 0f || dragPastEdge == 0f) return null
        val direction = if (dragPastEdge > 0f) 1 else -1
        if (!atHorizontalEdge(panX, limit, direction)) return null
        val passed = abs(dragPastEdge) >= viewportWidth.coerceAtLeast(1) * Metrics.readerPageTurnFraction
        val flick = abs(velocityX) >= Metrics.readerPageFlingPxPerSec && velocityX * dragPastEdge > 0f
        if (!passed && !flick) return null
        return when (direction) {
            1 -> turnTarget(current, pageCount, -1)
            else -> turnTarget(current, pageCount, 1)
        }
    }

    /** A page turn target clamped to the document. */
    fun turnTarget(current: Int, pageCount: Int, step: Int): Int {
        val count = pageCount.coerceAtLeast(1)
        return (current.coerceIn(0, count - 1) + step).coerceIn(0, count - 1)
    }
}
