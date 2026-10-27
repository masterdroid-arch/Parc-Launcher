package com.brightblu.parc.feature.settings.backup

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backup file name rule: prefix, local day, JSON suffix, and the picker MIME types (T17). */
class BackupFileNameTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `the name is the prefix, the local day and the json suffix`() {
        val noon = Instant.parse("2026-10-15T12:00:00Z").toEpochMilli()
        assertEquals("parc-layout-2026-10-15.json", BackupFileName.of(noon, utc))
    }

    @Test
    fun `the day is the device's local day, not UTC`() {
        // 23:30 UTC is already the next day in a +02:00 zone.
        val late = Instant.parse("2026-10-15T23:30:00Z").toEpochMilli()
        assertEquals("parc-layout-2026-10-16.json", BackupFileName.of(late, ZoneId.of("+02:00")))
        assertEquals("parc-layout-2026-10-15.json", BackupFileName.of(late, utc))
    }

    @Test
    fun `the export picker offers json and the import picker also accepts plain text`() {
        assertEquals("application/json", BackupFileName.MimeType)
        assertTrue(BackupFileName.MimeType in BackupFileName.ImportMimeTypes)
        assertTrue("text/plain" in BackupFileName.ImportMimeTypes)
    }
}
