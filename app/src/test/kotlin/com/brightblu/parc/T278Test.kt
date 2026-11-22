package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupEncryption
import org.junit.Assert.*
import org.junit.Test

class T278Test {
    @Test fun `missing weekly passphrase prevents file write`() {
        var writes = 0
        protectWeeklyBackup("private", true) { null }?.let { writes++ }
        assertEquals(0, writes)
        assertEquals("Enter your passphrase again", WeeklyBackupText.failure(WeeklyBackupFailure.PassphraseMissing))
    }
    @Test fun `weekly encryption uses same format and clears secret buffer`() {
        val secret = "test passphrase".toCharArray()
        val text = protectWeeklyBackup("private", true) { secret }!!
        assertTrue(secret.all { it == '\u0000' })
        assertEquals("private", BackupEncryption.decrypt(text, "test passphrase".toCharArray()))
        assertEquals("private", protectWeeklyBackup("private", false) { error("Must not read a secret") })
    }
}
