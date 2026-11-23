package com.brightblu.parc.core.data.backup

import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T402: a store marked secret reaches a backup only when the caller says the text will be encrypted. */
class SecretBackupTest {
    private val key = "fake-test-key-123456"
    private val secretSpec = BackupStoreSpec("deepl", listOf(BackupKey("key", BackupValueType.Text, 100)), secret = true)
    private val plainSpec = BackupStoreSpec("plain", listOf(BackupKey("note", BackupValueType.Text, 100)))

    private class Memory(private val values: Map<String, Any>) : StoreAccess {
        override suspend fun read(spec: BackupStoreSpec): Map<String, Any> = values
        override suspend fun write(spec: BackupStoreSpec, values: Map<String, Any>) = Unit
    }

    private fun backup() = FullBackup(FakeLayoutRepository(), listOf(
        BackupStore(secretSpec, Memory(mapOf("key" to key))),
        BackupStore(plainSpec, Memory(mapOf("note" to "hello"))),
    ))

    @Test fun `default export never contains a secret store`() = runTest {
        val text = backup().export()
        assertFalse(text.contains(key))
        assertFalse(text.contains("\"deepl\""))
        assertTrue(text.contains("hello"))
    }

    @Test fun `secrets are written only when the caller asks for them`() = runTest {
        assertTrue(backup().export(includeSecrets = true).contains(key))
        assertFalse(backup().export(includeSecrets = false).contains(key))
    }

    @Test fun `a plain export parses and restores everything except the secret`() = runTest {
        val parsed = backup().parse(backup().export())
        assertTrue(parsed.summary.settings == 1)
    }
}
