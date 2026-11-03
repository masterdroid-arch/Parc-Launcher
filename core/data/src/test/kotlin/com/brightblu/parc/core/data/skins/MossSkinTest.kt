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

/** T151: the Moss column of DESIGN 2.1, read from the shipped asset. */
class MossSkinTest {
    private val moss = SkinJson.decode(File("src/main/assets/skins/moss.json").readText())

    @Test fun mossCarriesTheDesignColourColumn() {
        val c = moss.colors
        assertEquals("moss", moss.id)
        assertEquals(0xFF06150CL, c.bg)
        assertEquals(0xFF0B3D22L, c.surface)
        assertEquals(0xFF1B4A2EL, c.lineBase)
        assertEquals(0xFF3F8F57L, c.lineActive)
        assertEquals(0xFFEE5A2BL, c.accent)
        assertEquals(0x42EE5A2BL, c.glow)
        assertEquals(0xFFF1F4ECL, c.textPrimary)
        assertEquals(0xFFA9B8ACL, c.textSecondary)
        assertEquals(0xFFF28A66L, c.pulse)
        assertEquals(0xFFE5736BL, c.danger)
        assertEquals(0xB806150CL, c.scrim)
        assertEquals(0xFF1F5A3AL, c.moduleSurface)
        assertEquals(0xFF8FBF9FL, c.moduleGuide)
        assertEquals(0xFFF1F4ECL, c.moduleInk)
    }

    @Test fun mossCarriesTheCardTokens() {
        assertEquals("Doto", moss.typography.numeralFamily)
        assertEquals(ModuleStyle.solid, moss.shape.moduleStyle)
        assertEquals(1.0f, moss.motionScale)
        assertEquals(48f, moss.glowRadiusDp)
        assertEquals(0.04f, moss.breatheAmount)
    }

    @Test fun mossPassesSkinSafetyAndPrintsItsContrastTable() {
        val c = moss.colors
        fun ratio(fg: Long, bg: Long) = WallpaperReadability.contrast(fg.toInt(), bg.toInt())
        println("MOSS-CONTRAST textPrimary/bg=%.2f textPrimary/surface=%.2f textSecondary/bg=%.2f textSecondary/surface=%.2f accent/bg=%.2f accent/surface=%.2f moduleInk/moduleSurface=%.2f".format(
            ratio(c.textPrimary, c.bg), ratio(c.textPrimary, c.surface), ratio(c.textSecondary, c.bg), ratio(c.textSecondary, c.surface),
            ratio(c.accent, c.bg), ratio(c.accent, c.surface), ratio(c.moduleInk, c.moduleSurface)))
        assertTrue(SkinSafety.readable(moss))
        assertTrue(SkinSafety.textContrast(moss) >= 4.5)
        assertTrue(SkinSafety.moduleInkContrast(moss) >= 7.0)
    }

    @Test fun mossSitsAfterDuskInTheGallery() {
        val assets = File("src/main/assets/skins").listFiles { f -> f.name.endsWith(".json") }!!
            .map { SkinAsset(it.name, it.readText()) }
        val ids = SkinCatalogLoader.parse(assets).ids
        assertEquals(ids.indexOf("dusk") + 1, ids.indexOf("moss"))
        assertEquals(Skin.Midnight.id, ids.first())
    }
}
