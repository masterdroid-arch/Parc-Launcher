package com.brightblu.parc.core.data.backup

import java.nio.ByteBuffer
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class BackupEncryptionTest {
    private val passphrase = "a test passphrase 🔐".toCharArray()
    @Test fun `round trip with fresh salt and IV each time`() {
        val first = BackupEncryption.encrypt("private notes 日本語", passphrase)
        val second = BackupEncryption.encrypt("private notes 日本語", passphrase)
        assertNotEquals(first, second)
        assertFalse(first.contains("private notes"))
        assertEquals("private notes 日本語", BackupEncryption.decrypt(first, passphrase))
        assertEquals("private notes 日本語", BackupEncryption.decrypt(second, passphrase))
        assertEquals(600_000, ByteBuffer.wrap(payload(first)).getInt(4))
    }
    @Test fun `wrong passphrase tampering truncation and changed header fail closed`() {
        val file = BackupEncryption.encrypt("secret", passphrase)
        assertFails { BackupEncryption.decrypt(file, "wrong password".toCharArray()) }
        assertFails { BackupEncryption.decrypt(file.trimEnd('='), passphrase) }
        val bytes = payload(file)
        for (offset in listOf(0, 7, 10, 30, bytes.lastIndex)) {
            val altered = bytes.copyOf()
            altered[offset] = (altered[offset].toInt() xor 1).toByte()
            assertFails { BackupEncryption.decrypt(armour(altered), passphrase) }
        }
        for (length in listOf(0, 3, 20, bytes.size - 1)) {
            assertFails { BackupEncryption.decrypt(armour(bytes.copyOf(length)), passphrase) }
        }
    }
    private fun payload(text: String) = Base64.getDecoder().decode(text.removePrefix(BackupEncryption.Magic))
    private fun armour(bytes: ByteArray) = BackupEncryption.Magic + Base64.getEncoder().encodeToString(bytes)
    private fun assertFails(action: () -> Unit) {
        try { action(); fail("Must reject invalid encrypted file") } catch (_: Exception) { }
    }
}
