package com.brightblu.parc.core.data.library

import kotlin.math.roundToInt

/** Whole-image fit in physical pixels. A small source is replaced by a title, never enlarged. */
object BookCoverSizing {
    data class Size(val width: Int, val height: Int)

    fun fit(width: Int, height: Int, boxWidth: Int, boxHeight: Int): Size {
        require(width > 0 && height > 0 && boxWidth > 0 && boxHeight > 0)
        val scale = minOf(boxWidth.toDouble() / width, boxHeight.toDouble() / height)
        return Size((width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1))
    }

    fun sharpEnough(width: Int, height: Int, boxWidth: Int, boxHeight: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val fitted = fit(width, height, boxWidth, boxHeight)
        return width >= fitted.width && height >= fitted.height
    }
}
