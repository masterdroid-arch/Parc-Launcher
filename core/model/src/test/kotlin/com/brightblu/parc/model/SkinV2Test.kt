package com.brightblu.parc.model

import org.junit.Assert.*
import org.junit.Test

class SkinV2Test {
    @Test fun defaultsAndExplicitTokensRoundTrip() {
        val defaults = TypographyTokens()
        assertEquals(TypeToken(56f, 500, 0f), defaults.clockDot)
        assertEquals(TypeToken(28f, 600, 0f), defaults.countDot)
        assertEquals(TypeToken(88f, 600, 0f), defaults.bigDot)
        val skin = Skin.Midnight.copy(
            colors = Skin.Midnight.colors.copy(moduleSurface = 0xFFE4E4E4, moduleGuide = 0xFFBEBEBE, moduleInk = 0xFF111111),
            shape = ShapeTokens(moduleStyle = ModuleStyle.frosted, tileCornerDp = 24f, moduleCornerDp = 40f),
            typography = defaults.copy(numeralFamily = "Doto", countDot = TypeToken(30f, 700, 1f)),
        )
        assertEquals(skin, SkinJson.decode(SkinJson.encode(skin)))
    }

    @Test fun invalidNewTokensRejectWholeSkin() {
        val json = SkinJson.encode(Skin.Midnight)
        for (bad in listOf(
            json.replace("\"solid\"", "\"liquid\""),
            json.replace("\"numeralFamily\":\"\"", "\"numeralFamily\":\"Unknown\""),
            json.replace("\"tileCornerDp\":20.0", "\"tileCornerDp\":-1"),
            json.replace("\"moduleCornerDp\":32.0", "\"moduleCornerDp\":-1"),
            json.replace("\"sizeSp\":56.0", "\"sizeSp\":23"),
            json.replace("\"moduleInk\"", "\"unknownModuleInk\""),
        )) {
            assertNotEquals(json, bad)
            assertTrue(runCatching { SkinJson.decode(bad) }.isFailure)
        }
    }

    @Test fun accentIsAMarkAtThreeToOneAndModuleInkNeedsSeven() {
        val base = Skin.Midnight.copy(colors = Skin.Midnight.colors.copy(bg = 0xFF000000, surface = 0xFF000000))
        // On black: #565656 is about 2.9:1, #5C5C5C about 3.2:1 (checked below).
        val low = base.copy(colors = base.colors.copy(accent = 0xFF565656))
        val ok = base.copy(colors = base.colors.copy(accent = 0xFF5C5C5C))
        assertTrue(SkinSafety.accentContrast(low) in 2.8..3.0)
        assertTrue(SkinSafety.accentContrast(ok) in 3.1..3.3)
        assertFalse(SkinSafety.accentMarkReadable(low))
        assertFalse(SkinSafety.readable(low))
        assertTrue(SkinSafety.accentMarkReadable(ok))
        val lowInk = ok.copy(colors = ok.colors.copy(moduleSurface = 0xFF000000, moduleInk = 0xFF888888))
        assertTrue(SkinSafety.moduleInkContrast(lowInk) < 7)
        assertFalse(SkinSafety.readable(lowInk))
        val goodInk = lowInk.copy(colors = lowInk.colors.copy(moduleInk = 0xFF999999))
        assertTrue(SkinSafety.readable(goodInk))
        val invisibleInk = goodInk.copy(colors = goodInk.colors.copy(moduleInk = 0x00999999))
        assertFalse(SkinSafety.readable(invisibleInk))
        val invisibleAccent = goodInk.copy(colors = goodInk.colors.copy(accent = 0x005C5C5C))
        assertFalse(SkinSafety.accentMarkReadable(invisibleAccent))
        assertEquals(3.0, SkinSafety.MinTextContrast, 0.0)
        assertEquals(3.0, SkinSafety.MinAccentMarkContrast, 0.0)
        assertEquals(7.0, SkinSafety.MinModuleInkContrast, 0.0)
    }

    @Test fun lowContrastSecondaryTextStillFails() {
        val dim = Skin.Midnight.copy(colors = Skin.Midnight.colors.copy(textSecondary = Skin.Midnight.colors.surface))
        assertFalse(SkinSafety.readable(dim))
    }

    @Test fun designMossAndGraphiteColoursAreReadable() {
        val moss = Skin.Midnight.copy(id = "moss", colors = Skin.Midnight.colors.copy(
            bg = 0xFF06150C, surface = 0xFF0B3D22, accent = 0xFFEE5A2B,
            textPrimary = 0xFFF1F4EC, textSecondary = 0xFFA9B8AC,
            moduleSurface = 0xFFE4E4E4, moduleInk = 0xFF14201A))
        val graphite = Skin.Midnight.copy(id = "graphite", colors = Skin.Midnight.colors.copy(
            bg = 0xFF000000, surface = 0xFF2A2A2A, accent = 0xFFE8431F,
            textPrimary = 0xFFE4E4E4, textSecondary = 0xFF8E8E8E,
            moduleSurface = 0xFFE4E4E4, moduleInk = 0xFF111111))
        for (skin in listOf(moss, graphite)) {
            assertTrue("${skin.id} accent ${SkinSafety.accentContrast(skin)}", SkinSafety.accentMarkReadable(skin))
            assertTrue("${skin.id} text ${SkinSafety.textContrast(skin)}", SkinSafety.textContrast(skin) >= SkinSafety.MinTextContrast)
            assertTrue("${skin.id} ink", SkinSafety.moduleInkContrast(skin) >= SkinSafety.MinModuleInkContrast)
            assertTrue(skin.id, SkinSafety.readable(skin))
        }
    }
}
