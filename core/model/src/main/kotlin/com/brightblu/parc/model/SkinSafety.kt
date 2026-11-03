package com.brightblu.parc.model

/**
 * OPUS6 (audit F7): a skin whose text cannot be read must never lock the user out.
 *
 * [readable] is the bar a skin must meet to be switched on: main and dim text at least 3:1 against
 * the background and the panel colour (the editor still recommends 4.5:1 for text). T161: the accent
 * is a small mark, not text, so it needs 3:1 against the background and the panel colour; module ink
 * needs 7:1 on the module card. [forSafetyScreens] is
 * the skin Settings, the skin editor and other recovery screens are drawn in: the active skin when
 * it is readable, otherwise the built-in Midnight palette, so the way back is always visible.
 */
object SkinSafety {
    const val MinTextContrast = 3.0
    /** The accent is a mark (hub dot, focus ring, meter dots), never text: 3:1 (DESIGN 2.1). */
    const val MinAccentMarkContrast = 3.0
    const val MinModuleInkContrast = 7.0

    fun textContrast(skin: Skin): Double {
        val c = skin.colors
        var worst = Double.MAX_VALUE
        for (text in longArrayOf(c.textPrimary, c.textSecondary)) for (behind in longArrayOf(c.bg, c.surface)) {
            worst = minOf(worst, WallpaperReadability.contrast(opaque(text), opaque(behind)))
        }
        return worst
    }

    /** Worst contrast of the accent mark against the stage and against the panel colour. */
    fun accentContrast(skin: Skin): Double = minOf(
        contrast(skin.colors.accent, skin.colors.bg),
        contrast(skin.colors.accent, composite(skin.colors.surface, skin.colors.bg)),
    )

    fun accentMarkReadable(skin: Skin): Boolean = accentContrast(skin) >= MinAccentMarkContrast

    fun moduleInkContrast(skin: Skin): Double = contrast(
        skin.colors.moduleInk, composite(skin.colors.moduleSurface, skin.colors.bg),
    )

    fun readable(skin: Skin): Boolean = textContrast(skin) >= MinTextContrast &&
        accentMarkReadable(skin) &&
        moduleInkContrast(skin) >= MinModuleInkContrast

    fun forSafetyScreens(skin: Skin): Skin = if (readable(skin)) skin else Skin.Midnight

    private fun opaque(argb: Long): Int = (argb.toInt() or 0xFF000000.toInt())

    private fun composite(foreground: Long, background: Long): Long =
        WallpaperReadability.composite(foreground.toInt(), opaque(background),
            ((foreground ushr 24) and 255) / 255f).toLong() and 0xFFFFFFFFL

    private fun contrast(foreground: Long, background: Long): Double =
        WallpaperReadability.contrast(composite(foreground, background).toInt(), opaque(background))
}
