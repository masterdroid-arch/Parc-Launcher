package com.brightblu.parc.core.data.library

import android.graphics.Bitmap

/**
 * T346: decides whether a rendered first page is really artwork. A PDF whose first page is a blank
 * sheet produced a featureless white tile on the shelf; that page is no cover, so the Library draws
 * its typographic cover (title and file type) instead. The colour test is pure and unit-tested; the
 * bitmap sampler runs only inside the loader's background work.
 */
object CoverArt {
    /** A channel at or above this value counts as bare paper. */
    const val LightChannel = 244

    /** Nearly all of the page must be bare paper; one line of print is enough to keep a real cover. */
    const val BlankShare = 0.995

    /** At most this many rows are read, so the check stays cheap on a large page. */
    const val MaxSampleRows = 64

    fun isBlank(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        var light = 0
        for (pixel in pixels) {
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            if (red >= LightChannel && green >= LightChannel && blue >= LightChannel) light++
        }
        return light.toDouble() / pixels.size >= BlankShare
    }

    fun isBlank(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return false
        val step = (height / MaxSampleRows).coerceAtLeast(1)
        val row = IntArray(width)
        var light = 0L
        var total = 0L
        var y = 0
        while (y < height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (pixel in row) {
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                if (red >= LightChannel && green >= LightChannel && blue >= LightChannel) light++
            }
            total += width
            y += step
        }
        return total > 0 && light.toDouble() / total >= BlankShare
    }
}
