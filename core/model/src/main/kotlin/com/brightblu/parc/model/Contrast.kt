package com.brightblu.parc.model

import kotlin.math.pow

/**
 * T221: WCAG sRGB contrast for skin colours. The skin stores ARGB values as unsigned-range Longs,
 * so this helper takes Longs and keeps the maths in one place for the settings and Messages text.
 */
fun contrastRatio(foreground: Long, background: Long): Double {
    fun channel(color: Long, shift: Int): Double {
        val c = ((color ushr shift) and 255L) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    fun luminance(color: Long): Double =
        .2126 * channel(color, 16) + .7152 * channel(color, 8) + .0722 * channel(color, 0)
    val a = luminance(foreground)
    val b = luminance(background)
    return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
}

/** Composite a translucent skin colour over what sits behind it; opaque colours pass through. */
fun compositeOver(foreground: Long, background: Long): Long {
    val alpha = ((foreground ushr 24) and 255L) / 255.0
    if (alpha >= 1.0) return foreground
    fun channel(shift: Int): Long {
        val f = (foreground ushr shift) and 255L
        val b = (background ushr shift) and 255L
        return ((f * alpha) + (b * (1.0 - alpha))).toLong().coerceIn(0L, 255L)
    }
    return (255L shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/**
 * Accent used as text on a card. The accent stays the mark colour (dot, underline, chevron); when it
 * cannot reach 4.5:1 on both card surfaces, text falls back to the skin's primary text colour.
 * A translucent card surface is composited over the skin background first, as Compose draws it.
 */
val SkinColors.readableAccent: Long
    get() {
        val surfaceCard = compositeOver(surface, bg)
        val moduleCard = compositeOver(moduleSurface, bg)
        return if (minOf(contrastRatio(accent, surfaceCard), contrastRatio(accent, moduleCard)) >= 4.5)
            accent else textPrimary
    }
