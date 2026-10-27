package com.brightblu.parc.feature.search

import com.brightblu.parc.model.fakes.FakeAppIndex
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import com.brightblu.parc.model.fakes.FakeUsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchIndexerTest {

    private val one = app("Alpha One")
    private val two = app("Alpha Two")

    private fun TestScope.collectorScope(): CoroutineScope =
        CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private fun ids(hits: List<com.brightblu.parc.model.SearchHit>) = hits.map { it.appId }

    @Test
    fun `indexes the app list and the layout when they first arrive`() = runTest {
        val scope = collectorScope()
        val engine = LabelSearchEngine()
        val indexer = SearchIndexer(
            FakeAppIndex(listOf(one, two)),
            FakeLayoutRepository(),
            FakeUsageRepository(),
            engine,
            scope,
        )
        advanceUntilIdle()
        assertEquals(2, engine.size)
        assertEquals(listOf(one.id), ids(engine.query("one")))
        indexer.cancel()
        scope.cancel()
    }

    @Test
    fun `a layout change removes a hidden app from the index`() = runTest {
        val scope = collectorScope()
        val engine = LabelSearchEngine()
        val layout = FakeLayoutRepository()
        val indexer = SearchIndexer(
            FakeAppIndex(listOf(one, two)),
            layout,
            FakeUsageRepository(),
            engine,
            scope,
        )
        advanceUntilIdle()
        layout.hide(one.id)
        advanceUntilIdle()
        assertTrue(engine.query("one").isEmpty())
        assertEquals(1, engine.size)
        indexer.cancel()
        scope.cancel()
    }

    @Test
    fun `a recorded launch becomes a usage boost`() = runTest {
        val scope = collectorScope()
        val engine = LabelSearchEngine()
        val usage = FakeUsageRepository()
        val indexer = SearchIndexer(
            FakeAppIndex(listOf(one, two)),
            FakeLayoutRepository(),
            usage,
            engine,
            scope,
        )
        advanceUntilIdle()
        assertEquals(one.id, ids(engine.query("alpha")).first())
        usage.recordLaunch(two.id)
        advanceUntilIdle()
        assertEquals(two.id, ids(engine.query("alpha")).first())
        indexer.cancel()
        scope.cancel()
    }

    @Test
    fun `cancelling stops following the app index`() = runTest {
        val scope = collectorScope()
        val engine = LabelSearchEngine()
        val layout = FakeLayoutRepository()
        val indexer = SearchIndexer(
            FakeAppIndex(listOf(one, two)),
            layout,
            FakeUsageRepository(),
            engine,
            scope,
        )
        advanceUntilIdle()
        indexer.cancel()
        layout.hide(one.id)
        advanceUntilIdle()
        assertEquals(2, engine.size)
        scope.cancel()
    }
}
