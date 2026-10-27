package com.brightblu.parc.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbcIndexTest {

    @Test
    fun `groups apps by their first letter`() {
        val index = AbcIndex.of(listOf(app("Alpha"), app("Beta"), app("Bravo")))
        assertEquals(listOf('A', 'B'), index.keys)
        assertEquals(listOf(1, 2), index.sections.map { it.apps.size })
    }

    @Test
    fun `sections are ordered from a to z`() {
        val index = AbcIndex.of(listOf(app("Zulu"), app("Alpha"), app("Mike")))
        assertEquals(listOf('A', 'M', 'Z'), index.keys)
    }

    @Test
    fun `the hash bucket is last and holds labels without a letter`() {
        val index = AbcIndex.of(listOf(app("2048"), app("Zulu"), app("+++")))
        assertEquals(listOf('Z', AbcIndex.Hash), index.keys)
        assertEquals(2, index.sections.last().apps.size)
    }

    @Test
    fun `cyrillic and greek sections follow latin`() {
        val index = AbcIndex.of(listOf(app("Яндекс"), app("Alpha"), app("Δelta")))
        assertEquals(listOf('A', 'Δ', 'Я'), index.keys)
    }

    @Test
    fun `diacritics group with their plain letter`() {
        val index = AbcIndex.of(listOf(app("Über"), app("Łódź")))
        assertEquals(listOf('L', 'U'), index.keys)
    }

    @Test
    fun `within a section apps are case-insensitively ordered`() {
        val index = AbcIndex.of(listOf(app("apple"), app("Alpha")))
        assertEquals(listOf("Alpha", "apple"), index.sections.single().apps.map { it.label })
    }

    @Test
    fun `equal labels are ordered by id`() {
        val first = app("Beta", id = "a/a.Main@0")
        val second = app("Beta", id = "b/b.Main@0")
        val index = AbcIndex.of(listOf(second, first))
        assertEquals(listOf(first.id, second.id), index.sections.single().apps.map { it.id })
    }

    @Test
    fun `hidden apps are dropped`() {
        val alpha = app("Alpha")
        val index = AbcIndex.of(listOf(alpha, app("Beta")), hidden = setOf(alpha.id))
        assertEquals(listOf('B'), index.keys)
    }

    @Test
    fun `an empty list produces no sections`() {
        val index = AbcIndex.of(emptyList())
        assertTrue(index.sections.isEmpty())
        assertEquals(AbcIndex.Empty.keys, index.keys)
    }

    @Test
    fun `flat offsets count headers and rows`() {
        val index = AbcIndex.of(listOf(app("Alpha"), app("Beta"), app("Bravo"), app("Charlie")))
        assertEquals(listOf(0, 2, 5), (0..2).map { index.flatOffsetOf(it) })
    }

    @Test
    fun `section key skips leading punctuation and folds diacritics`() {
        assertEquals('G', AbcIndex.sectionKey("(Google)"))
        assertEquals('U', AbcIndex.sectionKey("Über"))
        assertEquals(AbcIndex.Hash, AbcIndex.sectionKey("2048"))
        assertEquals(AbcIndex.Hash, AbcIndex.sectionKey(""))
    }

    @Test
    fun `section index lookup`() {
        val index = AbcIndex.of(listOf(app("Alpha"), app("Bravo")))
        assertEquals(1, index.sectionIndexOf('B'))
        assertEquals(-1, index.sectionIndexOf('Q'))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `flat offset rejects an unknown section`() {
        AbcIndex.Empty.flatOffsetOf(0)
    }
}
