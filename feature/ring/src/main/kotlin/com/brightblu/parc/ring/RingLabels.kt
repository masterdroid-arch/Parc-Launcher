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

/** Folder captions use the real raster bounds; solve once, never in draw. */
internal class FolderLabels(
    xs: FloatArray, ys: FloatArray, widths: FloatArray, heights: FloatArray,
    folders: BooleanArray, screenWidth: Float, screenHeight: Float,
    iconSizeDp: Float = Metrics.iconDp,
) {
    val x = FloatArray(xs.size)
    val y = FloatArray(xs.size)
    init {
        val margin = Metrics.folderLabelScreenMarginDp
        val offset = iconSizeDp / 2f + Metrics.folderLabelGapDp
        // The clamp never falls below the margin, so a caption wider than the safe band still gets a
        // valid position instead of an empty range. A user-chosen aligned layout can place a folder
        // where the old strict assertion failed; clamping keeps the app alive and the caption visible.
        val maxX = max(margin, screenWidth - margin)
        val maxY = max(margin, screenHeight - margin)
        for (i in folders.indices) {
            if (!folders[i]) continue
            x[i] = min(max(xs[i] - widths[i] / 2f, margin), max(margin, maxX - widths[i]))
            val below = ys[i] + offset
            val chosen = if (below + heights[i] <= screenHeight - margin) below
                else ys[i] - offset - heights[i]
            y[i] = min(max(chosen, margin), max(margin, maxY - heights[i]))
        }
    }
}

internal fun folderCaption(name: String): String = if (name.length <= Metrics.folderLabelMaxChars) name
    else name.take(Metrics.folderLabelMaxChars - 1).trimEnd() + "…"
