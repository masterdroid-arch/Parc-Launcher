package com.brightblu.parc.feature.search

import com.brightblu.parc.model.MatchRange
import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    private fun fold(text: String) = TextNormalizer.fold(text)

    @Test
    fun `folds ascii to lowercase`() {
        assertEquals("instagram", fold("Instagram"))
    }

    @Test
    fun `strips latin diacritics`() {
        assertEquals("cafe", fold("Café"))
        assertEquals("uber", fold("Über"))
        assertEquals("mensagens", fold("Mensagens"))
    }

    @Test
    fun `folds cyrillic to lowercase`() {
        assertEquals("яндекс", fold("Яндекс"))
    }

    @Test
    fun `decomposition folds yo to ye and short i to i`() {
        assertEquals("еж", fold("Ёж"))
        assertEquals("мои", fold("мой"))
    }

    @Test
    fun `folds letters with no canonical decomposition`() {
        assertEquals("strasse", fold("Straße"))
        assertEquals("lodz", fold("Łódź"))
        assertEquals("aeon", fold("Æon"))
        assertEquals("o", fold("Ø"))
    }

    @Test
    fun `keeps digits punctuation and emoji`() {
        assertEquals("🅿 maps 2", fold("🅿 Maps 2"))
        assertEquals("2024!", fold("2024!"))
    }

    @Test
    fun `maps a folded surrogate pair back to its source pair`() {
        val normalized = TextNormalizer.normalize("🅿 Maps")
        assertEquals("🅿 maps", normalized.text)
        assertEquals(0, normalized.sourceStartAt(0))
        assertEquals(2, normalized.sourceEndAt(0))
        assertEquals(2, normalized.sourceEndAt(1))
        assertEquals(MatchRange(2, 3), normalized.sourceRange(2, 3))
        assertEquals(MatchRange(3, 4), normalized.sourceRange(3, 4))
    }

    @Test
    fun `maps an expansion back to one source range`() {
        val normalized = TextNormalizer.normalize("Straße")
        assertEquals("strasse", normalized.text)
        assertEquals(MatchRange(4, 5), normalized.sourceRange(4, 6))
        assertEquals(MatchRange(4, 6), normalized.sourceRange(4, 7))
    }

    @Test
    fun `folds an empty string`() {
        assertEquals("", fold(""))
        assertEquals(0, TextNormalizer.normalize("").length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a folded range outside the text`() {
        TextNormalizer.normalize("abc").sourceRange(2, 5)
    }
}
