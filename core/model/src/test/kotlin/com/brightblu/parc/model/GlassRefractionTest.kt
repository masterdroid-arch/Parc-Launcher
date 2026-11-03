package com.brightblu.parc.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

/** T173: the lens map of the glass hub cards. */
class GlassRefractionTest {
    private val densities = listOf(1f, 2.625f, 2.8125f, 3.5f)
    private val sizes = listOf(1000 to 1000, 1000 to 700, 960 to 2400 / 3, 300 to 120)

    private fun source(x: Float, y: Float, w: Int, h: Int, r: Int, band: Int): FloatArray =
        FloatArray(2).also { GlassRefraction.source(x, y, w.toFloat(), h.toFloat(), r.toFloat(), band, it) }

    @Test fun constantsAreInTheCardRanges() {
        assertTrue(Metrics.glassRefractMagnify in 1.04f..1.08f)
        assertTrue(Metrics.glassRefractBandDp in 12f..20f)
        assertTrue(Metrics.glassRefractBudgetMs <= 80)
        for (d in densities) assertEquals(Metrics.glassRefractBandDp * d, GlassRefraction.bandPx(d).toFloat(), 1f)
    }

    @Test fun identityAtTheCentreAndEverywhereBeyondTheBand() {
        for ((w, h) in sizes) for (d in densities) {
            val band = GlassRefraction.bandPx(d)
            val r = (28 * d).toInt().coerceAtMost(minOf(w, h) / 2)
            val centre = source(w / 2f, h / 2f, w, h, r, band)
            assertEquals(w / 2f, centre[0], 0f); assertEquals(h / 2f, centre[1], 0f)
            for (y in 0 until h step 7) for (x in 0 until w step 7) {
                if (GlassRefraction.depth(x + 0.5f, y + 0.5f, w.toFloat(), h.toFloat(), r.toFloat()) >= band) {
                    val s = source(x + 0.5f, y + 0.5f, w, h, r, band)
                    assertEquals(x + 0.5f, s[0], 0f); assertEquals(y + 0.5f, s[1], 0f)
                }
            }
        }
    }

    @Test fun displacementIsBoundedAndPointsInward() {
        for ((w, h) in sizes) for (d in densities) {
            val band = GlassRefraction.bandPx(d)
            val r = (28 * d).toInt().coerceAtMost(minOf(w, h) / 2)
            val cap = GlassRefraction.maxShiftPx(band)
            assertTrue(cap <= band * Metrics.glassRefractMaxShiftOfBand + 1e-3f)
            var moved = 0
            for (y in 0 until h step 3) for (x in 0 until w step 3) {
                val px = x + 0.5f; val py = y + 0.5f
                val s = source(px, py, w, h, r, band)
                val dx = s[0] - px; val dy = s[1] - py
                assertTrue("shift ${hypot(dx, dy)} over $cap", hypot(dx, dy) <= cap + 1e-3f)
                // toward the centre: never away from it
                assertTrue(dx * (w / 2f - px) + dy * (h / 2f - py) >= -1e-3f)
                if (hypot(dx, dy) > 0.5f) moved++
            }
            assertTrue("the band does move pixels", moved > 0)
        }
    }

    @Test fun theSourceDeepensMonotonicallyAcrossTheBand() {
        // Along the middle of the top edge the source depth only grows with the pixel depth: nothing folds over.
        val w = 1000; val h = 1000; val d = 2.8125f; val band = GlassRefraction.bandPx(d); val r = (28 * d).toInt()
        var last = -1f
        for (y in 0 until band * 2) {
            val s = source(w / 2f, y + 0.5f, w, h, r, band)
            val depth = GlassRefraction.depth(s[0], s[1], w.toFloat(), h.toFloat(), r.toFloat())
            assertTrue("depth $depth after $last at y=$y", depth >= last)
            last = depth
        }
        // and the very edge reads from further in than it is
        assertTrue(GlassRefraction.depth(source(w / 2f, 0.5f, w, h, r, band)[0], source(w / 2f, 0.5f, w, h, r, band)[1], w.toFloat(), h.toFloat(), r.toFloat()) > 0.5f)
    }

    @Test fun innerShadowKeepsItsShareOfTheBand() {
        for (d in densities) {
            val band = GlassRefraction.bandPx(d)
            val shadow = GlassRefraction.innerShadowPx(band)
            assertTrue(shadow >= 1 && shadow < band / 2)
        }
        assertTrue(Metrics.glassInnerShadowAlpha in 0.05f..0.25f)
    }

    @Test fun depthOfARoundedRectangle() {
        assertEquals(500f, GlassRefraction.depth(500f, 500f, 1000f, 1000f, 60f), 0.01f)
        assertEquals(0f, GlassRefraction.depth(500f, 0f, 1000f, 1000f, 60f), 0.01f)
        assertEquals(10f, GlassRefraction.depth(10f, 500f, 1000f, 1000f, 60f), 0.01f)
        assertTrue(GlassRefraction.depth(0f, 0f, 1000f, 1000f, 60f) < 0f) // outside the rounded corner
    }
}
