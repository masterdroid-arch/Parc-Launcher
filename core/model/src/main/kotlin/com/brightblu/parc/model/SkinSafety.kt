package com.brightblu.parc.model

/**
 * OPUS6 (audit F7): a skin whose text cannot be read must never lock the user out.
 *
 * [readable] is the bar a skin must meet to be switched on: main and dim text at least 3:1 against
 * the background and the panel colour (the editor still recommends 4.5:1). [forSafetyScreens] is
 * the skin Settings, the skin editor and other recovery screens are drawn in: the active skin when
 * it is readable, otherwise the built-in Midnight palette, so the way back is always visible.
 */
object SkinSafety {
    const val MinTextContrast = 3.0

    fun textContrast(skin: Skin): Double {
        val c = skin.colors
        var worst = Double.MAX_VALUE
        for (text in longArrayOf(c.textPrimary, c.textSecondary)) for (behind in longArrayOf(c.bg, c.surface)) {
            worst = minOf(worst, WallpaperReadability.contrast(opaque(text), opaque(behind)))
        }
        return worst
    }

    fun readable(skin: Skin): Boolean = textContrast(skin) >= MinTextContrast

    fun forSafetyScreens(skin: Skin): Skin = if (readable(skin)) skin else Skin.Midnight

    private fun opaque(argb: Long): Int = (argb.toInt() or 0xFF000000.toInt())
}
