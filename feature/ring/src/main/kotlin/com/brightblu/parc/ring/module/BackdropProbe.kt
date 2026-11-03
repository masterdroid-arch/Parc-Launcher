package com.brightblu.parc.ring.module

import android.graphics.Bitmap
import com.brightblu.parc.model.Backdrop

/**
 * T172: what is behind a card, read from the wallpaper picture the frost uses. A grid of soft cells (each the average of a
 * few taps) stands in for the blurred pixels, so its darkest and lightest cell match what the frost shows. About 1 600
 * pixel reads, run when the picture or the card place changes, never per frame.
 */
object BackdropProbe {
    private const val Cells = 10
    private const val Taps = 4

    /** [left], [top], [width], [height] are in screen pixels; the [stage] picture may be smaller than the screen. */
    fun measure(stage: Bitmap, screenWidthPx: Int, screenHeightPx: Int, left: Int, top: Int, width: Int, height: Int): Backdrop {
        val sx = stage.width / screenWidthPx.coerceAtLeast(1).toFloat()
        val sy = stage.height / screenHeightPx.coerceAtLeast(1).toFloat()
        val x0 = (left * sx).coerceIn(0f, stage.width - 1f)
        val y0 = (top * sy).coerceIn(0f, stage.height - 1f)
        val w = (width * sx).coerceIn(1f, stage.width - x0)
        val h = (height * sy).coerceIn(1f, stage.height - y0)
        val cells = IntArray(Cells * Cells)
        for (cy in 0 until Cells) for (cx in 0 until Cells) {
            var r = 0; var g = 0; var b = 0
            for (ty in 0 until Taps) for (tx in 0 until Taps) {
                val x = (x0 + (cx + (tx + 0.5f) / Taps) * w / Cells).toInt().coerceIn(0, stage.width - 1)
                val y = (y0 + (cy + (ty + 0.5f) / Taps) * h / Cells).toInt().coerceIn(0, stage.height - 1)
                val p = stage.getPixel(x, y)
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            val n = Taps * Taps
            cells[cy * Cells + cx] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return Backdrop.of(cells)
    }
}
