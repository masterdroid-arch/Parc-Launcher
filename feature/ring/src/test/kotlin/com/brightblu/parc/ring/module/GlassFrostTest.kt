package com.brightblu.parc.ring.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassFrostTest {
    private fun request(stage: Int = 7, w: Int = 100, h: Int = 80, tint: Int = 0xB38FA6C9.toInt(), left: Int = 4, top: Int = 20,
        screenW: Int = 360, ink: Int = 0xFF05070C.toInt(), edge: Int = 2) =
        FrostRequest(screenW, 800, left, top, w, h, 10, true, tint, 0xFF05070C.toInt(), 0xFF8FA6C9.toInt(), stage, edge, ink)

    private fun luminance(p: Int) = (((p shr 16) and 0xFF) * 0.2126 + ((p shr 8) and 0xFF) * 0.7152 + (p and 0xFF) * 0.0722)

    @Test fun blurIsStableOnAConstantWallpaper() {
        val out = FrostMath.blurArgb(IntArray(20 * 30) { 0xFF4080C0.toInt() }, 20, 30, 3, 3)
        assertTrue(out.all { it == 0xFF4080C0.toInt() })
    }

    @Test fun blurKeepsTheAverageBrightnessOfStripes() {
        val w = 90; val h = 40
        val stripes = IntArray(w * h) { i -> if ((i % w) / 6 % 2 == 0) 0xFF202020.toInt() else 0xFFE0E0E0.toInt() }
        val out = FrostMath.blurArgb(stripes, w, h, 3, 3)
        val before = stripes.map(::luminance).average()
        val after = out.map(::luminance).average()
        assertEquals(before, after, before * 0.01)
        // the stripes are softened, not removed
        val row = out.copyOfRange(20 * w, 21 * w).map(::luminance)
        val original = stripes.copyOfRange(20 * w, 21 * w).map(::luminance)
        assertTrue(row.max() - row.min() < original.max() - original.min())
        assertTrue(row.max() - row.min() > 0.2 * (original.max() - original.min()))
    }

    @Test fun cacheKeyChangesWithWallpaperSizeColourAndPlace() {
        val base = request().key
        assertEquals(base, request().key)
        assertNotEquals(base, request(stage = 8).key) // wallpaper
        assertNotEquals(base, request(w = 101).key) // size
        assertNotEquals(base, request(h = 81).key)
        assertNotEquals(base, request(screenW = 361).key) // screen size
        assertNotEquals(base, request(tint = 0xB33B4A63.toInt()).key) // card colour
        assertNotEquals(base, request(ink = 0xFFFFFFFF.toInt()).key) // ink drives the tint over a wallpaper
        assertNotEquals(base, request(left = 5).key) // place
        assertNotEquals(base, request(top = 21).key)
        assertNotEquals(base, request(edge = 3).key)
        assertEquals(base.hashCode(), request().key.hashCode())
    }

    @Test fun hazeFrostIgnoresTheInkAndAnyWallpaper() {
        // stage 0 is the haze stage: the ink does not change its picture, so it must not change the cache key.
        assertEquals(request(stage = 0).key, request(stage = 0, ink = 0xFFFFFFFF.toInt()).key)
        assertNotEquals(request(stage = 0).key, request(stage = 7).key)
    }

    @Test fun edgeIsOneDpAndAtLeastOnePixel() {
        assertEquals(1, FrostRequest.edgeFor(0.5f))
        assertEquals(3, FrostRequest.edgeFor(2.8125f)) // 450 dpi
    }
}
