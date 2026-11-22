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
