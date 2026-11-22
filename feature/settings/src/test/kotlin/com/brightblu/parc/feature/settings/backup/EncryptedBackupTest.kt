package com.brightblu.parc.feature.settings.backup

import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class EncryptedBackupTest {
    @Test fun `encrypted restore authenticates before preview and changes nothing until confirm`() = runTest {
        val files = FakeBackupFileStore()
        val source = FakeLayoutRepository(sampleBackupLayout())
        val passphrase = "test passphrase".toCharArray()
        LayoutBackupService(source, files).exportTo("file", passphrase)
        val target = FakeLayoutRepository()
        val before = target.layout.value
        val service = LayoutBackupService(target, files)
        assertEquals(BackupOutcome.NeedsPassphrase, service.prepareRestore("file"))
        assertEquals(BackupOutcome.Failed(BackupFailure.UnlockFailed), service.prepareRestore("file", "wrong".toCharArray()))
        assertEquals(before, target.layout.value)
        val encrypted = files.documents.getValue("file")
        LayoutBackupService(source, files).exportTo("file")
        assertEquals(BackupOutcome.Failed(BackupFailure.UnlockFailed), service.prepareRestore("file", passphrase))
        assertEquals(before, target.layout.value)
        files.documents["file"] = encrypted
        val ready = service.prepareRestore("file", passphrase) as BackupOutcome.Ready
        assertEquals(before, target.layout.value)
        assertTrue(service.confirm(ready) is BackupOutcome.Restored)
        assertEquals(source.layout.value, target.layout.value)
    }
    @Test fun `damaged or truncated encrypted file cannot change current data`() = runTest {
        val files = FakeBackupFileStore()
        val target = FakeLayoutRepository(sampleBackupLayout())
        val before = target.layout.value
        val service = LayoutBackupService(target, files)
        service.exportTo("file", "test passphrase".toCharArray())
        val original = files.documents.getValue("file")
        for (damaged in listOf(original.dropLast(8), original.dropLast(4) + "AAAA")) {
            files.documents["file"] = damaged
            assertEquals(BackupOutcome.Failed(BackupFailure.UnlockFailed), service.prepareRestore("file", "test passphrase".toCharArray()))
            assertEquals(before, target.layout.value)
        }
    }
    @Test fun `old plain backup still restores without any passphrase`() = runTest {
        val source = FakeLayoutRepository(sampleBackupLayout())
        val files = FakeBackupFileStore()
        LayoutBackupService(source, files).exportTo("file")
        val target = FakeLayoutRepository()
        assertTrue(LayoutBackupService(target, files).restoreFrom("file") is BackupOutcome.Restored)
        assertEquals(source.layout.value, target.layout.value)
    }
}
