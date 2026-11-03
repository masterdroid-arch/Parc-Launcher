package com.brightblu.parc.core.data.skins

import com.brightblu.parc.model.ModuleStyle
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinJson
import com.brightblu.parc.model.SkinSafety
import com.brightblu.parc.model.WallpaperReadability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T154: the Glass column of DESIGN 2.1, read from the shipped asset. */
class GlassSkinTest {
    private val glass = SkinJson.decode(File("src/main/assets/skins/glass.json").readText())

    @Test fun glassCarriesTheDesignColourColumn() {
        val c = glass.colors
        assertEquals("glass", glass.id)
        assertEquals(0xFF0A1220L, c.bg)
        // DESIGN: #4F6A98 at 38 % over the stage. SkinSafety reads surface as opaque, so the file stores that mix over bg (#24334E).
        assertEquals(0xFF24334EL, c.surface)
        assertEquals(0xFF2B3C5CL, c.lineBase)
        assertEquals(0xFF8FA6C9L, c.lineActive)
        assertEquals(0xFFEE5A2BL, c.accent)
        assertEquals(0x3DEE5A2BL, c.glow) // 24 %
        assertEquals(0xFFF2F5FBL, c.textPrimary)
        assertEquals(0xFFA9B8D2L, c.textSecondary)
        assertEquals(0xFFC9D6EAL, c.pulse)
        assertEquals(0xFFE5736BL, c.danger)
        assertEquals(0xB80A1220L, c.scrim)
        assertEquals(0x478FA6C9L, c.moduleSurface) // #8FA6C9 at 28 %
        assertEquals(0x99C9D6EAL, c.moduleGuide) // #C9D6EA at 60 %
        assertEquals(0xFFF2F5FBL, c.moduleInk)
    }

    @Test fun glassCarriesTheCardTokens() {
        assertEquals("Doto", glass.typography.numeralFamily)
        assertEquals(ModuleStyle.frosted, glass.shape.moduleStyle)
        assertEquals(1.0f, glass.motionScale)
        assertEquals(48f, glass.glowRadiusDp)
        assertEquals(0.04f, glass.breatheAmount)
    }

    @Test fun glassPassesSkinSafetyAndTextStaysLegibleOverTheBrightestHaze() {
        val c = glass.colors
        fun rgb(argb: Long) = argb.toInt() or 0xFF000000.toInt()
        fun over(fg: Long, back: Int): Int {
            val alpha = ((fg ushr 24) and 255) / 255f
            return WallpaperReadability.composite(fg.toInt(), back, alpha)
        }
        fun ratio(fg: Int, bg: Int) = WallpaperReadability.contrast(fg, bg)
        // Haze pixels are lineActive at 12 to 18 % over bg (DESIGN 2.1); the tint is drawn on top of that stage.
        val darkest = rgb(c.bg)
        val brightest = WallpaperReadability.composite(rgb(c.lineActive), rgb(c.bg), 0.18f)
        val rows = listOf(darkest, brightest).map { stage ->
            val surface = over(c.surface, stage)
            val card = over(c.moduleSurface, stage)
            listOf(
                ratio(rgb(c.textPrimary), stage), ratio(rgb(c.textSecondary), stage),
                ratio(rgb(c.textPrimary), surface), ratio(rgb(c.textSecondary), surface),
                ratio(rgb(c.moduleInk), card), ratio(rgb(c.accent), surface),
            )
        }
        println("GLASS-CONTRAST (textPrimary/stage, textSecondary/stage, textPrimary/surface, textSecondary/surface, moduleInk/card, accent/surface) " +
            "darkest=${rows[0].joinToString { "%.2f".format(it) }} brightest=${rows[1].joinToString { "%.2f".format(it) }}")
        for (row in rows) {
            assertTrue(row[0] >= 12.0)
            assertTrue(row[1] >= 4.5)
            assertTrue(row[2] >= 4.5)
            assertTrue(row[3] >= 4.5)
            assertTrue(row[4] >= 7.0)
        }
        assertTrue(SkinSafety.readable(glass))
    }

    @Test fun glassSitsAfterGraphiteInTheGallery() {
        val assets = File("src/main/assets/skins").listFiles { f -> f.name.endsWith(".json") }!!
            .map { SkinAsset(it.name, it.readText()) }
        val ids = SkinCatalogLoader.parse(assets).ids
        assertEquals(ids.indexOf("graphite") + 1, ids.indexOf("glass"))
        assertEquals(Skin.Midnight.id, ids.first())
    }
}
