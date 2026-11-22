package com.brightblu.parc.reader

import com.brightblu.parc.model.Metrics
import kotlin.math.abs

/**
 * T210: the pure geometry and gesture thresholds behind the reader motion. No Android or Compose
 * types live here, so the cover-to-page interpolation, the pull-down decision and the page-turn
 * rules are unit-tested off the device.
 */
internal object ReaderMotion {
    /** A rectangle in window pixels. The reader and the shelf both use window coordinates. */
    data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = (right - left).coerceAtLeast(0f)
        val height: Float get() = (bottom - top).coerceAtLeast(0f)
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
        val isValid: Boolean
            get() = width > 0f && height > 0f &&
                left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()
    }

    val None = Rect(0f, 0f, 0f, 0f)

    fun full(width: Float, height: Float): Rect =
        Rect(0f, 0f, width.coerceAtLeast(0f), height.coerceAtLeast(0f))

    /** Reads the four cover extras defensively; a missing or inverted rectangle becomes null. */
    fun coverOrNull(left: Float, top: Float, right: Float, bottom: Float): Rect? =
        Rect(left, top, right, bottom).takeIf { it.isValid }

    fun lerp(from: Rect, to: Rect, fraction: Float): Rect {
        val t = fraction.coerceIn(0f, 1f)
        return Rect(
            from.left + (to.left - from.left) * t,
            from.top + (to.top - from.top) * t,
            from.right + (to.right - from.right) * t,
            from.bottom + (to.bottom - from.bottom) * t,
        )
    }

    /** The page starts at the cover and lands on the full screen; 0 returns the cover exactly. */
    fun coverToPage(cover: Rect, screen: Rect, fraction: Float): Rect = lerp(cover, screen, fraction)

    /**
     * The finger pulls the page down. 55 % of the screen height is a full pull, so a normal
     * downward gesture reaches the close threshold without crossing the whole display.
     */
    fun pullProgress(distancePx: Float, screenHeightPx: Float): Float {
        if (distancePx <= 0f || screenHeightPx <= 0f) return 0f
        return (distancePx / (screenHeightPx * Metrics.readerPullDistanceFraction)).coerceIn(0f, 1f)
    }

    /** A pull closes on distance, or on a quick downward flick even before the threshold. */
    fun shouldClose(progress: Float, velocityPxPerSec: Float): Boolean =
        progress >= Metrics.readerPullCloseFraction || velocityPxPerSec >= Metrics.readerPullFlingPxPerSec

    /** 1 means finish the close; 0 means spring back to the page. */
    fun pullTarget(progress: Float, velocityPxPerSec: Float): Float =
        if (shouldClose(progress, velocityPxPerSec)) 1f else 0f

    /** The cover is only shown near the end of a close, so the page itself is what follows the finger. */
    fun closeCoverAlpha(progress: Float): Float =
        ((progress - Metrics.readerCoverRevealStart) / (1f - Metrics.readerCoverRevealStart)).coerceIn(0f, 1f)

    /**
     * T341: the shelf cover may only fade once the reader has drawn the positioned first frame.
     * Being entered or a fixed hold is not enough: an unpositioned page (usually page one) must
     * never be revealed on resume.
     */
    fun coverMayFade(entered: Boolean, contentReady: Boolean, closing: Boolean): Boolean =
        entered && contentReady && !closing

    /** Open-with has no shelf cover: the page rises from below the bottom edge. */
    fun riseOffsetPx(screenHeightPx: Float, fraction: Float): Float =
        screenHeightPx.coerceAtLeast(0f) * (1f - fraction.coerceIn(0f, 1f))

    /**
     * The horizontal offset follows the finger. At the first page a right drag and at the last
     * page a left drag meet resistance instead of a hard stop.
     */
    fun pageDragOffset(distancePx: Float, pageWidthPx: Float, atFirst: Boolean, atLast: Boolean): Float {
        val width = pageWidthPx.coerceAtLeast(1f)
        val resisted = if ((atFirst && distancePx > 0f) || (atLast && distancePx < 0f)) {
            distancePx * Metrics.readerPageEdgeResistance
        } else distancePx
        return resisted.coerceIn(-width, width)
    }

    /**
     * Which page a gesture should settle on. A real flick wins over the distance threshold; a slow
     * drag turns once it passes 18 % of the page width. There is never a page before the first or
     * after the last.
     */
    fun pageTarget(
        currentPage: Int,
        pageCount: Int,
        dragPx: Float,
        velocityPxPerSec: Float,
        pageWidthPx: Float,
    ): Int {
        val count = pageCount.coerceAtLeast(1)
        val page = currentPage.coerceIn(0, count - 1)
        if (count <= 1) return 0
        val threshold = pageWidthPx.coerceAtLeast(1f) * Metrics.readerPageTurnFraction
        val direction = when {
            velocityPxPerSec <= -Metrics.readerPageFlingPxPerSec -> 1
            velocityPxPerSec >= Metrics.readerPageFlingPxPerSec -> -1
            dragPx <= -threshold -> 1
            dragPx >= threshold -> -1
            else -> 0
        }
        return (page + direction).coerceIn(0, count - 1)
    }

    /** Edge taps and the Contents list use the same clamp as a drag. */
    fun turnTarget(currentPage: Int, pageCount: Int, direction: Int): Int =
        (currentPage + direction).coerceIn(0, pageCount.coerceAtLeast(1) - 1)

    /**
     * The offset the page sits at before the commit. Turning forward puts the next page one width
     * to the left (negative); going back puts the previous page one width to the right.
     */
    fun pageSettleOffset(currentPage: Int, targetPage: Int, pageWidthPx: Float): Float =
        (currentPage - targetPage).toFloat() * pageWidthPx.coerceAtLeast(1f)

    /** True when a drag is too small and too slow to turn, so the page returns home. */
    fun returnsToCurrentPage(currentPage: Int, targetPage: Int): Boolean = currentPage == targetPage

    /**
     * Centre-crop source rectangle for the animated cover. Returns a new rectangle only for the
     * test/tool path; the draw path updates primitive RectF fields directly.
     */
    fun sourceCrop(imageWidth: Int, imageHeight: Int, targetWidth: Float, targetHeight: Float): Rect {
        val iw = imageWidth.coerceAtLeast(1).toFloat()
        val ih = imageHeight.coerceAtLeast(1).toFloat()
        val tw = targetWidth.coerceAtLeast(1f)
        val th = targetHeight.coerceAtLeast(1f)
        val imageAspect = iw / ih
        val targetAspect = tw / th
        return if (targetAspect > imageAspect) {
            val cropHeight = iw / targetAspect
            Rect(0f, (ih - cropHeight) / 2f, iw, (ih + cropHeight) / 2f)
        } else {
            val cropWidth = ih * targetAspect
            Rect((iw - cropWidth) / 2f, 0f, (iw + cropWidth) / 2f, ih)
        }
    }

    /** Used by the pull-down zone: a drag that starts near the top edge may close the reader. */
    fun startsInPullZone(y: Float, screenHeightPx: Float): Boolean =
        y >= 0f && y <= screenHeightPx.coerceAtLeast(0f) * Metrics.readerPullZoneFraction

    /** True when the horizontal movement is large enough that the gesture is a page turn, not a pull. */
    fun isHorizontalGesture(dx: Float, dy: Float, touchSlopPx: Float): Boolean =
        abs(dx) > touchSlopPx && abs(dx) > abs(dy)
}
