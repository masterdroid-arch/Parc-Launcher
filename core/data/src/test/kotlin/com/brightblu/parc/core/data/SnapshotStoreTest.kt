package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotStoreTest {

    private fun newStore(): SnapshotStore {
        val dir = Files.createTempDirectory("parc-snapshot").toFile()
        return SnapshotStore(File(dir, "app_snapshot.json"))
    }

    @Test
    fun `round trip preserves ids and labels`() {
        val store = newStore()
        val entries = listOf(
            AppEntry(AppId("com.a/com.a.Main@0"), "Alpha", "com.a", false, 0L),
            AppEntry(AppId("com.b/com.b.Main@10"), "Beta", "com.b", true, 123L),
        )

        store.write(entries)

        val read = store.read()
        assertEquals(entries.map { it.id to it.label }, read.map { it.id to it.label })
    }

    @Test
    fun `missing file reads empty`() {
        assertTrue(newStore().read().isEmpty())
    }

    @Test
    fun `corrupt file reads empty`() {
        val dir = Files.createTempDirectory("parc-snapshot").toFile()
        val file = File(dir, "app_snapshot.json")
        file.writeText("not json")
        assertTrue(SnapshotStore(file).read().isEmpty())
    }

    @Test
    fun `unsupported version reads empty`() {
        val dir = Files.createTempDirectory("parc-snapshot").toFile()
        val file = File(dir, "app_snapshot.json")
        file.writeText("""{"version":2,"entries":[]}""")
        assertTrue(SnapshotStore(file).read().isEmpty())
    }
}
