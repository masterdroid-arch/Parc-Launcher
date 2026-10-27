package com.brightblu.parc.feature.settings.backup

import com.brightblu.parc.model.fakes.FakeLayoutRepository
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller contract (T17): the result of one action becomes the message line, the busy flag
 * clears, and a second request cannot start while one is running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupControllerTest {

    private val uri = "content://test/backup.json"
    private val fixedNow = 1_763_078_400_000L // 2025-11-14T00:00:00Z in UTC

    @Test
    fun `a fresh controller shows the suggested name and no message`() = runTest {
        val files = FakeBackupFileStore()
        val service = LayoutBackupService(
            FakeLayoutRepository(), files, clock = { fixedNow }, zone = ZoneId.of("UTC"),
        )
        val controller = BackupController(service, backgroundScope)

        assertEquals("parc-layout-2025-11-14.json", controller.state.value.suggestedFileName)
        assertFalse(controller.state.value.busy)
        assertNull(controller.state.value.message)
    }

    @Test
    fun `a successful export reports it and clears busy`() = runTest {
        val files = FakeBackupFileStore()
        val service = LayoutBackupService(FakeLayoutRepository(), files, clock = { fixedNow })
        val controller = BackupController(service, backgroundScope)

        controller.exportTo(uri)
        runCurrent()

        assertEquals("Backup saved", controller.state.value.message)
        assertFalse(controller.state.value.busy)
        assertTrue(files.documents.containsKey(uri))
    }

    @Test
    fun `a successful restore reports it and clears busy`() = runTest {
        val source = FakeLayoutRepository(sampleBackupLayout())
        val files = FakeBackupFileStore()
        LayoutBackupService(source, files, clock = { fixedNow }).exportTo(uri)
        val controller = BackupController(
            LayoutBackupService(FakeLayoutRepository(), files, clock = { fixedNow }),
            backgroundScope,
        )

        controller.restoreFrom(uri)
        runCurrent()

        // Nothing is replaced until the user confirms.
        assertNotNull(controller.state.value.pendingRestore)
        assertNull(controller.state.value.message)

        controller.confirmRestore()
        runCurrent()

        assertTrue(controller.state.value.message!!.startsWith("Restored "))
        assertNull(controller.state.value.pendingRestore)
        assertFalse(controller.state.value.busy)
    }

    @Test
    fun `cancelling the confirm step keeps the current layout`() = runTest {
        val source = FakeLayoutRepository(sampleBackupLayout())
        val files = FakeBackupFileStore()
        LayoutBackupService(source, files, clock = { fixedNow }).exportTo(uri)
        val target = FakeLayoutRepository()
        val controller = BackupController(LayoutBackupService(target, files, clock = { fixedNow }), backgroundScope)

        controller.restoreFrom(uri)
        runCurrent()
        controller.cancelRestore()
        controller.confirmRestore()
        runCurrent()

        assertNull(controller.state.value.pendingRestore)
        assertTrue(target.layout.value.folders.isEmpty())
    }

    @Test
    fun `an invalid document reports the plain reason`() = runTest {
        val files = FakeBackupFileStore().apply { documents[uri] = "nope" }
        val controller = BackupController(
            LayoutBackupService(FakeLayoutRepository(), files, clock = { fixedNow }),
            backgroundScope,
        )

        controller.restoreFrom(uri)
        runCurrent()

        assertEquals("That file is not a Parc backup", controller.state.value.message)
    }

    @Test
    fun `a second request is ignored while one is running`() = runTest {
        val files = FakeBackupFileStore()
        val gate = CompletableDeferred<Unit>()
        files.writeGate = gate
        val controller = BackupController(
            LayoutBackupService(FakeLayoutRepository(), files, clock = { fixedNow }),
            backgroundScope,
        )

        controller.exportTo(uri)
        runCurrent()
        assertTrue(controller.state.value.busy)

        controller.exportTo("content://test/second.json")
        runCurrent()
        assertEquals(0, files.writeCount)

        gate.complete(Unit)
        runCurrent()
        assertEquals(1, files.writeCount)
        assertFalse(controller.state.value.busy)
        assertEquals("Backup saved", controller.state.value.message)
    }
}
