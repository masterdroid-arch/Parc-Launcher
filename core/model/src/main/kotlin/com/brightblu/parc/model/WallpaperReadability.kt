package com.brightblu.parc.model

import kotlin.math.pow

/** WCAG sRGB maths. Only called when wallpaper, settings or skin changes. */
object WallpaperReadability {
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color ushr shift) and 255) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    fun estimate(colors: List<Int>, darkTextHint: Boolean): Double? =
        if (colors.isEmpty()) null else colors.map(::luminance).average().let { if (darkTextHint) maxOf(it, .5) else it }
    fun composite(surface: Int, background: Int, alpha: Float): Int {
        fun c(shift: Int) = ((((surface ushr shift) and 255) * alpha + ((background ushr shift) and 255) * (1f - alpha)).toInt()).coerceIn(0, 255)
        return (255 shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }
    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
    /** Both extremes bound any wallpaper pixel, including a highlight missed by colour hints. */
    fun safeAlpha(surface: Int, texts: List<Int>, requested: Float): Float {
        for (step in kotlin.math.ceil((requested.coerceIn(0f, 1f) * 255).toDouble()).toInt()..255) {
            val alpha = step / 255f
            val low = composite(surface, 0xff000000.toInt(), alpha)
            val high = composite(surface, 0xffffffff.toInt(), alpha)
            if (texts.all { text ->
                val l = luminance(text)
                (l <= luminance(low) || l >= luminance(high)) && contrast(text, low) >= 4.5 && contrast(text, high) >= 4.5
            }) return alpha
        }
        return 1f
    }
}
