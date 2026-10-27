package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IconCacheManagerTest {

    private class FakeIcon(val sizeBytes: Int)

    private class FakeProducer(
        private val rasterSize: Int = 100,
        private val letterSize: Int = 60,
        var failRasterise: Boolean = false,
        var failLetter: Boolean = false,
    ) : IconProducer<FakeIcon> {
        var rasteriseCalls = 0
            private set
        var letterCalls = 0
            private set
        var lastLabel: String? = null
            private set

        override suspend fun rasterise(id: AppId, sizePx: Int): FakeIcon? {
            rasteriseCalls++
            if (failRasterise) throw RuntimeException("no drawable")
            return FakeIcon(rasterSize)
        }

        override suspend fun letterTile(label: String, sizePx: Int): FakeIcon? {
            letterCalls++
            lastLabel = label
            if (failLetter) throw RuntimeException("no tile")
            return FakeIcon(letterSize)
        }
    }

    private fun manager(
        producer: IconProducer<FakeIcon>,
        scope: TestScope,
        label: String? = null,
        loaded: MutableList<AppId> = mutableListOf(),
        maxBytes: Int = 1000,
    ): IconCacheManager<FakeIcon> = IconCacheManager(
        producer = producer,
        sizeOf = { it.sizeBytes },
        labelOf = { label },
        scope = scope,
        dispatcher = StandardTestDispatcher(scope.testScheduler),
        onLoaded = { loaded.add(it) },
        maxBytes = maxBytes,
    )

    @Test
    fun `get is non-blocking and announces the loaded id`() = runTest {
        val producer = FakeProducer()
        val loaded = mutableListOf<AppId>()
        val cache = manager(producer, this, loaded = loaded)
        val id = AppId("p/c@0")

        assertNull(cache.get(id, 112))
        assertEquals(0, producer.rasteriseCalls)

        advanceUntilIdle()
        assertEquals(1, producer.rasteriseCalls)
        assertEquals(listOf(id), loaded)
        assertNotNull(cache.get(id, 112))
        assertEquals(1, producer.rasteriseCalls)
    }

    @Test
    fun `duplicate requests collapse into one load`() = runTest {
        val producer = FakeProducer()
        val cache = manager(producer, this)
        val id = AppId("p/c@0")

        repeat(4) { cache.get(id, 112) }
        advanceUntilIdle()
        assertEquals(1, producer.rasteriseCalls)
    }

    @Test
    fun `preload warms the cache`() = runTest {
        val producer = FakeProducer()
        val cache = manager(producer, this)
        val a = AppId("p/a@0")
        val b = AppId("p/b@0")

        cache.preload(listOf(a, b))
        advanceUntilIdle()
        assertNotNull(cache.get(a, 112))
        assertNotNull(cache.get(b, 112))
        assertEquals(2, producer.rasteriseCalls)
    }

    @Test
    fun `cache evicts by bytes`() = runTest {
        val producer = FakeProducer(rasterSize = 100)
        val cache = manager(producer, this, maxBytes = 250)
        val ids = (1..3).map { AppId("p/c$it@0") }

        ids.forEach { cache.get(it, 112) }
        advanceUntilIdle()

        assertTrue(cache.cachedBytes() <= 250)
        assertEquals(2, cache.cachedKeys().size)
        assertFalse(IconKey(ids[0], 112) in cache.cachedKeys())
        assertTrue(IconKey(ids[2], 112) in cache.cachedKeys())
    }

    @Test
    fun `failed icon yields a letter tile`() = runTest {
        val producer = FakeProducer(letterSize = 70, failRasterise = true)
        val loaded = mutableListOf<AppId>()
        val cache = manager(producer, this, label = "Zed", loaded = loaded)
        val id = AppId("p/c@0")

        assertNull(cache.get(id, 112))
        advanceUntilIdle()

        assertEquals(1, producer.rasteriseCalls)
        assertEquals(1, producer.letterCalls)
        assertEquals("Zed", producer.lastLabel)
        assertEquals(70, cache.get(id, 112)?.sizeBytes)
        assertEquals(listOf(id), loaded)
    }

    @Test
    fun `letter tile failure caches nothing`() = runTest {
        val producer = FakeProducer(failRasterise = true, failLetter = true)
        val loaded = mutableListOf<AppId>()
        val cache = manager(producer, this, loaded = loaded)
        val id = AppId("p/c@0")

        assertNull(cache.get(id, 112))
        advanceUntilIdle()
        assertNull(cache.get(id, 112))
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `first letter is upper-cased or a question mark`() {
        assertEquals('H', firstLetter("hello"))
        assertEquals('H', firstLetter("  hello"))
        assertEquals('7', firstLetter("7zip"))
        assertEquals('?', firstLetter(""))
        assertEquals('?', firstLetter("   "))
    }

    private class GatedProducer : IconProducer<FakeIcon> {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var rasteriseCalls = 0
            private set

        override suspend fun rasterise(id: AppId, sizePx: Int): FakeIcon {
            rasteriseCalls++
            gate.await()
            return FakeIcon(10)
        }

        override suspend fun letterTile(label: String, sizePx: Int): FakeIcon? = null
    }

    @Test
    fun `clear returns the cached ids and empties the cache`() = runTest {
        val cache = manager(FakeProducer(), this)
        val a = AppId("p/a@0")
        val b = AppId("p/b@0")

        cache.get(a, 112)
        cache.get(b, 112)
        advanceUntilIdle()
        assertTrue(cache.cachedBytes() > 0)

        assertEquals(setOf(a, b), cache.clear())
        assertEquals(0, cache.cachedBytes())
        assertTrue(cache.clear().isEmpty())
    }

    @Test
    fun `a load started before clear is discarded and restarted`() = runTest {
        val producer = GatedProducer()
        val loaded = mutableListOf<AppId>()
        val cache = manager(producer, this, loaded = loaded)
        val id = AppId("p/c@0")

        assertNull(cache.get(id, 112))
        runCurrent()
        assertEquals(1, producer.rasteriseCalls)

        assertEquals(emptySet<AppId>(), cache.clear())
        producer.gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, producer.rasteriseCalls)
        assertEquals(listOf(id), loaded)
        assertNotNull(cache.get(id, 112))
        assertEquals(2, producer.rasteriseCalls)
    }

    // ---- OPUS2: a full scene of ~180 apps must not thrash the cache -----------------------------

    /** One "scene build": ask for every icon, then let the loads finish. Returns the loads it caused. */
    private fun TestScope.sceneBuild(cache: IconCacheManager<FakeIcon>, ids: List<AppId>, producer: FakeProducer): Int {
        val before = producer.rasteriseCalls
        ids.forEach { cache.get(it, 144) }
        advanceUntilIdle()
        return producer.rasteriseCalls - before
    }

    @Test
    fun `the old fixed budget keeps reloading a 180 app scene forever`() = runTest {
        val iconBytes = IconCacheBudget.iconBytes(144)
        val producer = FakeProducer(rasterSize = iconBytes)
        val cache = manager(producer, this, maxBytes = IconCacheManager.DefaultMaxBytes)
        val ids = List(180) { AppId("p.$it/Main@0") }
        assertEquals(180, sceneBuild(cache, ids, producer))
        // Every later build still loads icons again: the eviction loop that kept the owner's phone busy.
        repeat(3) { assertTrue(sceneBuild(cache, ids, producer) > 0) }
    }

    @Test
    fun `a budget fitted to the app count loads each icon once`() = runTest {
        val iconBytes = IconCacheBudget.iconBytes(144)
        val producer = FakeProducer(rasterSize = iconBytes)
        val cache = manager(producer, this, maxBytes = IconCacheManager.DefaultMaxBytes)
        val ids = List(180) { AppId("p.$it/Main@0") }
        cache.fitAppCount(ids.size, 144)
        assertEquals(180, sceneBuild(cache, ids, producer))
        repeat(3) { assertEquals(0, sceneBuild(cache, ids, producer)) }
        assertEquals(180 * iconBytes, cache.cachedBytes())
    }

    @Test
    fun `fitting never shrinks below the starting budget and is capped`() = runTest {
        val cache = manager(FakeProducer(), this, maxBytes = 1000)
        cache.fitAppCount(0, 144)
        assertEquals(1000, IconCacheBudget.bytesFor(0, 144, 1000))
        assertEquals((200 * IconCacheBudget.iconBytes(144) * IconCacheBudget.Headroom).toInt(),
            IconCacheBudget.bytesFor(200, 144, IconCacheManager.DefaultMaxBytes))
        assertEquals(IconCacheManager.DefaultMaxBytes, IconCacheBudget.bytesFor(20, 144, IconCacheManager.DefaultMaxBytes))
        assertEquals(IconCacheBudget.CeilingBytes, IconCacheBudget.bytesFor(100_000, 144, IconCacheManager.DefaultMaxBytes))
    }
}
