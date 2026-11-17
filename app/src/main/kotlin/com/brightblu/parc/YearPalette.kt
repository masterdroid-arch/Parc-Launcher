package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.SkinColors
import com.brightblu.parc.model.compositeOver
import com.brightblu.parc.model.contrastRatio
import com.brightblu.parc.model.readableAccent

/** T238: the colour of the filled Year dots. Accent follows the skin; the others are fixed hues made skin-safe. */
internal enum class YearColour(val id: String, val label: String) {
    Accent("accent", "Accent"),
    Green("green", "Green"),
    Blue("blue", "Blue"),
    Amber("amber", "Amber"),
    Rose("rose", "Rose"),
    Mono("mono", "Mono");

    companion object {
        fun fromId(id: String?): YearColour = entries.firstOrNull { it.id == id } ?: Accent
    }
}

internal object YearPalette {

    /** The two cards a dot can sit on, as the eye sees them (translucent surfaces composited over the skin background). */
    fun cards(colors: SkinColors): List<Long> =
        listOf(compositeOver(colors.surface, colors.bg), compositeOver(colors.moduleSurface, colors.bg))

    private fun worst(color: Long, cards: List<Long>) = cards.minOf { contrastRatio(color, it) }

    /** The full-strength colour: the hue's light or dark form, whichever reads better on this skin's cards. */
    fun base(colour: YearColour, colors: SkinColors): Long {
        val cards = cards(colors)
        return when (colour) {
            YearColour.Accent -> colors.readableAccent
            YearColour.Mono -> colors.textPrimary
            else -> {
                val (light, dark) = when (colour) {
                    YearColour.Green -> Metrics.yearGreenLight to Metrics.yearGreenDark
                    YearColour.Blue -> Metrics.yearBlueLight to Metrics.yearBlueDark
                    YearColour.Amber -> Metrics.yearAmberLight to Metrics.yearAmberDark
                    else -> Metrics.yearRoseLight to Metrics.yearRoseDark
                }
                if (worst(light, cards) >= worst(dark, cards)) light else dark
            }
        }
    }

    private fun mix(base: Long, card: Long, share: Double): Long = compositeOver(
        (base and 0x00FFFFFFL) or ((share * 255).toLong().coerceIn(0, 255) shl 24), card)

    /**
     * Four opaque colours, step 1 (less) to step 4 (more). Step 4 is the base; step 1 is the base faded toward the card
     * only as far as it still keeps [Metrics.yearStepFloorContrast] on both cards; steps 2 and 3 sit between them.
     */
    fun steps(colour: YearColour, colors: SkinColors): List<Long> {
        val base = base(colour, colors)
        val cards = cards(colors)
        val card = cards.minByOrNull { contrastRatio(base, it) }!!
        var share = 1.0
        while (share > 0.2 && worst(mix(base, card, share - 0.02), cards) >= Metrics.yearStepFloorContrast) share -= 0.02
        val first = mix(base, card, share)
        fun between(t: Float) = blend(first, base, t.toDouble())
        return listOf(first, between(Metrics.yearStepRamp2), between(Metrics.yearStepRamp3), base)
    }

    private fun blend(from: Long, to: Long, t: Double): Long {
        fun ch(shift: Int): Long = (((from ushr shift) and 255L) * (1 - t) + ((to ushr shift) and 255L) * t).toLong().coerceIn(0, 255)
        return (0xFFL shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The colour of an empty day's outline ring, readable on the card. */
    fun ring(colors: SkinColors): Long = colors.textSecondary
}
