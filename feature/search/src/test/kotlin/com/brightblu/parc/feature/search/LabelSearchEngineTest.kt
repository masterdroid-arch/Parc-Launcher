package com.brightblu.parc.feature.search

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelSearchEngineTest {

    private val engine = LabelSearchEngine()

    private fun ids(hits: List<SearchHit>) = hits.map { it.appId }

    @Test
    fun `query finds an app after update`() {
        val instagram = doc("Instagram", "com.instagram.android")
        engine.update(listOf(instagram))
        assertEquals(listOf(instagram.appId), ids(engine.query("insta")))
        assertEquals(1, engine.size)
    }

    @Test
    fun `update replaces the whole index`() {
        val first = doc("First")
        val second = doc("Second")
        engine.update(listOf(first))
        engine.update(listOf(second))
        assertTrue(engine.query("first").isEmpty())
        assertEquals(listOf(second.appId), ids(engine.query("second")))
        assertEquals(1, engine.size)
    }

    @Test
    fun `an empty update leaves nothing to find`() {
        engine.update(listOf(doc("First")))
        engine.update(emptyList())
        assertEquals(0, engine.size)
        assertTrue(engine.query("first").isEmpty())
    }

    @Test
    fun `boosts favour the most recent launch`() {
        val one = doc("Alpha One")
        val two = doc("Alpha Two")
        engine.update(listOf(one, two))
        assertEquals(one.appId, ids(engine.query("alpha")).first())
        engine.updateBoosts(SearchBoosts.fromRecents(listOf(two.appId)))
        assertEquals(two.appId, ids(engine.query("alpha")).first())
    }

    @Test
    fun `clearing boosts restores the unboosted order`() {
        val one = doc("Alpha One")
        val two = doc("Alpha Two")
        engine.update(listOf(one, two))
        engine.updateBoosts(SearchBoosts.fromRecents(listOf(two.appId)))
        engine.updateBoosts(emptyMap())
        assertEquals(one.appId, ids(engine.query("alpha")).first())
    }

    @Test
    fun `boosts survive an index update`() {
        val one = doc("Alpha One")
        val two = doc("Alpha Two")
        engine.updateBoosts(SearchBoosts.fromRecents(listOf(two.appId)))
        engine.update(listOf(one, two))
        assertEquals(two.appId, ids(engine.query("alpha")).first())
    }

    @Test
    fun `russian and accented queries find their apps end to end`() {
        val yandex = app("Яндекс", "ru.yandex.searchplugin")
        val cafe = app("Café", "com.example.cafe")
        engine.update(SearchIndexBuilder.build(listOf(yandex, cafe), layoutOf()))
        assertEquals(listOf(yandex.id), ids(engine.query("яндекс")))
        assertEquals(listOf(yandex.id), ids(engine.query("ЯНДЕКС")))
        assertEquals(listOf(cafe.id), ids(engine.query("cafe")))
    }

    @Test
    fun `an unknown boost id is ignored`() {
        val one = doc("Alpha One")
        engine.update(listOf(one))
        engine.updateBoosts(mapOf(AppId("ghost") to 100.0))
        assertEquals(listOf(one.appId), ids(engine.query("alpha")))
    }
}
