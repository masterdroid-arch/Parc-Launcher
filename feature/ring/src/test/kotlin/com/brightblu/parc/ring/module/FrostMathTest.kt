package com.brightblu.parc.ring.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrostMathTest {
    private fun request(w: Int = 100, h: Int = 80, corner: Int = 10, tint: Int = 0x478FA6C9, bg: Int = 0xFF05070C.toInt(),
        haze: Int = 0xFF8FA6C9.toInt(), left: Int = 4, screenW: Int = 360) =
        FrostRequest(screenW, 800, left, 20, w, h, corner, true, tint, bg, haze)

    @Test fun constantImageStaysConstant() {
        val out = FrostMath.blur(FloatArray(12 * 9) { 77f }, 12, 9, 3, 3)
        assertTrue(out.all { kotlin.math.abs(it - 77f) < 1e-3f })
        val argb = IntArray(12 * 9) { 0xFF336699.toInt() }
        assertTrue(FrostMath.blurArgb(argb, 12, 9, 3, 3).all { it == 0xFF336699.toInt() })
    }

    @Test fun impulseSpreadsSymmetricallyAndKeepsItsSum() {
        val w = 41; val h = 41
        val src = FloatArray(w * h).also { it[20 * w + 20] = 1000f }
        val out = FrostMath.blur(src, w, h, 3, 3)
        assertEquals(1000f, out.sum(), 10f)
        for (d in 1..8) {
            assertEquals(out[20 * w + 20 - d], out[20 * w + 20 + d], 1e-3f)
            assertEquals(out[(20 - d) * w + 20], out[(20 + d) * w + 20], 1e-3f)
            assertEquals(out[20 * w + 20 + d], out[(20 + d) * w + 20], 1e-3f)
        }
        assertTrue(out[20 * w + 20] < 1000f / 4f)
        assertTrue(out[20 * w + 21] > 0f)
    }

    @Test fun keyChangesWithSizeSkinAndStageAndNothingElse() {
        val base = request().key
        assertEquals(base, request().key)
        assertNotEquals(base, request(w = 101).key)
        assertNotEquals(base, request(h = 81).key)
        assertNotEquals(base, request(tint = 0x558FA6C9).key)
        assertNotEquals(base, request(bg = 0xFF000000.toInt()).key)
        assertNotEquals(base, request(corner = 12).key)
        assertNotEquals(base, request(haze = 0xFF3A4A5E.toInt()).key)
        assertNotEquals(base, request(left = 5).key)
        assertNotEquals(base, request(screenW = 361).key)
        // The request is a plain value: the cached picture never depends on anything outside these fields.
        assertEquals(base.hashCode(), request().key.hashCode())
    }

    @Test fun cacheEvictsLeastRecentlyUsed() {
        val cache = FrostCache<String>(2)
        val a = request(w = 1).key; val b = request(w = 2).key; val c = request(w = 3).key
        cache.put(a, "a"); cache.put(b, "b"); cache.get(a); cache.put(c, "c")
        assertEquals("a", cache.get(a)); assertNull(cache.get(b)); assertEquals("c", cache.get(c))
        assertEquals(2, cache.size())
    }

    @Test fun solidSkinsGetNoFrost() {
        val solid = com.brightblu.parc.model.Skin.Midnight
        assertEquals(com.brightblu.parc.model.ModuleStyle.solid, solid.shape.moduleStyle)
        // frostedBackground returns the very same modifier for solid skins.
        assertTrue(androidx.compose.ui.Modifier.frostedBackground(solid, 20f) === androidx.compose.ui.Modifier)
    }
}
