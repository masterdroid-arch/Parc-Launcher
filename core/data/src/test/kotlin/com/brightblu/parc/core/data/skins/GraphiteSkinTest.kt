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

/** T152: the Graphite column of DESIGN 2.1, read from the shipped asset. */
class GraphiteSkinTest {
    private val graphite = SkinJson.decode(File("src/main/assets/skins/graphite.json").readText())

    @Test fun graphiteCarriesTheDesignColourColumn() {
        val c = graphite.colors
        assertEquals("graphite", graphite.id)
        assertEquals(0xFF000000L, c.bg)
        assertEquals(0xFF2A2A2AL, c.surface)
        assertEquals(0xFF333333L, c.lineBase)
        assertEquals(0xFF6B6B6BL, c.lineActive)
        assertEquals(0xFFE8431FL, c.accent)
        assertEquals(0x47E8431FL, c.glow)
        assertEquals(0xFFE4E4E4L, c.textPrimary)
        // DESIGN 2.1 estimates #8E8E8E, which measures 4.38:1 on the surface (bar 4.5); #929292 is the nearest pass.
        assertEquals(0xFF929292L, c.textSecondary)
        assertEquals(0xFFF07A5CL, c.pulse)
        assertEquals(0xFFE5736BL, c.danger)
        assertEquals(0xB8000000L, c.scrim)
        assertEquals(0xFF3A3A3AL, c.moduleSurface)
        assertEquals(0xFF9A9A9AL, c.moduleGuide)
        assertEquals(0xFFF2F2F2L, c.moduleInk)
    }

    @Test fun graphiteCarriesTheCardTokens() {
        assertEquals("Doto", graphite.typography.numeralFamily)
        assertEquals(ModuleStyle.solid, graphite.shape.moduleStyle)
        assertEquals(1.0f, graphite.motionScale)
        assertEquals(48f, graphite.glowRadiusDp)
        assertEquals(0.04f, graphite.breatheAmount)
    }

    @Test fun graphitePassesSkinSafetyAndPrintsItsContrastTable() {
        val c = graphite.colors
        fun ratio(fg: Long, bg: Long) = WallpaperReadability.contrast(fg.toInt(), bg.toInt())
        println("GRAPHITE-CONTRAST textPrimary/bg=%.2f textPrimary/surface=%.2f textSecondary/bg=%.2f textSecondary/surface=%.2f accent/bg=%.2f accent/surface=%.2f moduleInk/moduleSurface=%.2f".format(
            ratio(c.textPrimary, c.bg), ratio(c.textPrimary, c.surface), ratio(c.textSecondary, c.bg), ratio(c.textSecondary, c.surface),
            ratio(c.accent, c.bg), ratio(c.accent, c.surface), ratio(c.moduleInk, c.moduleSurface)))
        assertTrue(SkinSafety.readable(graphite))
        assertTrue(SkinSafety.textContrast(graphite) >= 4.5)
        assertTrue(SkinSafety.moduleInkContrast(graphite) >= 7.0)
    }

    @Test fun graphiteSitsAfterMossInTheGallery() {
        val assets = File("src/main/assets/skins").listFiles { f -> f.name.endsWith(".json") }!!
            .map { SkinAsset(it.name, it.readText()) }
        val ids = SkinCatalogLoader.parse(assets).ids
        assertEquals(ids.indexOf("moss") + 1, ids.indexOf("graphite"))
        assertEquals(Skin.Midnight.id, ids.first())
    }
}
