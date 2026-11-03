package com.brightblu.parc.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassBackdropTest {
    @Test fun glassOffChangesNothing() {
        for (style in WallpaperStyle.entries) assertEquals(GlassStage.Off, GlassBackdrop.stage(false, style, true))
    }

    @Test fun wallpaperStaysVisibleWhenReadable() {
        assertEquals(GlassStage.WallpaperTint, GlassBackdrop.stage(true, WallpaperStyle.System, false))
        assertEquals(GlassStage.WallpaperTint, GlassBackdrop.stage(true, WallpaperStyle.System, true))
        assertEquals(GlassStage.WallpaperTint, GlassBackdrop.stage(true, WallpaperStyle.Image, true))
    }

    @Test fun fallsBackToTheHazeStageWithoutAWallpaper() {
        assertEquals(GlassStage.Haze, GlassBackdrop.stage(true, WallpaperStyle.Image, false))
        assertEquals(GlassStage.Haze, GlassBackdrop.stage(true, WallpaperStyle.Black, false))
        assertEquals(GlassStage.Haze, GlassBackdrop.stage(true, WallpaperStyle.Solid, true))
        assertFalse(GlassBackdrop.frostFromWallpaper(GlassStage.Haze, true))
        assertFalse(GlassBackdrop.frostFromWallpaper(GlassStage.WallpaperTint, false))
        assertTrue(GlassBackdrop.frostFromWallpaper(GlassStage.WallpaperTint, true))
    }

    @Test fun theTintOverTheWallpaperIsFaint() {
        assertTrue(Metrics.glassWallpaperTint <= 0.08f)
        assertTrue(Metrics.glassWallpaperTintMax <= 0.08f)
        val tint = GlassBackdrop.wallpaperTint(0x05070C)
        assertTrue((tint ushr 24) / 255f <= 0.08f)
        assertEquals(0x05070C, tint and 0xFFFFFF)
    }

    @Test fun glassKeepsTheDimAndSetsOnlyTheScrimAside() {
        // T176: Dim darkens a chosen picture also with glass on (it used to be dropped, so Dim did nothing on the owner's phone).
        val look = LookSettings(wallpaper = WallpaperStyle.Image, wallpaperDimPercent = 40, imageReadabilityScrim = true)
        val glass = GlassBackdrop.pictureLook(look, glassOn = true)
        assertEquals(40, glass.wallpaperDimPercent)
        assertFalse(glass.imageReadabilityScrim)
        assertEquals(WallpaperStyle.Image, glass.wallpaper)
        assertSame(look, GlassBackdrop.pictureLook(look, glassOn = false))
        val bare = LookSettings(wallpaper = WallpaperStyle.Image, wallpaperDimPercent = 0, imageReadabilityScrim = false)
        assertSame(bare, GlassBackdrop.pictureLook(bare, glassOn = true))
    }

    @Test fun grainAndHighlightConstants() {
        assertTrue(Metrics.glassGrainAlpha <= 0.04f)
        assertEquals(1f, Metrics.glassEdgeDp)
        assertEquals(0.35f, Metrics.glassEdgeAlpha)
        val tile = GlassBackdrop.grainTile()
        assertEquals(Metrics.glassGrainTilePx * Metrics.glassGrainTilePx, tile.size)
        assertTrue(tile.all { (it ushr 24) / 255f <= 0.04f && (it ushr 24) > 0 })
        assertEquals(tile.toList(), GlassBackdrop.grainTile().toList()) // deterministic
        assertTrue(tile.map { it and 0xFF }.toSet().size > 100) // real noise, not a flat tile
    }

    @Test fun identityChangesWithThePictureAndNeverIsZero() {
        val a = GlassBackdrop.identity(100, 200) { x, _ -> if (x % 20 < 10) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val b = GlassBackdrop.identity(100, 200) { x, _ -> if (x % 20 < 10) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val c = GlassBackdrop.identity(101, 200) { x, _ -> if (x % 20 < 10) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertNotEquals(a, b); assertNotEquals(a, c)
        assertEquals(a, GlassBackdrop.identity(100, 200) { x, _ -> if (x % 20 < 10) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() })
        assertNotEquals(0, GlassBackdrop.identity(1, 1) { _, _ -> 0 })
    }

    @Test fun tintKeepsSevenToOneOverTheRealPixelRange() {
        val surface = 0xFF8FA6C9.toInt()
        val ink = 0xFF05070C.toInt()
        val light = GlassBackdrop.tintAlpha(surface, ink, 0xFF808080.toInt(), 0xFFFFFFFF.toInt())
        val dark = GlassBackdrop.tintAlpha(surface, ink, 0xFF000000.toInt(), 0xFF808080.toInt())
        assertTrue(light >= Metrics.hubGlassTintAlpha)
        assertTrue(dark >= light) // a darker backdrop needs a more opaque card for dark ink
        for ((a, low, high) in listOf(Triple(light, 0xFF808080.toInt(), 0xFFFFFFFF.toInt()), Triple(dark, 0xFF000000.toInt(), 0xFF808080.toInt()))) {
            for (backdrop in listOf(low, high)) {
                val card = WallpaperReadability.composite(surface, backdrop, a / 255f)
                assertTrue(WallpaperReadability.contrast(ink, card) >= 7.0)
            }
        }
    }
}
