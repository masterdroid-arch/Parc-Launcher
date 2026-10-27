package com.brightblu.parc.core.data

import kotlin.math.roundToInt

/**
 * A drawable's bounds in bitmap pixels. Values may lie outside `0..sizePx`; the [android.graphics.Canvas]
 * clips the bleed. Immutable and free of Android types so the maths is unit-testable.
 */
internal data class DrawBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** Integer bounds ready for [android.graphics.drawable.Drawable.setBounds]. */
internal data class IntBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Rounds each edge to the nearest pixel edge. */
internal fun DrawBounds.toIntBounds(): IntBounds = IntBounds(
    left = left.roundToInt(),
    top = top.roundToInt(),
    right = right.roundToInt(),
    bottom = bottom.roundToInt(),
)

/**
 * Pure pixel maths for icon rasterisation.
 *
 * An adaptive icon's two layers each span a 108 dp canvas; only the central 72 dp safe zone is
 * guaranteed visible inside the system mask. Parc draws the layers unmasked and applies its own
 * circle, so the 108 dp canvas is scaled until the 72 dp safe zone fills the requested square and
 * the outer bleed is clipped away. Legacy icons are inset to the same 72/108 proportion.
 */
internal object IconRasterGeometry {
    /** Full adaptive-icon layer canvas. */
    const val LayerCanvasDp = 108f

    /** Central region of the layer canvas guaranteed visible inside the system mask. */
    const val SafeZoneDp = 72f

    /** 72/108 = 2/3: safe zone as a fraction of the layer canvas. */
    const val SafeZoneFraction = SafeZoneDp / LayerCanvasDp

    /** 108/72 = 1.5: scale applied to a layer canvas so its safe zone fills the requested square. */
    const val LayerScale = LayerCanvasDp / SafeZoneDp

    /** Fraction of the tile used by the letter glyph. */
    const val LetterSizeFraction = 0.5f

    /**
     * Bounds for one adaptive layer (background or foreground). The full 108 dp canvas is scaled so
     * its 72 dp safe zone equals [sizePx] and is centred; the bleed lies outside the square and is
     * clipped by the canvas.
     */
    fun adaptiveLayerBounds(sizePx: Int): DrawBounds = centred(sizePx, sizePx * LayerScale)

    /**
     * Bounds for a legacy (non-adaptive) icon: a centred square inset to the same safe-zone
     * proportion (72/108) of [sizePx], so its artwork sits where an adaptive safe zone would.
     */
    fun legacyBounds(sizePx: Int): DrawBounds = centred(sizePx, sizePx * SafeZoneFraction)

    /** Full-bleed circle radius for a letter tile. */
    fun letterTileRadius(sizePx: Int): Float = sizePx / 2f

    /** Text size for a letter tile's single glyph. */
    fun letterTileTextSize(sizePx: Int): Float = sizePx * LetterSizeFraction

    private fun centred(sizePx: Int, side: Float): DrawBounds {
        val offset = (sizePx - side) / 2f
        return DrawBounds(offset, offset, offset + side, offset + side)
    }
}
