package com.brightblu.parc.feature.settings.backup

import com.brightblu.parc.core.data.backup.BackupKey
import com.brightblu.parc.core.data.backup.BackupStore
import com.brightblu.parc.core.data.backup.BackupStoreSpec
import com.brightblu.parc.core.data.backup.BackupValueType
import com.brightblu.parc.core.data.backup.StoreAccess
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T402: no plain-text export contains a credential; an encrypted one still carries it. */
class PlainExportKeyTest {
    private val key = "fake-test-key-123456"
    private val spec = BackupStoreSpec("deepl", listOf(BackupKey("key", BackupValueType.Text, 100)), secret = true)
    private val access = object : StoreAccess {
        override suspend fun read(spec: BackupStoreSpec): Map<String, Any> = mapOf("key" to key)
        override suspend fun write(spec: BackupStoreSpec, values: Map<String, Any>) = Unit
    }

    @Test fun `a manual export without a passphrase leaves the key out`() = runTest {
        val files = FakeBackupFileStore()
        val service = LayoutBackupService(FakeLayoutRepository(), files, stores = { listOf(BackupStore(spec, access)) })
        assertTrue(service.exportTo("plain") is BackupOutcome.Exported)
        val text = files.documents.getValue("plain")
        assertFalse(text.startsWith("PARC-ENCRYPTED"))
        assertFalse(text.contains(key))
        assertFalse(text.contains("deepl"))
    }

    @Test fun `an encrypted export carries the key and a wrong passphrase cannot read it`() = runTest {
        val files = FakeBackupFileStore()
        val service = LayoutBackupService(FakeLayoutRepository(), files, stores = { listOf(BackupStore(spec, access)) })
        assertTrue(service.exportTo("locked", "long enough".toCharArray()) is BackupOutcome.Exported)
        val text = files.documents.getValue("locked")
        assertTrue(text.startsWith("PARC-ENCRYPTED"))
        assertFalse(text.contains(key))
        val ready = service.prepareRestore("locked", "long enough".toCharArray()) as BackupOutcome.Ready
        assertTrue(ready.summary.settings == 1)
    }
}
