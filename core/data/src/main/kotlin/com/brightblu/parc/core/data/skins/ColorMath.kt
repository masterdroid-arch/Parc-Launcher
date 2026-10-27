package com.brightblu.parc.core.data.skins

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** HSV colour with hue in degrees (0 until 360), saturation and value in 0..1, alpha in 0..255. */
data class Hsv(val hue: Float, val saturation: Float, val value: Float, val alpha: Int = 255)

/** Pure colour helpers for the skin editor: hex text, HSV and WCAG contrast. Thread-safe, no Android. */
object ColorMath {

    /** `RGB`, `RRGGBB` or `AARRGGBB`, with or without a leading `#`; null when it is not valid hex. */
    fun parseHex(text: String): Long? {
        val raw = text.trim().removePrefix("#")
        if (raw.isEmpty() || raw.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return when (raw.length) {
            3 -> {
                val r = raw[0].digitToInt(16); val g = raw[1].digitToInt(16); val b = raw[2].digitToInt(16)
                0xFF000000L or (r * 17L shl 16) or (g * 17L shl 8) or (b * 17L)
            }
            6 -> 0xFF000000L or raw.toLong(16)
            8 -> raw.toLong(16)
            else -> null
        }
    }

    /** `#RRGGBB` for opaque colours, `#AARRGGBB` otherwise (upper case). */
    fun formatHex(argb: Long): String {
        val value = argb and 0xFFFFFFFFL
        return if ((value ushr 24) == 0xFFL) "#%06X".format(value and 0xFFFFFFL) else "#%08X".format(value)
    }

    fun toHsv(argb: Long): Hsv {
        val a = ((argb ushr 24) and 0xFF).toInt()
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return Hsv(if (h < 0f) h + 360f else h, if (mx == 0f) 0f else d / mx, mx, a)
    }

    fun fromHsv(hsv: Hsv): Long {
        val h = ((hsv.hue % 360f) + 360f) % 360f
        val s = hsv.saturation.coerceIn(0f, 1f); val v = hsv.value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float): Long = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255).toLong()
        return (hsv.alpha.coerceIn(0, 255).toLong() shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }

    /** WCAG relative luminance of the colour's RGB (alpha ignored). */
    fun luminance(argb: Long): Double {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = ((argb ushr 16) and 0xFF).toInt(); val g = ((argb ushr 8) and 0xFF).toInt(); val b = (argb and 0xFF).toInt()
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    /** Source-over composite of [top] on an opaque [bottom]; the result is opaque. */
    fun over(top: Long, bottom: Long): Long {
        val a = ((top ushr 24) and 0xFF) / 255.0
        fun mix(shift: Int): Long {
            val t = ((top ushr shift) and 0xFF).toDouble(); val b = ((bottom ushr shift) and 0xFF).toDouble()
            return (t * a + b * (1.0 - a) + 0.5).toLong().coerceIn(0, 255)
        }
        return 0xFF000000L or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** WCAG contrast ratio (1..21) of [foreground] on [background]; translucent foregrounds are composited first. */
    fun contrast(foreground: Long, background: Long): Double {
        val fg = if ((foreground ushr 24) == 0xFFL) foreground else over(foreground, background)
        val l1 = luminance(fg); val l2 = luminance(background)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    /** Linear blend of the RGB of [from] towards [to] by [t] (0..1); the result is opaque. */
    fun mix(from: Long, to: Long, t: Double): Long {
        val k = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int): Long {
            val a = ((from ushr shift) and 0xFF).toDouble(); val b = ((to ushr shift) and 0xFF).toDouble()
            return (a + (b - a) * k + 0.5).toLong().coerceIn(0, 255)
        }
        return 0xFF000000L or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /**
     * Moves [foreground] towards [toward] (white or black) in 5% steps until it reaches [minRatio] on
     * every opaque colour in [backgrounds]. Returns an opaque colour; the pure end of the blend if the
     * ratio cannot be reached.
     */
    fun ensureContrast(foreground: Long, backgrounds: List<Long>, minRatio: Double, toward: Long): Long {
        var colour = foreground or 0xFF000000L
        var step = 0
        while (step < 20 && backgrounds.any { contrast(colour, it) < minRatio }) {
            step++
            colour = mix(foreground, toward, step * 0.05)
        }
        return colour
    }

    const val TextContrast = 4.5
}
