package com.brightblu.parc.core.data.icons

import android.graphics.Bitmap
import androidx.datastore.preferences.core.preferencesOf
import com.brightblu.parc.core.data.FakePreferencesStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IconPackRepositoryTest {

    private class NullBitmapSource : IconPackSource<Bitmap> {
        var packsResult: List<IconPack> = emptyList()
        var packsFailure: RuntimeException? = null
        val filterRequests = mutableListOf<String>()
        var filterResult: Map<String, String> = emptyMap()
        var drawableCalls = 0
            private set

        override fun packs(): List<IconPack> {
            packsFailure?.let { throw it }
            return packsResult
        }

        override fun appFilter(packageName: String): Map<String, String> {
            filterRequests.add(packageName)
            return filterResult
        }

        override fun drawable(packageName: String, drawableName: String, sizePx: Int): Bitmap? {
            drawableCalls++
            return null
        }
    }

    private class Recorder {
        val calls = mutableListOf<Pair<String, Int>>()
        fun icon(component: String, sizePx: Int): Bitmap? {
            calls.add(component to sizePx)
            return null
        }
    }

    private fun repository(
        store: FakePreferencesStore,
        source: NullBitmapSource,
        scope: TestScope,
        fallback: Recorder = Recorder(),
    ): IconPackRepository = IconPackRepository(
        store = store,
        source = source,
        fallback = fallback::icon,
        scope = scope,
        dispatcher = StandardTestDispatcher(scope.testScheduler),
        logWarn = {},
    )

    @Test
    fun `installed packs load after construction without blocking`() = runTest {
        val source = NullBitmapSource().apply {
            packsResult = listOf(IconPack("pack.one", "One"))
        }
        val repo = repository(FakePreferencesStore(), source, this)
        assertTrue(repo.packs.value.isEmpty())

        advanceUntilIdle()
        assertEquals(listOf(IconPack("pack.one", "One")), repo.packs.value)
        repo.close()
    }

    @Test
    fun `a failing pack list leaves an empty list`() = runTest {
        val source = NullBitmapSource().apply { packsFailure = IllegalStateException("boom") }
        val repo = repository(FakePreferencesStore(), source, this)

        advanceUntilIdle()
        assertTrue(repo.packs.value.isEmpty())
        repo.close()
    }

    @Test
    fun `refresh reloads the pack list`() = runTest {
        val source = NullBitmapSource().apply {
            packsResult = listOf(IconPack("pack.one", "One"))
        }
        val repo = repository(FakePreferencesStore(), source, this)
        advanceUntilIdle()

        source.packsResult = listOf(IconPack("pack.two", "Two"))
        repo.refresh()

        assertEquals(listOf(IconPack("pack.two", "Two")), repo.packs.value)
        repo.close()
    }

    @Test
    fun `selection is published and persisted`() = runTest {
        val store = FakePreferencesStore()
        val repo = repository(store, NullBitmapSource(), this)
        advanceUntilIdle()

        repo.select("pack.two")
        assertEquals("pack.two", repo.selection.value)

        repo.close()
        val restored = repository(store, NullBitmapSource(), this)
        advanceUntilIdle()
        assertEquals("pack.two", restored.selection.value)
        restored.close()
    }

    @Test
    fun `selecting none clears the stored key`() = runTest {
        val store = FakePreferencesStore()
        val repo = repository(store, NullBitmapSource(), this)
        advanceUntilIdle()

        repo.select("pack.two")
        repo.select(null)
        assertNull(repo.selection.value)
        repo.close()

        val restored = repository(store, NullBitmapSource(), this)
        advanceUntilIdle()
        assertNull(restored.selection.value)
        restored.close()
    }

    @Test
    fun `a blank stored selection means none`() = runTest {
        val store = FakePreferencesStore(
            preferencesOf(IconPackRepository.KeySelected to "   "),
        )
        val repo = repository(store, NullBitmapSource(), this)

        advanceUntilIdle()
        assertNull(repo.selection.value)
        repo.close()
    }

    @Test
    fun `no pack selected asks the app icon and never reads a pack`() = runTest {
        val source = NullBitmapSource()
        val fallback = Recorder()
        val repo = repository(FakePreferencesStore(), source, this, fallback)
        advanceUntilIdle()

        assertNull(repo.iconFor("com.example/com.example.Main", 112))
        assertEquals(listOf<Pair<String, Int>>("com.example/com.example.Main" to 112), fallback.calls)
        assertTrue(source.filterRequests.isEmpty())
        assertEquals(0, source.drawableCalls)
        repo.close()
    }

    @Test
    fun `a selected pack is read and a missing drawable falls back`() = runTest {
        val source = NullBitmapSource().apply {
            filterResult = mapOf("com.example/com.example.main" to "themed")
        }
        val fallback = Recorder()
        val store = FakePreferencesStore(
            preferencesOf(IconPackRepository.KeySelected to "pack.one"),
        )
        val repo = repository(store, source, this, fallback)
        advanceUntilIdle()

        assertNull(repo.iconFor("com.example/com.example.Main", 96))
        assertEquals(listOf("pack.one"), source.filterRequests)
        assertEquals(1, source.drawableCalls)
        assertEquals(listOf<Pair<String, Int>>("com.example/com.example.Main" to 96), fallback.calls)
        assertEquals(0, repo.cachedBytes())
        repo.close()
    }

    @Test
    fun `packs are one per package sorted by label`() {
        val packs = listOf(
            IconPack("pack.b", "Zebra"),
            IconPack("pack.a", "alpha"),
            IconPack("pack.b", "Duplicate"),
            IconPack("  ", "Blank"),
        )

        assertEquals(
            listOf("pack.a", "pack.b"),
            IconPackRules.distinctSorted(packs).map { it.packageName },
        )
        assertEquals(
            "alpha",
            IconPackRules.distinctSorted(packs).first().label,
        )
    }

    @Test
    fun `a selection normalises to a trimmed package or none`() {
        assertEquals("pack.one", IconPackRules.normalizeSelection("  pack.one "))
        assertNull(IconPackRules.normalizeSelection(null))
        assertNull(IconPackRules.normalizeSelection(""))
        assertNull(IconPackRules.normalizeSelection("   "))
    }
}
