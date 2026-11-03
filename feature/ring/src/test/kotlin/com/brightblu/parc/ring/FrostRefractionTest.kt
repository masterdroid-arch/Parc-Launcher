package com.brightblu.parc.ring

import com.brightblu.parc.model.ModuleStyle
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.GlassRefraction
import com.brightblu.parc.ring.module.FrostLens
import com.brightblu.parc.ring.module.FrostPath
import com.brightblu.parc.ring.module.FrostRequest
import org.junit.Assert.*
import org.junit.Test

/** T173: the lens edge is part of the cached frost, and the frost path is one decision. */
class FrostRefractionTest {
    private fun request(refractPx: Int = 0, left: Int = 40, stage: Int = 7) = FrostRequest(1080, 2400, left, 100, 1000, 900, 80, true,
        0x80123456.toInt(), 0, 0, stage, 3, 0, true, refractPx)

    @Test fun cacheKeyChangesWithRefractionAndItsBand() {
        assertEquals(request(45).key, request(45).key)
        assertNotEquals(request(0).key, request(45).key)
        assertNotEquals(request(45).key, request(52).key)
    }

    @Test fun cacheKeyStillChangesWithWallpaperSizeAndPlace() {
        assertNotEquals(request(45, stage = 7).key, request(45, stage = 8).key)
        assertNotEquals(request(45, left = 40).key, request(45, left = 400).key)
        assertNotEquals(request(45).key, request(45).copy(widthPx = 900).key)
        assertNotEquals(request(45).key, request(45).copy(tint = 0x80654321.toInt()).key)
    }

    @Test fun refractionIsOffUnlessAsked() {
        assertEquals(0, FrostRequest.refractFor(2.8f, false))
        assertTrue(FrostRequest.refractFor(2.8f, true) in (12 * 2.8f).toInt()..(20 * 2.8f).toInt())
    }

    @Test fun frostPathFollowsTheCardSkinAlone() {
        for (style in ModuleStyle.entries) {
            val skin = Skin.Midnight.copy(shape = Skin.Midnight.shape.copy(moduleStyle = style))
            assertEquals(style == ModuleStyle.frosted, FrostPath.frosted(skin))
        }
    }

    private fun gradient(w: Int, h: Int) = IntArray(w * h) { i ->
        val x = i % w; val y = i / w
        (0xFF shl 24) or ((x * 255 / (w - 1)) shl 16) or ((y * 255 / (h - 1)) shl 8) or ((x + y) * 255 / (w + h - 2))
    }

    @Test fun lensLeavesAConstantPictureConstant() {
        val flat = IntArray(126 * 150) { 0xFF336699.toInt() }
        assertArrayEquals(flat, FrostLens.apply(flat, 126, 150, 10, 5))
    }

    @Test fun lensChangesOnlyTheBandAndKeepsTheInputIntact() {
        val w = 126; val h = 150; val band = 5; val corner = 10
        val picture = gradient(w, h); val copy = picture.copyOf()
        val out = FrostLens.apply(picture, w, h, corner, band)
        assertArrayEquals(copy, picture)
        assertEquals(picture.size, out.size)
        var changed = 0
        for (y in 0 until h) for (x in 0 until w) {
            val depth = GlassRefraction.depth(x + 0.5f, y + 0.5f, w.toFloat(), h.toFloat(), corner.toFloat())
            if (depth >= band) assertEquals("($x,$y) deeper than the band", picture[y * w + x], out[y * w + x])
            else if (out[y * w + x] != picture[y * w + x]) changed++
        }
        assertTrue("the band bends the picture ($changed pixels)", changed > (w + h))
    }

    @Test fun lensKeepsTheAverageColourClose() {
        val w = 126; val h = 150
        val picture = gradient(w, h)
        val out = FrostLens.apply(picture, w, h, 10, 5)
        fun mean(px: IntArray, shift: Int) = px.sumOf { (it ushr shift) and 0xFF } / px.size.toDouble()
        for (shift in listOf(16, 8, 0)) assertEquals(mean(picture, shift), mean(out, shift), mean(picture, shift) * 0.02 + 1.0)
        assertTrue(out.all { it ushr 24 == 0xFF })
    }

    @Test fun lensIsOffWithoutABand() {
        val picture = gradient(40, 50)
        assertArrayEquals(picture, FrostLens.apply(picture, 40, 50, 6, 0))
    }
}
