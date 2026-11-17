package com.brightblu.parc

import com.brightblu.parc.feature.settings.backup.BackupFileName
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T266: the weekly backup's dated names, four-file retention, plain status text and failure cases. */
class T266Test {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-17T15:00:00Z").toEpochMilli()

    private fun file(name: String) = WeeklyBackupFile(name, "content://folder/$name")

    @Test
    fun onlyTheNewestFourParcBackupsAreKept() {
        val files = listOf(
            file("parc-weekly-2026-10-01.json"),
            file("parc-weekly-2026-10-02.json"),
            file("parc-weekly-2026-10-03.json"),
            file("parc-weekly-2026-10-04.json"),
            file("parc-weekly-2026-10-05.json"),
            file("parc-weekly-2026-10-06.json"),
            file("notes.txt"),
            file("parc-layout-2026-09-01.json"),
            file("parc-weekly-not-a-date.json"),
            file("parc-weekly-2026-13-01.json"),
        )
        assertEquals(
            listOf("parc-weekly-2026-10-01.json", "parc-weekly-2026-10-02.json"),
            WeeklyBackupFiles.toDelete(files).map { it.name },
        )
        assertTrue(WeeklyBackupFiles.isBackup("parc-weekly-2026-10-06.json"))
        assertFalse(WeeklyBackupFiles.isBackup("notes.txt"))
        assertFalse(WeeklyBackupFiles.isBackup("parc-weekly-2026-13-01.json"))
    }

    @Test
    fun theBackupReadsTheStoredLayoutThroughTheFourHomeArcs() {
        assertEquals(listOf("A1", "A2", "A3", "A4"), weeklyBackupArcSpecs(384f, 853f).map { it.id })
        assertTrue(weeklyBackupArcSpecs(384f, 853f).all { it.slotCapacity > 0 })
    }

    @Test
    fun theWeeklyNameHasItsOwnPrefixAndTheManualExportsLocalDate() {
        assertEquals("parc-weekly-2026-11-17.json", WeeklyBackupFiles.nameFor(now, utc))
        assertEquals("parc-weekly-2026-11-17.json.tmp", WeeklyBackupFiles.tempNameFor(WeeklyBackupFiles.nameFor(now, utc)))
        assertFalse(WeeklyBackupFiles.isBackup("parc-weekly-2026-11-17.json.tmp"))
        assertFalse(WeeklyBackupFiles.isBackup(BackupFileName.of(now, utc)))
    }

    @Test
    fun theLastBackupLineUsesThePhonesOwnDayAndClock() {
        assertEquals("Never", WeeklyBackupText.lastBackup(null, now, utc))
        assertEquals("Today 09:05", WeeklyBackupText.lastBackup(
            Instant.parse("2026-11-17T09:05:00Z").toEpochMilli(), now, utc))
        assertEquals("Yesterday 18:40", WeeklyBackupText.lastBackup(
            Instant.parse("2026-11-16T18:40:00Z").toEpochMilli(), now, utc))
        assertEquals("3 Nov 2026 07:00", WeeklyBackupText.lastBackup(
            Instant.parse("2026-11-03T07:00:00Z").toEpochMilli(), now, utc))
    }

    @Test
    fun aFailedRunSaysWhatWentWrongInPlainWords() {
        assertTrue(WeeklyBackupText.failure(WeeklyBackupFailure.FolderGone).contains("folder is gone"))
        assertTrue(WeeklyBackupText.failure(WeeklyBackupFailure.NoSpace).contains("not enough space"))
        assertTrue(WeeklyBackupText.failure(WeeklyBackupFailure.Unreadable).contains("could not be read"))
        assertTrue(WeeklyBackupText.failure(WeeklyBackupFailure.WriteFailed).contains("could not be written"))
        assertTrue(WeeklyBackupText.failure(WeeklyBackupFailure.GrantFailed).contains("keep access"))
    }

    @Test
    fun folderAndSpaceFailuresAreClassifiedForTheScreen() {
        assertEquals(WeeklyBackupFailure.FolderGone, WeeklyBackupFailures.classify(WeeklyBackupFolderGone()))
        assertEquals(WeeklyBackupFailure.FolderGone, WeeklyBackupFailures.classify(SecurityException("revoked")))
        assertEquals(WeeklyBackupFailure.NoSpace, WeeklyBackupFailures.classify(IOException("ENOSPC (No space left on device)")))
        assertEquals(WeeklyBackupFailure.WriteFailed, WeeklyBackupFailures.classify(IOException("provider error")))
    }

    @Test
    fun aStoredFailureIdReadsBackAndAnUnknownOneIsRememberedAsNoFailure() {
        assertEquals(WeeklyBackupFailure.NoSpace, WeeklyBackupFailure.fromId("no_space"))
        assertEquals(WeeklyBackupFailure.FolderGone, WeeklyBackupFailure.fromId("folder_gone"))
        assertNull(WeeklyBackupFailure.fromId("from a later version"))
        assertNull(WeeklyBackupFailure.fromId(null))
    }
}
