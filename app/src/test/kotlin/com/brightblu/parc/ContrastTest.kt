package com.brightblu.parc

import com.brightblu.parc.model.SkinJson
import com.brightblu.parc.core.data.skins.ColorMath
import com.brightblu.parc.core.data.skins.DynamicPalette
import com.brightblu.parc.core.data.skins.WallpaperSkin
import com.brightblu.parc.model.compositeOver
import com.brightblu.parc.model.contrastRatio
import com.brightblu.parc.model.readableAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T221: accent-as-text is safe on every shipped skin's cards; marks keep the raw accent. */
class ContrastTest {
    private fun skinDirectory(): File =
        sequenceOf(File("../core/data/src/main/assets/skins"), File("core/data/src/main/assets/skins"))
            .first { it.isDirectory }

    private fun skins() = skinDirectory().listFiles { file -> file.extension == "json" }!!
        .sortedBy { it.name }
        .map { SkinJson.decode(it.readText()) }

    @Test fun contrastRatioMatchesKnownPairs() {
        assertEquals(21.0, contrastRatio(0xFF000000L, 0xFFFFFFFFL), 0.0001)
        assertEquals(1.0, contrastRatio(0xFF123456L, 0xFF123456L), 0.0001)
        assertEquals(21.0, contrastRatio(0xFFFFFFFFL, 0xFF000000L), 0.0001)
    }

    @Test fun allNineShippedSkinsHaveReadableAccentTextOnBothCards() {
        val all = skins()
        assertEquals(9, all.size)
        for (skin in all) {
            val text = skin.colors.readableAccent
            assertTrue("${skin.id}: readable accent is accent or textPrimary",
                text == skin.colors.accent || text == skin.colors.textPrimary)
            for (raw in listOf(skin.colors.surface, skin.colors.moduleSurface)) {
                // A translucent module surface is drawn over the skin background, so test what the eye sees.
                val background = compositeOver(raw, skin.colors.bg)
                val ratio = contrastRatio(text, background)
                assertTrue("${skin.id}: $ratio on ${background.toString(16)}", ratio >= 4.5)
                assertTrue("${skin.id}: primary text itself must be readable on its cards",
                    contrastRatio(skin.colors.textPrimary, background) >= 4.5)
            }
        }
    }

    @Test fun mossFallsBackToPrimaryTextAndMidnightKeepsItsAccent() {
        val byId = skins().associateBy { it.id }
        assertEquals(byId.getValue("moss").colors.textPrimary, byId.getValue("moss").colors.readableAccent)
        assertEquals(byId.getValue("midnight").colors.accent, byId.getValue("midnight").colors.readableAccent)
    }

    @Test fun accentMustPassBothSurfaces() {
        val colors = skins().first().colors.copy(accent = 0xFF888888L, textPrimary = 0xFFFFFFFFL,
            bg = 0xFF000000L, surface = 0xFF000000L, moduleSurface = 0xFF444444L)
        assertTrue(contrastRatio(colors.accent, colors.surface) >= 4.5)
        assertTrue(contrastRatio(colors.accent, colors.moduleSurface) < 4.5)
        assertEquals(colors.textPrimary, colors.readableAccent)
        assertEquals(colors.textPrimary, colors.copy(surface = colors.moduleSurface, moduleSurface = colors.surface).readableAccent)
    }

    @Test fun transparentSurfacesAreCompositedOverTheStage() {
        assertEquals(0xFF000000L, compositeOver(0x00FFFFFFL, 0xFF000000L))
        assertEquals(0xFF808080L, compositeOver(0x80FFFFFFL, 0xFF000000L))
        assertEquals(0xFFFFFFFFL, compositeOver(0xFFFFFFFFL, 0xFF000000L))
    }

    @Test fun generatedWallpaperVariantsHaveReadableCardText() {
        fun ramp(mid: Long) = List(13) { i ->
            val t = i / 12.0
            if (t < .5) ColorMath.mix(0xFFFFFFFFL, mid, t * 2)
            else ColorMath.mix(mid, 0xFF000000L, (t - .5) * 2)
        }
        for (mid in listOf(0xFF3A7BD5L, 0xFF101820L, 0xFF7A7A85L)) {
            val shades = ramp(mid)
            val palette = DynamicPalette.orNull(shades, shades, shades, shades)!!
            for (dark in listOf(false, true)) {
                val colors = WallpaperSkin.colors(palette, dark)
                for (background in listOf(colors.surface, colors.moduleSurface)) {
                    assertTrue(contrastRatio(colors.readableAccent, compositeOver(background, colors.bg)) >= 4.5)
                }
                assertTrue(colors.readableAccent in listOf(colors.accent, colors.textPrimary))
            }
        }
    }
}
