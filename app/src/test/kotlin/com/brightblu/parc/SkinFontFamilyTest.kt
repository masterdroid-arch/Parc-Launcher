package com.brightblu.parc

import com.brightblu.parc.model.ParcFonts
import com.brightblu.parc.model.Phosphor
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.TypographyTokens
import com.brightblu.parc.model.textFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SkinFontFamilyTest {
    private val figtreeSkin = Phosphor.Skin.copy(typography = TypographyTokens())

    @Test fun figtreeSkinGetsFigtree() {
        assertEquals("Figtree", figtreeSkin.typography.family)
        assertEquals(ParcFonts.Figtree, figtreeSkin.textFamily)
    }

    @Test fun monospaceSkinGetsFigtree() {
        assertEquals(ParcFonts.Figtree, Phosphor.Skin.textFamily)
    }

    @Test fun monospaceFallbackGetsFigtree() {
        val skin = figtreeSkin.copy(typography = TypographyTokens(fallbackFamily = "monospace"))
        assertEquals(ParcFonts.Figtree, skin.textFamily)
    }

    @Test fun emptyFamilyDefaultsToFigtree() {
        val skin = figtreeSkin.copy(typography = TypographyTokens(family = ""))
        assertEquals(ParcFonts.Figtree, skin.textFamily)
    }

    @Test fun unknownFamilyDefaultsToFigtree() {
        val skin = figtreeSkin.copy(typography = TypographyTokens(family = "serif"))
        assertEquals(ParcFonts.Figtree, skin.textFamily)
    }

    @Test fun legacySystemFamilyGetsFigtree() {
        val fromFamily = figtreeSkin.copy(typography = TypographyTokens(family = "system"))
        val fromFallback = figtreeSkin.copy(typography = TypographyTokens(family = "", fallbackFamily = "system"))
        assertEquals(ParcFonts.Figtree, fromFamily.textFamily)
        assertEquals(ParcFonts.Figtree, fromFallback.textFamily)
    }

    @Test fun dotIsNeverTheTextFont() {
        assertNotEquals(ParcFonts.Doto, figtreeSkin.textFamily)
        assertNotEquals(ParcFonts.Doto, Phosphor.Skin.textFamily)
    }
}
