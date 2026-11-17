package com.brightblu.parc.feature.search

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T259: a name typed in the other script still finds the app, without changing its displayed label. */
class T259TransliterationTest {

    private fun rank(query: String, vararg docs: SearchDoc): List<SearchHit> =
        SearchRanker.rank(query, SearchIndex.of(docs.toList()))

    private fun doc(label: String, id: String = "p.$label/Main@0") =
        SearchDoc(AppId(id), label, "com.example.${label.filter { it.isLetterOrDigit() }.lowercase()}")

    private fun app(label: String, packageName: String, id: String = "p.$label/Main@0") =
        com.brightblu.parc.model.AppEntry(AppId(id), label, packageName, isSystem = false, installTime = 0)

    @Test fun `the tables spell the common names both ways`() {
        assertEquals("yandeks", Transliteration.toLatin("Яндекс"))
        assertEquals("telegram", Transliteration.toLatin("Телеграм"))
        assertEquals("телеграм", Transliteration.toCyrillic("telegram"))
        assertEquals("яндекс", Transliteration.toCyrillic("yandex"))
        assertEquals("сбербанк", Transliteration.toCyrillic("sberbank"))
    }

    @Test fun `latin typing finds a cyrillic name`() {
        val yandex = doc("Яндекс")
        assertEquals(listOf(yandex.appId), rank("yandex", yandex).map { it.appId })
        assertEquals(listOf(yandex.appId), rank("yandeks", yandex).map { it.appId })

        val telegram = doc("Телеграм")
        assertEquals(listOf(telegram.appId), rank("telegram", telegram).map { it.appId })
    }

    @Test fun `cyrillic typing finds a latin name`() {
        val telegram = doc("Telegram")
        assertEquals(listOf(telegram.appId), rank("телеграм", telegram).map { it.appId })
    }

    @Test fun `the latin form of a name with a soft sign still matches`() {
        val tinkoff = doc("Тинькофф")
        assertEquals(listOf(tinkoff.appId), rank("tinkoff", tinkoff).map { it.appId })
    }

    @Test fun `a plain latin query is not turned into noise`() {
        val docs = listOf(doc("Clock"), doc("Maps"), doc("Files"))
        val hits = rank("c", *docs.toTypedArray())
        assertEquals(1, hits.size)
        assertEquals("Clock", docs.first { it.appId == hits.single().appId }.label)
    }

    @Test fun `an alias is searchable like a keyword`() {
        val bank = doc("Money")
        val docs = SearchIndexBuilder.build(
            listOf(app("Money", "com.example.bank", bank.appId.value), app("Maps", "com.example.maps")),
            com.brightblu.parc.model.ParcLayout(),
            mapOf(bank.appId.value to "bank"),
        )
        val hits = SearchRanker.rank("bank", SearchIndex.of(docs))
        assertEquals(listOf(bank.appId), hits.map { it.appId })
        assertTrue(hits.single().matchRanges.isEmpty())
    }
}
