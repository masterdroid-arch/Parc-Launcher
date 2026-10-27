package com.brightblu.parc.core.data

import android.graphics.Bitmap
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherAppIndexTest {

    private class NullIconProducer : IconProducer<Bitmap> {
        override suspend fun rasterise(id: AppId, sizePx: Int): Bitmap? = null
        override suspend fun letterTile(label: String, sizePx: Int): Bitmap? = null
    }

    private fun newStore(): SnapshotStore {
        val dir = Files.createTempDirectory("parc-index").toFile()
        return SnapshotStore(File(dir, "app_snapshot.json"))
    }

    private fun build(
        source: FakeLauncherSource,
        store: SnapshotStore,
        scope: kotlinx.coroutines.test.TestScope,
    ): LauncherAppIndex = LauncherAppIndex(
        source = source,
        scope = scope,
        snapshot = store,
        iconProducer = NullIconProducer(),
        ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        debounceMillis = 300,
        logWarn = {},
    )

    @Test
    fun `apps are seeded from the snapshot before the source answers`() = runTest {
        val store = newStore()
        store.write(listOf(AppEntry(AppId("com.a/com.a.Main@0"), "Alpha", "com.a", false, 0L)))
        val source = FakeLauncherSource(listOf(sourceActivity("com.z/com.z.Zed", "Zed")))
        val index = build(source, store, this)

        assertEquals(listOf("Alpha"), index.apps.value.map { it.label })

        advanceUntilIdle()
        assertEquals(listOf("Zed"), index.apps.value.map { it.label })
        index.close()
    }

    @Test
    fun `snapshot is rewritten after a change`() = runTest {
        val store = newStore()
        val source = FakeLauncherSource(listOf(sourceActivity("com.z/com.z.Zed", "Zed")))
        val index = build(source, store, this)

        advanceUntilIdle()

        assertEquals(listOf("Zed"), store.read().map { it.label })
        index.close()
    }

    @Test
    fun `launch delegates and swallows failures`() = runTest {
        val source = FakeLauncherSource()
        val index = build(source, newStore(), this)

        index.launch(AppId("p/c@0"))
        assertEquals(listOf("p/c" to 0L), source.launched)

        source.launchFailure = SecurityException("denied")
        index.launch(AppId("p/d@0"))
        assertTrue(source.launched.size == 1)
        index.close()
    }

    @Test
    fun `shortcuts delegate to the provider`() = runTest {
        val source = FakeLauncherSource().apply {
            shortcutsResult = listOf(SourceShortcut("s1", "One"))
        }
        val index = build(source, newStore(), this)

        val shortcuts = index.shortcuts(AppId("p/c@0"))
        assertEquals(1, shortcuts.size)
        assertEquals("s1", shortcuts.first().id)
        index.close()
    }
}
