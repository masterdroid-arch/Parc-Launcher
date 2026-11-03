package com.brightblu.parc

import androidx.compose.ui.text.font.FontFamily
import com.brightblu.parc.model.ParcFonts
import com.brightblu.parc.model.Phosphor
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.TypographyTokens
import com.brightblu.parc.model.textFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkinFontFamilyTest {
    private val figtreeSkin = Phosphor.Skin.copy(typography = TypographyTokens())

    @Test fun figtreeSkinGetsFigtree() {
        assertEquals("Figtree", figtreeSkin.typography.family)
        assertEquals(ParcFonts.Figtree, figtreeSkin.textFamily)
    }

    @Test fun monospaceSkinGetsMonospace() {
        assertEquals(FontFamily.Monospace, Phosphor.Skin.textFamily)
    }

    @Test fun monospaceFallbackWins() {
        val skin = figtreeSkin.copy(typography = TypographyTokens(fallbackFamily = "monospace"))
        assertEquals(FontFamily.Monospace, skin.textFamily)
    }

    @Test fun unknownFamilyLeavesSystemFont() {
        val skin = figtreeSkin.copy(typography = TypographyTokens(family = "serif"))
        assertNull(skin.textFamily)
    }

    @Test fun dotIsNeverTheTextFont() {
        assertNotEquals(ParcFonts.Doto, figtreeSkin.textFamily)
        assertNotEquals(ParcFonts.Doto, Phosphor.Skin.textFamily)
    }
}
