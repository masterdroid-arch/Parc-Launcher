package com.brightblu.parc.core.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-mathematics tests for icon rasterisation (T04b). No Android runtime is involved: only the
 * scale/inset maths that used to be implicit in [AndroidLauncherSource.renderIcon] is asserted here.
 */
class IconRasterGeometryTest {

    private val delta = 0.0001f

    @Test
    fun `safe zone is two thirds of the layer canvas and the scale is its inverse`() {
        assertEquals(72f / 108f, IconRasterGeometry.SafeZoneFraction, delta)
        assertEquals(108f / 72f, IconRasterGeometry.LayerScale, delta)
        assertEquals(1f, IconRasterGeometry.SafeZoneFraction * IconRasterGeometry.LayerScale, delta)
    }

    @Test
    fun `adaptive layer canvas is scaled so the safe zone fills the requested square`() {
        val bounds = IconRasterGeometry.adaptiveLayerBounds(112)

        // 112 * 108/72 = 168 px; bleed of 18 dp each side = 112 * 0.25 = 28 px.
        assertEquals(168f, bounds.width, delta)
        assertEquals(168f, bounds.height, delta)
        assertEquals(-28f, bounds.left, delta)
        assertEquals(-28f, bounds.top, delta)
        assertEquals(140f, bounds.right, delta)
        assertEquals(140f, bounds.bottom, delta)
    }

    @Test
    fun `adaptive bounds stay centred on the square for any size`() {
        for (sizePx in intArrayOf(1, 48, 100, 112, 144, 213)) {
            val bounds = IconRasterGeometry.adaptiveLayerBounds(sizePx)
            assertEquals(sizePx.toFloat(), bounds.left + bounds.right, delta)
            assertEquals(sizePx.toFloat(), bounds.top + bounds.bottom, delta)
            // The bleed is symmetric and always one quarter of the square on each side.
            assertEquals(-0.25f * sizePx, bounds.left, delta)
        }
    }

    @Test
    fun `the safe zone region maps to exactly the bitmap square`() {
        val sizePx = 112
        val bounds = IconRasterGeometry.adaptiveLayerBounds(sizePx)
        val bleed = -bounds.left
        val safeZone = bounds.width - 2 * bleed

        assertEquals(sizePx.toFloat(), safeZone, delta)
        // The scaled bleed equals the real 18 dp gap scaled by (sizePx / 72 dp).
        assertEquals(18f * (sizePx / 72f), bleed, delta)
    }

    @Test
    fun `legacy icons are inset to the same safe-zone proportion`() {
        val bounds = IconRasterGeometry.legacyBounds(108)

        // 108 * 72/108 = 72 px, centred with an 18 px inset on every side.
        assertEquals(72f, bounds.width, delta)
        assertEquals(18f, bounds.left, delta)
        assertEquals(18f, bounds.top, delta)
        assertEquals(90f, bounds.right, delta)
        assertEquals(90f, bounds.bottom, delta)
    }

    @Test
    fun `legacy inset is one sixth of the square on each side`() {
        for (sizePx in intArrayOf(1, 48, 100, 112, 144, 213)) {
            val bounds = IconRasterGeometry.legacyBounds(sizePx)
            assertEquals(sizePx * (2f / 3f), bounds.width, delta)
            assertEquals(sizePx / 6f, bounds.left, delta)
            assertEquals(sizePx.toFloat(), bounds.left + bounds.right, delta)
        }
    }

    @Test
    fun `integer bounds round to the nearest pixel edge`() {
        assertEquals(
            IntBounds(-28, -28, 140, 140),
            IconRasterGeometry.adaptiveLayerBounds(112).toIntBounds(),
        )
        // 112 * 2/3 = 74.67 px side, 18.67 px inset -> 19..93.
        assertEquals(
            IntBounds(19, 19, 93, 93),
            IconRasterGeometry.legacyBounds(112).toIntBounds(),
        )
    }

    @Test
    fun `letter tile geometry is a full-bleed circle with the 0_5 text fraction`() {
        // Regression guard: the T04b change must not alter the letter-tile fallback.
        assertEquals(0.5f, IconRasterGeometry.LetterSizeFraction, delta)
        assertEquals(56f, IconRasterGeometry.letterTileRadius(112), delta)
        assertEquals(56f, IconRasterGeometry.letterTileTextSize(112), delta)
        assertEquals(0.5f, IconRasterGeometry.letterTileRadius(1), delta)
        assertEquals(24f, IconRasterGeometry.letterTileTextSize(48), delta)
    }
}
