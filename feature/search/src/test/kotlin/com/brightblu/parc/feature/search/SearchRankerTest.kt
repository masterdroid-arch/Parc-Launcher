package com.brightblu.parc.feature.search

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.MatchRange
import com.brightblu.parc.model.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankerTest {

    private fun rank(
        query: String,
        vararg docs: SearchDoc,
        boosts: Map<AppId, Double> = emptyMap(),
    ): List<SearchHit> = SearchRanker.rank(query, SearchIndex.of(docs.toList()), boosts)

    private fun labels(hits: List<SearchHit>, docs: List<SearchDoc>): List<String> {
        val byId = docs.associate { it.appId to it.label }
        return hits.map { byId.getValue(it.appId) }
    }

    private val instagram = doc("Instagram", "com.instagram.android")
    private val threads = doc("Threads", "com.instagram.barcelona")

    @Test
    fun `label prefix outranks a package word-prefix`() {
        val docs = listOf(instagram, threads)
        val hits = rank("ins", *docs.toTypedArray())
        assertEquals(listOf("Instagram", "Threads"), labels(hits, docs))
    }

    @Test
    fun `exact match outranks a prefix match`() {
        val exact = doc("Insta")
        val prefix = doc("Instagram")
        val hits = rank("insta", exact, prefix)
        assertEquals(exact.appId, hits.first().appId)
    }

    @Test
    fun `prefix match outranks a word-prefix match`() {
        val prefix = doc("Instagram")
        val wordPrefix = doc("Meta Instagram")
        assertEquals(prefix.appId, rank("insta", wordPrefix, prefix).first().appId)
    }

    @Test
    fun `word-prefix match outranks a substring match`() {
        val wordPrefix = doc("Meta Instagram")
        val substring = doc("XInstagram")
        assertEquals(wordPrefix.appId, rank("insta", substring, wordPrefix).first().appId)
    }

    @Test
    fun `substring match outranks a subsequence match`() {
        val substring = doc("Bitmap")
        val subsequence = doc("My App")
        assertEquals(substring.appId, rank("map", subsequence, substring).first().appId)
    }

    @Test
    fun `subsequence must keep the query order`() {
        assertEquals(1, rank("mps", doc("Maps")).size)
        assertTrue(rank("spm", doc("Maps")).isEmpty())
    }

    @Test
    fun `non-matching query returns nothing`() {
        assertTrue(rank("zzz", instagram, threads).isEmpty())
    }

    @Test
    fun `results are in descending score order`() {
        val hits = rank("ins", threads, instagram)
        assertTrue(hits[0].score > hits[1].score)
    }

    @Test
    fun `equal scores fall back to label then id`() {
        val beta = doc("Beta", id = "b/b.Main@0")
        val betaLowerId = doc("Beta", id = "a/a.Main@0")
        val alpha = doc("Alpha", id = "c/c.Main@0")
        val hits = rank("a", beta, alpha, betaLowerId)
        assertEquals(listOf("Alpha", "Beta", "Beta"), labels(hits, listOf(beta, betaLowerId, alpha)))
        assertEquals(betaLowerId.appId, hits[1].appId)
    }

    @Test
    fun `prefix ranges point into the label`() {
        val hits = rank("insta", instagram)
        assertEquals(listOf(MatchRange(0, 5)), hits.single().matchRanges)
    }

    @Test
    fun `word-prefix ranges point into the label`() {
        val hits = rank("maps", doc("Google Maps"))
        assertEquals(listOf(MatchRange(7, 11)), hits.single().matchRanges)
    }

    @Test
    fun `subsequence ranges merge adjacent characters`() {
        val hits = rank("mps", doc("Maps"))
        assertEquals(listOf(MatchRange(0, 1), MatchRange(2, 4)), hits.single().matchRanges)
    }

    @Test
    fun `plain query matches an accented label and keeps display offsets`() {
        val hits = rank("cafe", doc("Café"))
        assertEquals(listOf(MatchRange(0, 4)), hits.single().matchRanges)
    }

    @Test
    fun `accented query matches a plain label`() {
        assertEquals(1, rank("café", doc("Cafe")).size)
    }

    @Test
    fun `russian query matches a cyrillic label`() {
        val yandex = doc("Яндекс", "ru.yandex.searchplugin")
        val hits = rank("яндекс", yandex)
        assertEquals(listOf(MatchRange(0, 6)), hits.single().matchRanges)
    }

    @Test
    fun `a package-only match reports no label ranges`() {
        val hits = rank("instagram", threads)
        assertEquals(threads.appId, hits.single().appId)
        assertTrue(hits.single().matchRanges.isEmpty())
    }

    @Test
    fun `keyword match surfaces the member app`() {
        val hits = rank("social", doc("Reddit", keywords = listOf("Social")))
        assertEquals(1, hits.size)
        assertTrue(hits.single().matchRanges.isEmpty())
    }

    @Test
    fun `usage boost reorders equal matches`() {
        val one = doc("Alpha One")
        val two = doc("Alpha Two")
        val hits = rank("alpha", one, two, boosts = SearchBoosts.fromRecents(listOf(two.appId)))
        assertEquals(two.appId, hits.first().appId)
    }

    @Test
    fun `usage boost cannot flip a weaker match kind`() {
        val wordPrefix = doc("Meta Insta")
        val prefix = doc("Insta")
        val boost = SearchBoosts.fromRecents(listOf(wordPrefix.appId))
        assertEquals(prefix.appId, rank("insta", wordPrefix, prefix, boosts = boost).first().appId)
    }

    @Test
    fun `blank query returns nothing`() {
        assertTrue(rank("   ", instagram).isEmpty())
        assertTrue(rank("", instagram).isEmpty())
    }

    @Test
    fun `empty index returns nothing`() {
        assertTrue(SearchRanker.rank("ins", emptyList()).isEmpty())
    }

    @Test
    fun `boosts decay with recency position`() {
        val first = AppId("first")
        val second = AppId("second")
        val boosts = SearchBoosts.fromRecents(listOf(first, second))
        assertTrue(boosts.getValue(first) > boosts.getValue(second))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a boost that could flip a match kind`() {
        SearchConfig(maxBoost = 500.0)
    }

    // OPUS3: the owner's "cal" showed Citymapper, Claude, Clock, Planner, Digital Wellbeing first.
    @Test
    fun `cal finds names that start with cal first and no package noise`() {
        val calendar = doc("Calendar", "com.google.android.calendar")
        val calculator = doc("Calculator", "com.google.android.calculator")
        val citymapper = doc("Citymapper", "com.citymapper.app")
        val clock = doc("Clock", "com.google.android.deskclock")
        val planner = doc("Planner", "com.example.planner")
        val wellbeing = doc("Digital Wellbeing", "com.google.android.apps.wellbeing")
        val local = doc("Local News", "org.example.news")
        val docs = listOf(citymapper, clock, planner, wellbeing, local, calendar, calculator)
        val hits = rank("cal", *docs.toTypedArray())
        assertEquals(listOf("Calculator", "Calendar", "Local News"), labels(hits, docs))
    }

    @Test
    fun `a package prefix or word still matches`() {
        val chat = doc("Messenger", "com.example.chat")
        val hits = rank("chat", chat)
        assertEquals(listOf(chat.appId), hits.map { it.appId })
    }

    @Test
    fun `c does not match every app through com`() {
        val docs = listOf(doc("Clock", "com.google.android.deskclock"), doc("Maps", "com.google.android.apps.maps"),
            doc("Files", "com.google.android.documentsui"))
        val got = labels(rank("c", *docs.toTypedArray()), docs)
        assertEquals(got.toString(), listOf("Clock"), got)
        assertTrue("the shared \"com.\" start matches nothing", rank("com", *docs.toTypedArray()).isEmpty())
    }

    @Test
    fun `scattered letters still match inside an app name`() {
        val docs = listOf(doc("Google Maps", "com.google.android.apps.maps"))
        assertEquals(1, rank("gmps", *docs.toTypedArray()).size)
        assertEquals(0, rank("cgl", *docs.toTypedArray()).size)
    }
}
