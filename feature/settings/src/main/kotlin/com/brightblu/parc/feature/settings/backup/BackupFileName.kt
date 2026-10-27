package com.brightblu.parc.feature.settings.backup

import java.time.Instant
import java.time.ZoneId

/**
 * Pure naming for the backup document (T17, F-15). Immutable and thread-safe; no Android types, so
 * the rule is unit-tested with a fixed clock and zone.
 */
object BackupFileName {
    /** MIME type the export picker offers; the file is plain versioned JSON. */
    const val MimeType = "application/json"

    /** MIME types the import picker accepts. Some providers report octet-stream for JSON. */
    val ImportMimeTypes = arrayOf(MimeType, "text/plain", "application/octet-stream")

    private const val Prefix = "parc-layout-"
    private const val Suffix = ".json"

    /**
     * `parc-layout-YYYY-MM-DD.json` for [epochMillis] read in [zone]. The date is the user's local
     * day, not UTC, so two backups taken on the device's own dates never collide across midnight.
     *
     * `Instant.atZone().toLocalDate()` rather than `LocalDate.ofInstant` (T66): the latter is API 34
     * and there is no core-library desugaring in this build, while the former is available on 26+.
     */
    fun of(epochMillis: Long, zone: ZoneId): String =
        Prefix + Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate() + Suffix
}
