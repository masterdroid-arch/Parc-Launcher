package com.brightblu.parc.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * T173: the lens edge of a glass hub card. A thick glass rim shows the picture behind it magnified, pulled in toward the
 * middle of the card. The map says, for a pixel of the card, where in the blurred picture its colour comes from.
 *
 * - Inside the card, further than `band` from the edge, the map is the identity (so the middle of the card is the plain frost).
 * - In the band the picture is magnified by [Metrics.glassRefractMagnify] about the card centre, faded in with a smoothstep,
 *   so the source point lies toward the centre of the card. The move is capped at
 *   [Metrics.glassRefractMaxShiftOfBand] of the band, which keeps the map monotone (nothing folds over).
 *
 * Pure arithmetic on floats, no Android types; the renderer evaluates it once per pixel of the band while it pre-renders a card.
 */
object GlassRefraction {
    fun bandPx(density: Float) = (Metrics.glassRefractBandDp * density + 0.5f).toInt().coerceAtLeast(2)
    /** The inner shadow keeps its share of the band (3 dp of 16 dp), so it follows the density with it. */
    fun innerShadowPx(band: Int) = (band * Metrics.glassInnerShadowDp / Metrics.glassRefractBandDp + 0.5f).toInt().coerceAtLeast(1)

    /** Distance in px from (x, y) to the edge of a rounded rectangle of [w] x [h] with corner [r], positive inside. */
    fun depth(x: Float, y: Float, w: Float, h: Float, r: Float): Float {
        val hx = w / 2f; val hy = h / 2f
        val rr = min(r, min(hx, hy))
        val qx = kotlin.math.abs(x - hx) - (hx - rr)
        val qy = kotlin.math.abs(y - hy) - (hy - rr)
        val ox = max(qx, 0f); val oy = max(qy, 0f)
        val outside = if (ox > 0f && oy > 0f) sqrt(ox * ox + oy * oy) else ox + oy // sqrt only in the rounded corners
        val inside = min(max(qx, qy), 0f)
        return rr - (outside + inside)
    }

    /** 1 at the edge, 0 at [band] px in (smoothstep). */
    fun weight(depth: Float, band: Int): Float {
        if (depth >= band) return 0f
        val t = 1f - (depth.coerceAtLeast(0f) / band)
        return t * t * (3f - 2f * t)
    }

    /** The largest distance, in px, a pixel's source may lie from the pixel. */
    fun maxShiftPx(band: Int) = band * Metrics.glassRefractMaxShiftOfBand

    /**
     * Writes the source position of the pixel at ([x], [y]) into `out[0]`, `out[1]`. [out] is passed in so that the render loop
     * allocates nothing.
     */
    fun source(x: Float, y: Float, w: Float, h: Float, r: Float, band: Int, out: FloatArray) {
        val d = depth(x, y, w, h, r)
        val k = weight(d, band)
        if (k <= 0f) { out[0] = x; out[1] = y; return }
        var dx = (w / 2f - x) * (Metrics.glassRefractMagnify - 1f) * k
        var dy = (h / 2f - y) * (Metrics.glassRefractMagnify - 1f) * k
        val cap = maxShiftPx(band)
        val shift2 = dx * dx + dy * dy
        if (shift2 > cap * cap) { val s = cap / sqrt(shift2); dx *= s; dy *= s }
        out[0] = x + dx; out[1] = y + dy
    }
}
