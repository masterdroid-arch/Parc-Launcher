package com.brightblu.parc.feature.settings.backup

import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.ArcAssignment
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.LayoutJson
import com.brightblu.parc.model.LayoutRepository
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup service contract (T17): an export writes exactly what the repository exports, a restore
 * replaces a fresh layout with the saved one, and every failure leaves the layout untouched.
 */
class LayoutBackupServiceTest {

    private val uri = "content://test/backup.json"
    private val fixedNow = 1_763_078_400_000L // 2025-11-14T00:00:00Z in UTC
    private val utc = ZoneId.of("UTC")

    private fun service(repo: LayoutRepository, files: FakeBackupFileStore) =
        LayoutBackupService(repo, files, clock = { fixedNow }, zone = utc)

    @Test
    fun `export writes the repository json through the transport and reports its size`() = runTest {
        val repo = FakeLayoutRepository()
        val files = FakeBackupFileStore()

        val outcome = service(repo, files).exportTo(uri)

        val written = files.documents.getValue(uri)
        assertEquals(BackupOutcome.Exported(written.toByteArray(Charsets.UTF_8).size), outcome)
        assertTrue(written.contains("\"backupVersion\":2"))
        val fresh = FakeLayoutRepository()
        service(fresh, files).restoreFrom(uri)
        assertEquals(repo.layout.value, fresh.layout.value)
        assertEquals(uri, files.lastWrittenUri)
    }

    @Test
    fun `a write failure is reported and nothing is stored`() = runTest {
        val files = FakeBackupFileStore().apply { writeFailure = java.io.IOException("disk") }

        val outcome = service(FakeLayoutRepository(), files).exportTo(uri)

        assertEquals(BackupOutcome.Failed(BackupFailure.WriteFailed), outcome)
        assertNull(files.lastWrittenUri)
    }

    @Test
    fun `a fresh install restores the exported layout exactly`() = runTest {
        val source = FakeLayoutRepository(sampleBackupLayout())
        val files = FakeBackupFileStore()
        val backup = service(source, files)
        backup.exportTo(uri)

        // A fresh install: the layout has never been written, only the built-in default snapshot.
        val fresh = FakeLayoutRepository()
        assertTrue(fresh.layout.value.folders.isEmpty())
        val outcome = service(fresh, files).restoreFrom(uri)

        outcome as BackupOutcome.Restored
        assertEquals(source.layout.value.folders.size, outcome.folders)
        assertEquals(source.layout.value.pins.size, outcome.pinned)
        assertEquals(source.layout.value.hidden.size, outcome.hidden)
        assertEquals(source.layout.value.folders.size, outcome.summary.folders)
        assertEquals(source.layout.value, fresh.layout.value)
    }

    @Test
    fun `a restore replaces the seeded first-run layout with the backup`() = runTest {
        val source = FakeLayoutRepository(sampleBackupLayout())
        val files = FakeBackupFileStore()
        service(source, files).exportTo(uri)

        // A fresh install has already seeded a layout; the restore must overwrite it wholesale.
        val seeded = FakeLayoutRepository(
            ParcLayout(
                version = 1,
                arcs = listOf(
                    ArcAssignment(
                        ArcSpec("A1", 0, 120f, 180f, 270f, 4, Layer.Home),
                        mapOf(1 to Slot.App(backupId("other"))),
                    ),
                ),
                folders = listOf(Folder(FolderId("new"), "New")),
            ),
        )

        val outcome = service(seeded, files).restoreFrom(uri)

        assertTrue(outcome is BackupOutcome.Restored)
        assertEquals(source.layout.value, seeded.layout.value)
    }

    @Test
    fun `a malformed document fails clean and leaves the layout alone`() = runTest {
        val repo = FakeLayoutRepository(sampleBackupLayout())
        val before = repo.layout.value
        val files = FakeBackupFileStore().apply { documents[uri] = "not json at all" }

        val outcome = service(repo, files).restoreFrom(uri)

        assertEquals(BackupOutcome.Failed(BackupFailure.InvalidDocument), outcome)
        assertEquals(before, repo.layout.value)
    }

    @Test
    fun `an old bare layout document with an unknown key still restores, the key is ignored`() = runTest {
        val repo = FakeLayoutRepository(sampleBackupLayout())
        val files = FakeBackupFileStore().apply {
            documents[uri] = """{"version":1,"arcs":[],"folders":[],"pins":[],"hidden":[],"extra":true}"""
        }

        val outcome = service(repo, files).restoreFrom(uri)

        assertTrue(outcome is BackupOutcome.Restored)
        assertTrue(repo.layout.value.folders.isEmpty())
    }

    @Test
    fun `prepare changes nothing until confirm`() = runTest {
        val source = FakeLayoutRepository(sampleBackupLayout())
        val files = FakeBackupFileStore()
        service(source, files).exportTo(uri)
        val fresh = FakeLayoutRepository()

        val prepared = service(fresh, files).prepareRestore(uri)

        prepared as BackupOutcome.Ready
        assertTrue(fresh.layout.value.folders.isEmpty())
        assertEquals(source.layout.value.folders.size, prepared.summary.folders)
        service(fresh, files).confirm(prepared)
        assertEquals(source.layout.value, fresh.layout.value)
    }

    @Test
    fun `a repository rejection (foreign arcs) fails clean`() = runTest {
        val rejecting = RejectingImportLayout(FakeLayoutRepository(sampleBackupLayout()))
        val before = rejecting.layout.value
        val files = FakeBackupFileStore().apply { documents[uri] = LayoutJson.encode(before) }

        val outcome = service(rejecting, files).restoreFrom(uri)

        assertEquals(BackupOutcome.Failed(BackupFailure.InvalidDocument), outcome)
        assertEquals(before, rejecting.layout.value)
    }

    @Test
    fun `an unreadable document is a read failure`() = runTest {
        val files = FakeBackupFileStore()

        val outcome = service(FakeLayoutRepository(), files).restoreFrom(uri)

        assertEquals(BackupOutcome.Failed(BackupFailure.ReadFailed), outcome)
    }

    @Test
    fun `the suggested name comes from the injected clock and zone`() {
        val name = service(FakeLayoutRepository(), FakeBackupFileStore()).suggestedFileName()
        // fixedNow is 2025-11-14T00:00:00Z.
        assertEquals("parc-layout-2025-11-14.json", name)
    }

    /** Stands in for the production repository refusing arcs this build does not have. */
    private class RejectingImportLayout(delegate: LayoutRepository) : LayoutRepository by delegate {
        override suspend fun importLayout(source: String) {
            throw IllegalArgumentException("Imported layout uses arcs this build does not have")
        }
    }
}
