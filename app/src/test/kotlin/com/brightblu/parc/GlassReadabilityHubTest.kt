package com.brightblu.parc

import com.brightblu.parc.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** T172: in every skin and swatch, a glass hub with "Keep text readable" is never solid because of readability and its ink reads over the mean. */
class GlassReadabilityHubTest {
    private val skins get() = File("../core/data/src/main/assets/skins").listFiles()!!
        .filter { it.extension == "json" }.map { SkinJson.decode(it.readText()) }
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private val backdrops = listOf(
        Backdrop(grey(245), grey(220), grey(255)), Backdrop(grey(15), grey(0), grey(40)), Backdrop(grey(128), grey(128), grey(128)),
        Backdrop(grey(120), grey(10), grey(250)), // bright sky, dark ground
        Backdrop(0xFF5A7BA8.toInt(), 0xFF203040.toInt(), 0xFFB0C8E8.toInt()),
        Backdrop(0xFF8A6A3A.toInt(), 0xFF302010.toInt(), 0xFFE8D0A0.toInt()))

    @Test fun tintStaysUnderSixtyPercentAndInkReadsOverTheMeanInEverySkinAndSwatch() {
        for (skin in skins) for (colour in HubColour.entries) for (b in backdrops) for (opacity in listOf(20, 35, 50, 60, 100)) {
            val appearance = HubAppearance(colour, colour, glass = true)
            for ((name, card) in listOf("arc" to appearance.arcSkin(skin, opacity, keepReadable = true, backdrop = b),
                "stats" to appearance.statsSkin(skin, opacity, keepReadable = true, backdrop = b))) {
                val tint = card.colors.moduleSurface
                val alpha = (tint ushr 24).toInt()
                val requested = (opacity * 255 + 50) / 100
                assertTrue("${skin.id} $colour $name $opacity alpha $alpha", alpha >= requested && alpha <= maxOf(requested, GlassReadability.TintCap))
                val ink = card.colors.moduleInk.toInt()
                val mean = WallpaperReadability.composite(tint.toInt(), b.mean, alpha / 255f)
                assertTrue("${skin.id} $colour $name $opacity contrast ${WallpaperReadability.contrast(ink, mean)}", WallpaperReadability.contrast(ink, mean) >= 4.5)
                assertEquals(ink.toLong() and 0xFFFFFFFF, card.colors.textPrimary and 0xFFFFFFFF)
                // Tiles inside the stats card sit on `surface`; their text must read there too (the nested surface flips with the ink).
                assertTrue("${skin.id} $colour $name $opacity nested", WallpaperReadability.contrast(ink, card.colors.surface.toInt()) >= 4.5 || name == "arc")
            }
        }
    }

    @Test fun inkFlipsWithTheBrightnessOfThePictureBehindTheGlassSkin() {
        val glass = skins.first { it.id == "glass" }
        val appearance = HubAppearance(glass = true)
        val over = { b: Backdrop -> appearance.arcSkin(glass, 50, keepReadable = true, backdrop = b).colors.moduleInk.toInt() }
        val bright = over(backdrops[0]); val dark = over(backdrops[1])
        assertTrue(WallpaperReadability.luminance(bright) < WallpaperReadability.luminance(dark))
    }

    @Test fun withReadabilityOffTheUsersOpacityIsTheTintExactly() {
        for (skin in skins) for (opacity in listOf(20, 50, 100)) {
            val alpha = HubAppearance(glass = true).arcSkin(skin, opacity, keepReadable = false, backdrop = backdrops[1]).colors.moduleSurface ushr 24
            assertEquals((opacity * 255 + 50) / 100L, alpha)
        }
    }

}
