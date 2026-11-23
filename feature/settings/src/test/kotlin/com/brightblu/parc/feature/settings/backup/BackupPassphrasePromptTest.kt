package com.brightblu.parc.feature.settings.backup

import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BackupPassphrasePromptTest {
    private class Protection : BackupProtection {
        override var enabled = false
        override fun enable(passphrase: CharArray) { enabled = true }
        override fun disable() { enabled = false }
    }
    @Test fun `encryption starts off and only enables after matching eight character input`() = runTest {
        val protection = Protection()
        val controller = BackupController(LayoutBackupService(FakeLayoutRepository(), FakeBackupFileStore()), this, protection)
        assertFalse(controller.state.value.encrypted)
        controller.toggleEncryption()
        assertEquals(PassphrasePurpose.Enable, controller.state.value.passphrasePurpose)
        controller.submitPassphrase("short", "short")
        assertEquals("Use at least 8 characters", controller.state.value.message)
        assertFalse(protection.enabled)
        controller.submitPassphrase("long enough", "different")
        assertEquals("The passphrases do not match", controller.state.value.message)
        assertFalse(protection.enabled)
        controller.submitPassphrase("long enough", "long enough")
        controller.state.first { !it.busy }
        assertTrue(protection.enabled)
        assertTrue(controller.state.value.encrypted)
        assertNull(controller.state.value.passphrasePurpose)
    }
    @Test fun `encrypted manual backup asks each time and cancel never writes`() = runTest {
        val protection = Protection().apply { enabled = true }
        val files = FakeBackupFileStore()
        val controller = BackupController(LayoutBackupService(FakeLayoutRepository(), files), this, protection)
        controller.exportTo("first")
        assertEquals(PassphrasePurpose.Export, controller.state.value.passphrasePurpose)
        controller.cancelPassphrase()
        assertEquals(0, files.writeCount)
        controller.exportTo("second")
        controller.submitPassphrase("long enough", "")
        controller.state.first { !it.busy }
        assertEquals(1, files.writeCount)
        assertTrue(files.documents.getValue("second").startsWith("PARC-ENCRYPTED"))
        controller.exportTo("third")
        assertEquals(PassphrasePurpose.Export, controller.state.value.passphrasePurpose)
    }
}

/** T402: the weekly switch needs a passphrase first; cancelling leaves it off. */
class WeeklyPassphraseRequiredTest {
    private class Protection : BackupProtection {
        override var enabled = false
        override fun enable(passphrase: CharArray) { enabled = true }
        override fun disable() { enabled = false }
    }

    @Test fun `without a passphrase the action waits for the set-up and cancel drops it`() = runTest {
        val protection = Protection()
        val controller = BackupController(LayoutBackupService(FakeLayoutRepository(), FakeBackupFileStore()), this, protection)
        var turnedOn = 0
        controller.requirePassphrase { turnedOn++ }
        assertEquals(PassphrasePurpose.Enable, controller.state.value.passphrasePurpose)
        assertEquals(0, turnedOn)
        controller.cancelPassphrase()
        assertEquals(0, turnedOn)
        assertFalse(protection.enabled)
        // A later successful set-up for another reason must not run the dropped action.
        controller.toggleEncryption()
        controller.submitPassphrase("long enough", "long enough")
        controller.state.first { !it.busy }
        assertTrue(protection.enabled)
        assertEquals(0, turnedOn)
    }

    @Test fun `the action runs once the passphrase is saved`() = runTest {
        val protection = Protection()
        val controller = BackupController(LayoutBackupService(FakeLayoutRepository(), FakeBackupFileStore()), this, protection)
        var turnedOn = 0
        controller.requirePassphrase { turnedOn++ }
        controller.submitPassphrase("short", "short")
        assertEquals(0, turnedOn)
        controller.submitPassphrase("long enough", "long enough")
        controller.state.first { !it.busy }
        assertEquals(1, turnedOn)
        assertTrue(controller.state.value.encrypted)
    }

    @Test fun `with a passphrase already set the action runs at once`() = runTest {
        val protection = Protection().apply { enabled = true }
        val controller = BackupController(LayoutBackupService(FakeLayoutRepository(), FakeBackupFileStore()), this, protection)
        var turnedOn = 0
        controller.requirePassphrase { turnedOn++ }
        assertEquals(1, turnedOn)
        assertNull(controller.state.value.passphrasePurpose)
    }

    @Test fun `the paused line shows when weekly is on without a passphrase`() {
        val on = WeeklyBackupUiState(enabled = true, failureLabel = null)
        assertEquals("Weekly backup paused: add a passphrase", WeeklyBackupUiState.statusLine(on, passphraseOn = false))
        assertNull(WeeklyBackupUiState.statusLine(on, passphraseOn = true))
        assertNull(WeeklyBackupUiState.statusLine(WeeklyBackupUiState(enabled = false), passphraseOn = false))
        assertEquals("Last backup failed", WeeklyBackupUiState.statusLine(
            WeeklyBackupUiState(enabled = true, failureLabel = "Last backup failed"), passphraseOn = true))
    }

    @Test fun `the export screen says encrypted or not and names a left out key`() {
        assertEquals(listOf("Encrypted"), BackupExportCopy.lines(encrypted = true, hasKey = true))
        assertEquals(listOf("Not encrypted"), BackupExportCopy.lines(encrypted = false, hasKey = false))
        val plainWithKey = BackupExportCopy.lines(encrypted = false, hasKey = true)
        assertEquals("Not encrypted", plainWithKey.first())
        assertTrue(plainWithKey.last().contains("left out"))
    }
}
