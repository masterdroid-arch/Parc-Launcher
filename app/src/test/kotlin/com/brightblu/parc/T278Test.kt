package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupEncryption
import org.junit.Assert.*
import org.junit.Test

class T278Test {
    @Test fun `missing weekly passphrase prevents file write`() {
        var writes = 0
        protectWeeklyBackup("private", true) { null }?.let { writes++ }
        assertEquals(0, writes)
        assertEquals("Weekly backup paused: add a passphrase", WeeklyBackupText.failure(WeeklyBackupFailure.PassphraseMissing))
    }
    @Test fun `weekly encryption uses same format and clears secret buffer`() {
        val secret = "test passphrase".toCharArray()
        val text = protectWeeklyBackup("private", true) { secret }!!
        assertTrue(secret.all { it == '\u0000' })
        assertEquals("private", BackupEncryption.decrypt(text, "test passphrase".toCharArray()))
    }

    /** T402: protection off means no weekly text at all, and the secret is never even asked for. */
    @Test fun `weekly backup with protection off writes nothing`() {
        var asked = false
        var writes = 0
        protectWeeklyBackup("private notes and a key", false) { asked = true; "x".toCharArray() }?.let { writes++ }
        assertEquals(0, writes)
        assertFalse(asked)
    }

    /** T402: an existing user with weekly on and no passphrase gets the paused line, and nothing is written. */
    @Test fun `existing weekly user without a passphrase is paused and nothing is written`() {
        val line = "Weekly backup paused: add a passphrase"
        val old = com.brightblu.parc.feature.settings.backup.WeeklyBackupUiState(enabled = true)
        assertEquals(line, com.brightblu.parc.feature.settings.backup.WeeklyBackupUiState.statusLine(old, passphraseOn = false))
        // After the next run recorded the failure, the stored line is the same one.
        val afterRun = old.copy(failureLabel = WeeklyBackupText.failure(WeeklyBackupFailure.PassphraseMissing))
        assertEquals(line, com.brightblu.parc.feature.settings.backup.WeeklyBackupUiState.statusLine(afterRun, passphraseOn = false))
        var writes = 0
        protectWeeklyBackup("older state", false) { null }?.let { writes++ }
        assertEquals(0, writes)
        // Older weekly files in the folder are only ever removed after a successful write, never on a pause.
        val files = listOf(
            WeeklyBackupFile("parc-weekly-2026-10-01.json", "u1"), WeeklyBackupFile("parc-weekly-2026-10-08.json", "u2"),
            WeeklyBackupFile("parc-weekly-2026-10-15.json", "u3"), WeeklyBackupFile("parc-weekly-2026-10-22.json", "u4"),
        )
        assertTrue(WeeklyBackupFiles.toDelete(files).isEmpty())
    }

}
