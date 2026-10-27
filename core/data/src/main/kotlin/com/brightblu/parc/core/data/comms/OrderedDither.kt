package com.brightblu.parc.core.data.comms

/** Integer size, used by the image pipeline without pulling an Android type into the maths. */
data class PixelSize(val width: Int, val height: Int)

/**
 * Pure ordered dither (T55). Converts ARGB pixels to a 1-bit [CommsImage] using an 8x8 Bayer
 * matrix, so a thumbnail keeps a readable gradient without greys or a full-size bitmap.
 *
 * The rule is deliberately simple and unit-tested: a pixel is ink when its alpha-weighted
 * luminance is greater than the matrix threshold at that position. Fully transparent pixels are
 * never ink, white is always ink and black is never ink.
 *
 * Scaling is separate ([targetSize]) so the Android half only has to hand over pixels; no Android
 * type appears in this file.
 */
object OrderedDither {

    /** 8x8 Bayer matrix, values 0..63. */
    private val Bayer = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42,
        48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38,
        60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41,
        51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37,
        63, 31, 55, 23, 61, 29, 53, 21,
    )

    /**
     * Fits [width] x [height] inside [maxSide] while preserving the aspect ratio. Never upscales:
     * a 16x8 image stays 16x8. Every returned side is at least 1 for a positive input.
     */
    fun targetSize(width: Int, height: Int, maxSide: Int = CommsImage.MaxSide): PixelSize {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        require(maxSide > 0) { "maxSide must be positive" }
        val longest = maxOf(width, height)
        if (longest <= maxSide) return PixelSize(width, height)
        val scale = maxSide.toDouble() / longest
        val scaledWidth = (width * scale).toInt().coerceAtLeast(1)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        return PixelSize(scaledWidth, scaledHeight)
    }

    /**
     * Converts [argb] (row-major, [width] * [height] entries) to a packed 1-bit image. Alpha 0 is
     * treated as no ink; otherwise luminance is multiplied by alpha, so a translucent pixel is
     * darker than the same opaque colour.
     */
    fun fromPixels(argb: IntArray, width: Int, height: Int): CommsImage {
        require(width > 0 && height > 0) { "size must be positive" }
        require(width <= CommsImage.MaxSide && height <= CommsImage.MaxSide) {
            "image exceeds ${CommsImage.MaxSide} px"
        }
        require(argb.size == width * height) {
            "expected ${width * height} pixels, got ${argb.size}"
        }

        val words = LongArray(CommsImage.wordCountFor(width, height))
        var pixel = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (luminance(argb[pixel]) > threshold(x, y)) {
                    words[pixel ushr 6] = words[pixel ushr 6] or (1L shl (pixel and 63))
                }
                pixel++
            }
        }
        return CommsImage.fromWords(width, height, words.toList())
    }

    /** Threshold for one matrix cell, in the same 0..255 range as luminance. */
    fun threshold(x: Int, y: Int): Int =
        (Bayer[(y and 7) * 8 + (x and 7)] shl 2) + 2

    /** Alpha-weighted luminance of one ARGB pixel, 0..255. */
    fun luminance(argb: Int): Int {
        val alpha = (argb ushr 24) and 0xFF
        if (alpha == 0) return 0
        val red = (argb ushr 16) and 0xFF
        val green = (argb ushr 8) and 0xFF
        val blue = argb and 0xFF
        val opaque = (red * 299 + green * 587 + blue * 114) / 1000
        return opaque * alpha / 255
    }
}
