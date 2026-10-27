package com.brightblu.parc.core.data

import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.LayoutJson
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import java.io.File
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Seed generation migration (T34). The phone already holds a v1 layout in Room, so the first start of
 * this build must preserve it as a file before replacing it, and must do that exactly once.
 */
class LayoutMigrationTest {

    private val alpha = appId("com.alpha/com.alpha.Main")
    private val beta = appId("com.beta/com.beta.Main")
    private val installed = listOf(appEntry(alpha), appEntry(beta))

    /**
     * What an existing install has: v1 slots and one long folder name, from before the T24 rename.
     */
    private val old = ParcLayout(
        version = LayoutVersion,
        arcs = homeArcSpecs().map { spec ->
            if (spec.id == "A1") {
                ArcAssignment(spec, mapOf(0 to Slot.App(alpha)))
            } else {
                ArcAssignment(spec, emptyMap())
            }
        },
        folders = listOf(folder("media", "Music & Media", listOf(alpha))),
        pins = setOf(alpha),
    )

    /** What this build seeds for a fresh install. */
    private val next = ParcLayout(
        version = LayoutVersion,
        arcs = homeArcSpecs().map { spec ->
            if (spec.id == "A2") {
                ArcAssignment(spec, mapOf(0 to Slot.App(alpha), 1 to Slot.App(beta)))
            } else {
                ArcAssignment(spec, emptyMap())
            }
        },
        folders = listOf(folder("life", "Life", listOf(alpha, beta))),
        pins = setOf(alpha),
    )

    /** Harness whose stored layout is [old] and whose marker says generation [marker]. */
    private suspend fun aged(
        marker: Int?,
        backupStore: RecordingBackupStore = RecordingBackupStore(),
    ): RepositoryHarness {
        val h = RepositoryHarness(seed = next, backupStore = backupStore)
        h.repository.load(installed)
        h.repository.importLayout(LayoutJson.encode(old))
        h.generationStore.marker = marker
        return h
    }

    @Test
    fun `a current generation layout is never replaced by the seed`() = runTest {
        // A v-current database already holds a user's own arrangement; T62 must not touch it.
        val h = RepositoryHarness(seed = next)
        h.repository.load(installed)
        val personal = ParcLayout(
            version = LayoutVersion,
            arcs = homeArcSpecs().map { spec ->
                when (spec.id) {
                    "A1" -> ArcAssignment(spec, mapOf(0 to Slot.App(beta), 2 to Slot.App(alpha)))
                    "A3" -> ArcAssignment(spec, mapOf(0 to Slot.Folder(com.brightblu.parc.model.FolderId("work"))))
                    else -> ArcAssignment(spec, emptyMap())
                }
            },
            folders = listOf(folder("work", "Work", listOf(alpha, beta))),
            pins = setOf(beta),
        )
        h.repository.importLayout(LayoutJson.encode(personal))

        val restarted = h.newRepository()
        restarted.load(installed)

        assertEquals(personal, restarted.layout.value)
        assertTrue(h.backupStore.written.isEmpty())
    }

    @Test
    fun `a fresh install stamps the current generation and writes no backup`() = runTest {
        val h = RepositoryHarness(seed = next)

        h.repository.load(installed)

        assertEquals(next, h.repository.layout.value)
        assertEquals(SeedGeneration.Current, h.generationStore.marker)
        assertTrue(h.backupStore.written.isEmpty())
    }

    @Test
    fun `an older generation is backed up once and replaced by the seed`() = runTest {
        val h = aged(marker = 1)

        val migrated = h.newRepository()
        migrated.load(installed)

        assertEquals(1, h.backupStore.written.size)
        assertEquals(1, h.backupStore.written.single().first)
        assertEquals(LayoutJson.encode(old), h.backupStore.written.single().second)
        assertEquals(next, migrated.layout.value)
        assertEquals(SeedGeneration.Current, h.generationStore.marker)
        assertTrue(h.warnings.any { it.contains("layout-v1-test.json") })

        // A later start sees the current generation, so nothing is written a second time.
        h.newRepository().load(installed)

        assertEquals(1, h.backupStore.attempts)
        assertEquals(1, h.backupStore.written.size)
    }

    @Test
    fun `a layout with no generation marker is migrated as generation one`() = runTest {
        val h = aged(marker = null)

        h.newRepository().load(installed)

        assertEquals(1, h.backupStore.written.size)
        assertEquals(LayoutRepositoryImpl.LegacyGeneration, h.backupStore.written.single().first)
        assertEquals(SeedGeneration.Current, h.generationStore.marker)
    }

    @Test
    fun `the old layout is published before it is replaced and is never blanked`() = runTest {
        val h = aged(marker = 1)

        val migrated = h.newRepository()
        migrated.load(emptyList())

        assertEquals(old, migrated.layout.value)
        assertTrue(h.backupStore.written.isEmpty())
        assertEquals(1, h.generationStore.marker)

        migrated.load(installed)

        assertEquals(next, migrated.layout.value)
        assertEquals(1, h.backupStore.written.size)
    }

    @Test
    fun `a backup that cannot be written keeps the old layout and is retried`() = runTest {
        val h = aged(marker = 1, backupStore = RecordingBackupStore(failure = true))

        val migrated = h.newRepository()
        migrated.load(installed)

        assertEquals(old, migrated.layout.value)
        assertEquals(1, h.generationStore.marker)
        assertEquals(1, h.backupStore.attempts)
        assertTrue(h.warnings.any { it.contains("backup failed") })

        h.newRepository().load(installed)

        assertEquals(2, h.backupStore.attempts)
        assertEquals(old, h.repository.layout.value)
    }

    @Test
    fun `a marker that cannot be read leaves the stored layout alone`() = runTest {
        val h = aged(marker = 2)
        h.generationStore.failReads = true

        h.newRepository().load(installed)

        assertEquals(old, h.repository.layout.value)
        assertTrue(h.backupStore.written.isEmpty())
        assertTrue(h.warnings.any { it.contains("generation read failed") })
    }

    @Test
    fun `the backup name is the replaced generation and the local date`() {
        val noon = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
        val late = Instant.parse("2026-10-16T23:30:00Z").toEpochMilli()
        val twoHours = 2 * 60 * 60 * 1000

        assertEquals("layout-v1-2026-10-16.json", layoutBackupFileName(1, noon, twoHours))
        // 23:30 UTC is already the 17th at +02:00, and still the 16th at +00:00.
        assertEquals("layout-v1-2026-10-17.json", layoutBackupFileName(1, late, twoHours))
        assertEquals("layout-v1-2026-10-16.json", layoutBackupFileName(1, late, 0))
        assertEquals("layout-v2-2026-10-17.json", layoutBackupFileName(2, late, twoHours))
    }

    @Test
    fun `a failed read of the layout table shows the seed but writes and stamps nothing`() = runTest {
        // OPUS6 (audit F3): seeding (and stamping) after a failed read replaced real layouts.
        var writes = 0
        val broken = object : LayoutStore {
            override suspend fun read(): LayoutRows = throw IllegalStateException("database unavailable")
            override suspend fun write(rows: LayoutRows) { writes++ }
        }
        val h = RepositoryHarness(seed = next, store = broken)

        h.repository.load(installed)

        assertEquals(next, h.repository.layout.value)
        assertEquals(0, writes)
        assertNull(h.generationStore.marker)
        assertTrue(h.backupStore.written.isEmpty())
        assertFalse(h.repository.loaded)
    }

    @Test
    fun `the generation store has no marker until one is written`() = runTest {
        val prefs = ParcPrefs(FakePreferencesStore())
        val store = PrefsLayoutGenerationStore(prefs)

        assertNull(store.applied())

        store.setApplied(2)

        assertNull(PrefsLayoutGenerationStore(ParcPrefs(FakePreferencesStore())).applied())
        assertEquals(2, PrefsLayoutGenerationStore(prefs).applied())
    }

    @Test
    fun `the file store writes the old document under the dated name and leaves nothing else`() = runTest {
        val directory = File(System.getProperty("java.io.tmpdir"), "parc-t34-${System.nanoTime()}")
        val store = FileLayoutBackupStore(
            directory = directory,
            clock = { Instant.parse("2026-10-16T12:00:00Z").toEpochMilli() },
            zoneOffsetMillis = { 0 },
        )

        try {
            val name = store.write(1, """{"version":1}""")

            assertEquals("layout-v1-2026-10-16.json", name)
            assertEquals("""{"version":1}""", File(directory, name).readText())
            assertEquals(listOf(name), directory.listFiles()!!.map { it.name })
        } finally {
            directory.deleteRecursively()
        }
    }
}
