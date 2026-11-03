package com.brightblu.parc.ring

import com.brightblu.parc.model.Metrics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Preparation-only placement for the single focused pill, including its padding. */
internal class RingLabels(
    xs: FloatArray, ys: FloatArray, widths: FloatArray, heights: FloatArray,
    originX: Float, originY: Float, screenWidth: Float, screenHeight: Float,
    iconSizeDp: Float = Metrics.iconDp,
) {
    val x = FloatArray(xs.size)
    val y = FloatArray(xs.size)
    init {
        for (i in xs.indices) {
            val placed = place(xs[i], ys[i], widths[i], heights[i], originX, originY, screenWidth, screenHeight, iconSizeDp)
            x[i] = placed.first
            y[i] = placed.second
        }
    }

    companion object {
        /**
         * One pill's position (top-left in dp) for an item centred at (xDp, yDp). Outward from the
         * hub, flipping inward when the outward side crosses a keep-out, then clamped to the screen.
         */
        fun place(xDp: Float, yDp: Float, widthDp: Float, heightDp: Float,
            originX: Float, originY: Float, screenWidth: Float, screenHeight: Float,
            iconSizeDp: Float = Metrics.iconDp): Pair<Float, Float> {
            val offset = iconSizeDp / 2f + Metrics.labelGapDp
            val left = Metrics.keepOutLeftDp
            val right = screenWidth - Metrics.keepOutRightDp
            val top = Metrics.keepOutTopDp
            val bottom = screenHeight - Metrics.keepOutBottomDp
            val dx = xDp - originX
            val dy = yDp - originY
            val horizontal = abs(dx) >= abs(dy)
            val outward = if ((if (horizontal) dx else dy) <= 0f) -1f else 1f
            fun place(side: Float): Pair<Float, Float> {
                val x = if (horizontal) {
                    if (side < 0f) xDp - offset - widthDp else xDp + offset
                } else xDp - widthDp / 2f
                val y = if (horizontal) yDp - heightDp / 2f else {
                    if (side < 0f) yDp - offset - heightDp else yDp + offset
                }
                return x to y
            }
            var result = place(outward)
            val (x, y) = result
            if (x < left || x + widthDp > right || y < top || y + heightDp > bottom) result = place(-outward)
            return result.first.coerceIn(left, right - widthDp) to result.second.coerceIn(top, bottom - heightDp)
        }
    }
}

/**
 * T170: folder captions. Each caption tries below the folder icon, above it, then right and left,
 * and takes the first spot that is inside the bounds (the card with its inset) and touches no other
 * visible icon rect and no caption already placed. Below/above shift sideways to stay inside; right/left
 * shift up or down. If every spot collides, the one with the least overlap wins. Allocation-free after
 * construction so the draw loop can call [solve] with live slot centres.
 */
internal class FolderLabelPlacer(capacity: Int) {
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    private var candX = 0f
    private var candY = 0f

    /**
     * Centres, caption sizes and bounds are in dp. [folder] marks slots that carry a caption, [visible]
     * the slots that are drawn (obstacles). The bounds are the area the whole caption must stay inside.
     */
    fun solve(cx: FloatArray, cy: FloatArray, w: FloatArray, h: FloatArray,
        folder: BooleanArray, visible: BooleanArray, n: Int, iconDp: Float,
        left: Float, top: Float, right: Float, bottom: Float) {
        val offset = iconDp / 2f + Metrics.folderLabelGapDp
        val maxX = max(left, right)
        val maxY = max(top, bottom)
        for (i in 0 until n) {
            if (!folder[i]) continue
            var bestSide = -1
            var bestOverlap = Float.MAX_VALUE
            var bestX = 0f
            var bestY = 0f
            // Level 0 is centred on the icon; each further level slides the caption along its edge, both ways.
            var step = 0
            while (step < SHIFT_STEPS * 2 - 1 && bestOverlap > 0f) {
                val shift = if (step == 0) 0f else (if (step % 2 == 1) 1f else -1f) * ((step + 1) / 2) * SHIFT_DP
                var side = 0
                while (side < 4 && bestOverlap > 0f) {
                    if (candidate(i, side, shift, cx, cy, w, h, offset, left, top, maxX, maxY)) {
                        val overlap = overlap(i, candX, candY, cx, cy, w, h, folder, visible, n, iconDp)
                        if (overlap < bestOverlap) { bestOverlap = overlap; bestSide = side; bestX = candX; bestY = candY }
                    }
                    side++
                }
                step++
            }
            if (bestSide < 0) {
                // No side fits the bounds at all: pin below, clamped, so the caption stays visible.
                x[i] = min(max(cx[i] - w[i] / 2f, left), max(left, maxX - w[i]))
                y[i] = min(max(cy[i] + offset, top), max(top, maxY - h[i]))
            } else { x[i] = bestX; y[i] = bestY }
        }
    }

    /** Fills [candX]/[candY] for [side]; false when the caption cannot sit inside the bounds there. */
    private fun candidate(i: Int, side: Int, shift: Float, cx: FloatArray, cy: FloatArray, w: FloatArray, h: FloatArray,
        offset: Float, left: Float, top: Float, maxX: Float, maxY: Float): Boolean {
        if (side < 2) {
            candX = min(max(cx[i] - w[i] / 2f + shift, left), max(left, maxX - w[i]))
            candY = if (side == 0) cy[i] + offset else cy[i] - offset - h[i]
        } else {
            candY = min(max(cy[i] - h[i] / 2f + shift, top), max(top, maxY - h[i]))
            candX = if (side == 2) cx[i] + offset else cx[i] - offset - w[i]
        }
        return candX >= left - EPS && candX + w[i] <= maxX + EPS && candY >= top - EPS && candY + h[i] <= maxY + EPS
    }

    /** Overlap area of caption [i] at ([px], [py]) with other visible icons and earlier captions. */
    private fun overlap(i: Int, px: Float, py: Float, cx: FloatArray, cy: FloatArray, w: FloatArray, h: FloatArray,
        folder: BooleanArray, visible: BooleanArray, n: Int, iconDp: Float): Float {
        var area = 0f
        val half = iconDp / 2f
        for (j in 0 until n) {
            if (j == i) continue
            if (visible[j]) area += intersection(px, py, px + w[i], py + h[i], cx[j] - half, cy[j] - half, cx[j] + half, cy[j] + half)
            if (j < i && folder[j]) area += intersection(px, py, px + w[i], py + h[i], x[j], y[j], x[j] + w[j], y[j] + h[j])
        }
        return area
    }

    private fun intersection(l1: Float, t1: Float, r1: Float, b1: Float, l2: Float, t2: Float, r2: Float, b2: Float): Float {
        val dx = min(r1, r2) - max(l1, l2)
        val dy = min(b1, b2) - max(t1, t2)
        return if (dx > 0f && dy > 0f) dx * dy else 0f
    }

    private companion object {
        const val EPS = 0.001f
        const val SHIFT_DP = 10f
        const val SHIFT_STEPS = 5
    }
}

internal fun folderCaption(name: String): String = if (name.length <= Metrics.folderLabelMaxChars) name
    else name.take(Metrics.folderLabelMaxChars - 1).trimEnd() + "…"
