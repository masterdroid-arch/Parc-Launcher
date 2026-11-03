package com.brightblu.parc.model

/**
 * T172: the colours behind a hub card, as opaque ARGB. [mean] is the average picture behind the card; [low] and [high]
 * are its darkest and lightest soft cell (cells are about a tenth of the card, so they already read like the blurred frost).
 */
data class Backdrop(val mean: Int, val low: Int, val high: Int) {
    companion object {
        private const val Opaque = 0xFF shl 24

        /** From a grid of cell colours (any ARGB; alpha is ignored). An empty grid is a mid grey. */
        fun of(cells: IntArray): Backdrop {
            if (cells.isEmpty()) return Backdrop(0xFF808080.toInt(), 0xFF808080.toInt(), 0xFF808080.toInt())
            var r = 0L; var g = 0L; var b = 0L
            var low = cells[0]; var high = cells[0]
            var lowLuma = Double.MAX_VALUE; var highLuma = -1.0
            for (p in cells) {
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
                val luma = WallpaperReadability.luminance(p or Opaque)
                if (luma < lowLuma) { lowLuma = luma; low = p }
                if (luma > highLuma) { highLuma = luma; high = p }
            }
            val n = cells.size
            return Backdrop(Opaque or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt(), low or Opaque, high or Opaque)
        }

        /** The T153 haze stage (bg with soft blobs of the line colour at 12 to 18 percent): what a card frosts when no picture can be read. */
        fun haze(bg: Int, line: Int) = Backdrop(
            mean = WallpaperReadability.composite(line, bg, Metrics.frostHazeAlphaMin / 2f + 0.05f),
            low = bg or Opaque,
            high = WallpaperReadability.composite(line, bg, Metrics.frostHazeAlphaMax))
    }
}

/** What a glass card ends up with: the ink for its text and icons, and the tint alpha (0..255) of its colour. */
data class GlassCard(val ink: Int, val alpha: Int)

/**
 * T172: readability for a glass card without making it solid. The tint never rises above [TintCap] on account of
 * readability (a card the user set above that stays where the user put it). The ink is chosen from what is behind the card,
 * so a dark picture gets light ink and a bright one dark ink. Pure maths, run when the picture, the card place or the
 * look changes, never per frame.
 */
object GlassReadability {
    /** 60 percent: the most the readability rule may add on its own. */
    const val TintCap = 153
    const val MeanContrast = 4.5
    const val ExtremeContrast = 3.0

    /** [skinInks] are tried first (the skin's own look); black or white is the last resort and always reaches 4.5:1 over the mean. */
    fun resolve(surface: Int, skinInks: List<Int>, requestedAlpha: Int, backdrop: Backdrop): GlassCard {
        val start = requestedAlpha.coerceIn(0, 255)
        val end = maxOf(start, TintCap)
        val inks = skinInks.map { it or (0xFF shl 24) }
        // Pass 1 also wants the extremes at 3:1; pass 2 settles for the mean alone. The lowest tint that works wins.
        for (withExtremes in booleanArrayOf(true, false)) for (alpha in start..end) {
            val best = best(surface, inks, alpha, backdrop, withExtremes)
            if (best != null) return GlassCard(best, alpha)
        }
        val neutral = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        return GlassCard(neutral.maxBy { meanContrast(surface, it, start, backdrop) }, start)
    }

    fun meanContrast(surface: Int, ink: Int, alpha: Int, backdrop: Backdrop): Double =
        WallpaperReadability.contrast(ink, WallpaperReadability.composite(surface, backdrop.mean, alpha / 255f))

    private fun best(surface: Int, inks: List<Int>, alpha: Int, backdrop: Backdrop, withExtremes: Boolean): Int? {
        val a = alpha / 255f
        val low = WallpaperReadability.composite(surface, backdrop.low, a)
        val high = WallpaperReadability.composite(surface, backdrop.high, a)
        var found: Int? = null
        var foundContrast = 0.0
        for (ink in inks) {
            val mean = meanContrast(surface, ink, alpha, backdrop)
            if (mean < MeanContrast) continue
            if (withExtremes && (WallpaperReadability.contrast(ink, low) < ExtremeContrast || WallpaperReadability.contrast(ink, high) < ExtremeContrast)) continue
            if (mean > foundContrast) { found = ink; foundContrast = mean }
        }
        return found
    }
}
