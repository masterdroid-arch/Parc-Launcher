package com.brightblu.parc.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexBuilderTest {

    private val reddit = app("Reddit", "com.reddit.frontpage")
    private val telegram = app("Telegram", "org.telegram.messenger")
    private val maps = app("Maps", "com.google.android.apps.maps")

    @Test
    fun `builds one doc per installed app with label and package`() {
        val docs = SearchIndexBuilder.build(listOf(reddit, telegram), layoutOf())
        assertEquals(listOf("Reddit", "Telegram"), docs.map { it.label })
        assertEquals(listOf("com.reddit.frontpage", "org.telegram.messenger"), docs.map { it.packageName })
        assertTrue(docs.all { it.keywords.isEmpty() })
    }

    @Test
    fun `hidden apps are not indexed`() {
        val docs = SearchIndexBuilder.build(
            listOf(reddit, telegram),
            layoutOf(hidden = setOf(reddit.id)),
        )
        assertEquals(listOf(telegram.id), docs.map { it.appId })
    }

    @Test
    fun `a locked folder's name and lock tag never become keywords (T98)`() {
        val locked = folder("vault", "Vault", apps = listOf(reddit))
            .copy(tags = setOf("secret", com.brightblu.parc.model.LockedFolderTag))
        val docs = SearchIndexBuilder.build(listOf(reddit, maps), layoutOf(locked))
        assertTrue(docs.all { it.keywords.isEmpty() })
    }

    @Test
    fun `folder name is a keyword for every member`() {
        val docs = SearchIndexBuilder.build(
            listOf(reddit, telegram, maps),
            layoutOf(folder("social", "Social", apps = listOf(reddit, telegram))),
        )
        val byId = docs.associateBy { it.appId }
        assertEquals(listOf("Social"), byId.getValue(reddit.id).keywords)
        assertEquals(listOf("Social"), byId.getValue(telegram.id).keywords)
        assertTrue(byId.getValue(maps.id).keywords.isEmpty())
    }

    @Test
    fun `folder tags become keywords and duplicates are dropped`() {
        val docs = SearchIndexBuilder.build(
            listOf(reddit),
            layoutOf(
                folder("social", "Social", tags = setOf("Social", "chat"), apps = listOf(reddit)),
                folder("news", "News", tags = setOf("chat"), apps = listOf(reddit)),
            ),
        )
        assertEquals(listOf("Social", "chat", "News"), docs.single().keywords)
    }

    @Test
    fun `a blank folder name is not a keyword`() {
        val docs = SearchIndexBuilder.build(
            listOf(reddit),
            layoutOf(folder("blank", "   ", apps = listOf(reddit))),
        )
        assertTrue(docs.single().keywords.isEmpty())
    }

    @Test
    fun `an app in no folder still gets a doc`() {
        val docs = SearchIndexBuilder.build(
            listOf(maps),
            layoutOf(folder("social", "Social", apps = listOf(reddit))),
        )
        assertEquals(listOf(maps.id), docs.map { it.appId })
    }
}
