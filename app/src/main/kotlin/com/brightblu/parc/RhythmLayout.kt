package com.brightblu.parc

import com.brightblu.parc.model.Metrics

/**
 * 1.2.1: the sizes of the Rhythm page, worked out from the room the hub page really has (never a fixed size that can
 * overflow). Pure and framework-free; the composables only draw what this says.
 */
internal object RhythmLayout {
    enum class Placement {
        /** Compact: the ring with its centre number, the date and the legend beside it. No facts. */
        CompactBeside,
        /** Standard: the ring on the left, the first facts on the right. */
        FactsBeside,
        /** Tall (and a very narrow Standard): the ring, then a row of facts under it. */
        FactsBelow,
    }

    /** [ringDp] is the ring's outer diameter; [boxDp] adds the hour-label band around it. */
    data class Plan(val placement: Placement, val ringDp: Float, val strokeDp: Float, val hourLabels: Boolean, val bandDp: Float) {
        val boxDp: Float get() = ringDp + 2f * bandDp
    }

    fun plan(size: HubSize, heightDp: Float, widthDp: Float, fontScale: Float): Plan {
        val scale = fontScale.coerceAtLeast(1f)
        val band = Metrics.rhythmLabelBandDp * scale
        val header = Metrics.rhythmHeaderDp * scale
        fun stroke(ring: Float) = (ring * Metrics.rhythmStrokeFraction).coerceIn(6f, Metrics.rhythmRingStrokeDp)
        return when (size) {
            HubSize.Compact -> {
                val ring = heightDp.coerceIn(Metrics.rhythmRingCompactMinDp, Metrics.rhythmRingCompactDp)
                Plan(Placement.CompactBeside, ring, stroke(ring), hourLabels = false, bandDp = 0f)
            }
            HubSize.Standard -> {
                val room = heightDp - header
                val sideWidth = Metrics.rhythmSideFactsMinWidthDp + Metrics.hudCellGapDp
                if (widthDp - sideWidth < Metrics.rhythmRingMinDp + 2f * band) below(heightDp, widthDp, scale, band, header, Metrics.rhythmRingStandardDp, ::stroke)
                else {
                    val ring = minOf(room - 2f * band, widthDp - sideWidth - 2f * band, Metrics.rhythmRingStandardDp)
                        .coerceAtLeast(Metrics.rhythmRingMinDp)
                    Plan(Placement.FactsBeside, ring, stroke(ring), hourLabels = true, bandDp = band)
                }
            }
            HubSize.Tall -> below(heightDp, widthDp, scale, band, header, Metrics.rhythmRingTallDp, ::stroke)
        }
    }

    private fun below(heightDp: Float, widthDp: Float, scale: Float, band: Float, header: Float, maxRing: Float, stroke: (Float) -> Float): Plan {
        val room = heightDp - header - Metrics.rhythmFactsRowDp * scale - Metrics.hudCellGapDp * 2f
        // 1.5.1: under the ring the page may scroll for the facts, but the ring itself stays a readable size.
        val comfortable = minOf(Metrics.rhythmRingBelowMinDp, widthDp - 2f * band, maxRing).coerceAtLeast(Metrics.rhythmRingMinDp)
        val ring = minOf(room - 2f * band, widthDp - 2f * band, maxRing).coerceAtLeast(comfortable)
        return Plan(Placement.FactsBelow, ring, stroke(ring), hourLabels = true, bandDp = band)
    }

    /**
     * The centre number's size: as large as the hole of the ring allows for [chars] characters (about 0.55 em each),
     * between the line size and the hub numeral size. With a bigger system font ([fontScale]) the size shrinks so the
     * drawn width stays the same: the number must never touch the ring.
     */
    fun centreSp(ringDp: Float, strokeDp: Float, chars: Int = 6, fontScale: Float = 1f): Float {
        val hole = ringDp - 2f * strokeDp
        val dp = hole * 0.74f / (chars.coerceAtLeast(1) * 0.55f)
        val lower = if (chars <= 6) Metrics.hubLineSp else 11f
        return (dp / fontScale.coerceAtLeast(1f)).coerceIn(lower, Metrics.hubValueSp)
    }}
