package com.brightblu.parc.core.data.skins

import com.brightblu.parc.model.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** T162: the hub module card is visibly separate from the stage in Midnight and Dusk. */
class VisibleModuleCardTest {
    private fun load(id: String) = SkinJson.decode(File("src/main/assets/skins/$id.json").readText())

    private fun luminance(argb: Long): Double {
        fun lin(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(16) + 0.7152 * lin(8) + 0.0722 * lin(0)
    }

    private fun ratio(a: Long, b: Long): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    @Test fun storedCardColours() {
        assertEquals(0xFF141B24L, load("midnight").colors.moduleSurface)
        assertEquals(0xFF1A1526L, load("dusk").colors.moduleSurface)
        assertEquals(0xFF141B24L, Skin.Midnight.colors.moduleSurface)
        assertEquals(0xFF1A1526L, Skin.Dusk.colors.moduleSurface)
    }

    @Test fun guideAndInkStayOnTheSkinTokens() {
        for (id in listOf("midnight", "dusk")) {
            val c = load(id).colors
            assertEquals(id, c.lineActive, c.moduleGuide)
            assertEquals(id, c.textPrimary, c.moduleInk)
        }
    }

    @Test fun cardIsVisiblySeparateFromBackgroundAndInkIsReadable() {
        for (id in listOf("midnight", "dusk")) {
            val skin = load(id)
            val c = skin.colors
            assertTrue("$id card vs bg ${ratio(c.moduleSurface, c.bg)}", ratio(c.moduleSurface, c.bg) >= 1.15)
            assertTrue("$id ink ${ratio(c.moduleInk, c.moduleSurface)}", ratio(c.moduleInk, c.moduleSurface) >= 7.0)
            assertTrue(SkinSafety.readable(skin))
        }
    }

    @Test fun otherSkinsAreUntouched() {
        for (id in listOf("cyber", "field-atlas-dark", "field-atlas-light", "phosphor")) {
            val c = load(id).colors
            assertEquals(id, c.surface, c.moduleSurface)
        }
    }
}
