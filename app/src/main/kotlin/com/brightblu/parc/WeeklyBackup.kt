package com.brightblu.parc

import android.content.Context
import android.content.SharedPreferences
import com.brightblu.parc.feature.settings.backup.BackupFileName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager

/** Why the weekly backup could not write. Stored by id so an old value still reads after a rename. */
internal enum class WeeklyBackupFailure(val id: String) {
    /** The chosen folder or its Android grant is gone. */
    FolderGone("folder_gone"),
    /** The folder has no room for the backup. */
    NoSpace("no_space"),
    /** The layout or a settings store could not be read. */
    Unreadable("unreadable"),
    /** The folder exists but the file could not be written or an old one removed. */
    WriteFailed("write_failed"),
    /** Android would not keep access to the picked folder, so it was not saved. */
    GrantFailed("grant_failed"),
    ;

    companion object {
        fun fromId(id: String?): WeeklyBackupFailure? = entries.firstOrNull { it.id == id }
    }
}

/** One stored weekly-backup configuration. The folder and its grant are local to this phone. */
internal data class WeeklyBackupSettings(
    val enabled: Boolean = false,
    val folderUri: String? = null,
    val folderName: String? = null,
    val lastSuccessMillis: Long? = null,
    val lastFailure: WeeklyBackupFailure? = null,
    val lastFailureMillis: Long? = null,
)

/**
 * T266: the weekly backup's small app-private store. It lives beside Parc's other phone-only UI
 * preferences, not in a Parc backup: a folder grant belongs to one phone and must not be restored
 * onto another. A listener state flow lets the Backup screen show a run that finished while open.
 */
internal class WeeklyBackupStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FileName, Context.MODE_PRIVATE)

    fun read(): WeeklyBackupSettings = WeeklyBackupSettings(
        enabled = prefs.getBoolean(KeyEnabled, false),
        folderUri = prefs.getString(KeyFolderUri, null)?.takeIf { it.isNotBlank() },
        folderName = prefs.getString(KeyFolderName, null)?.takeIf { it.isNotBlank() },
        lastSuccessMillis = prefs.getLong(KeyLastSuccess, 0L).takeIf { it > 0L },
        lastFailure = WeeklyBackupFailure.fromId(prefs.getString(KeyLastFailure, null)),
        lastFailureMillis = prefs.getLong(KeyLastFailureAt, 0L).takeIf { it > 0L },
    )

    val state: Flow<WeeklyBackupSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** Turning the switch off leaves the folder choice in place for the next time. */
    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KeyEnabled, enabled).commit()
    }

    fun setFolder(uri: String, name: String) {
        prefs.edit().putString(KeyFolderUri, uri).putString(KeyFolderName, name).commit()
    }

    fun recordSuccess(now: Long) {
        prefs.edit().putLong(KeyLastSuccess, now).remove(KeyLastFailure).remove(KeyLastFailureAt).commit()
    }

    fun recordFailure(failure: WeeklyBackupFailure, now: Long) {
        prefs.edit().putString(KeyLastFailure, failure.id).putLong(KeyLastFailureAt, now).commit()
    }

    private companion object {
        const val FileName = "weekly_backup"
        const val KeyEnabled = "enabled"
        const val KeyFolderUri = "folder_uri"
        const val KeyFolderName = "folder_name"
        const val KeyLastSuccess = "last_success"
        const val KeyLastFailure = "last_failure"
        const val KeyLastFailureAt = "last_failure_at"
    }
}

/** T266: the Backup screen's plain words. Pure; tested with a fixed clock and zone. */
internal object WeeklyBackupText {
    /** `Never`, `Today 14:32`, `Yesterday 09:05`, or `3 Nov 2026 07:00`. */
    fun lastBackup(lastSuccessMillis: Long?, nowMillis: Long, zone: ZoneId): String {
        if (lastSuccessMillis == null || lastSuccessMillis <= 0L) return "Never"
        val moment = Instant.ofEpochMilli(lastSuccessMillis).atZone(zone)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val day = when (moment.toLocalDate()) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> moment.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
        }
        return day + " " + moment.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
    }

    /** One clear line; the screen shows it under the weekly rows. */
    fun failure(failure: WeeklyBackupFailure): String = when (failure) {
        WeeklyBackupFailure.FolderGone -> "Last backup failed: the folder is gone. Choose it again."
        WeeklyBackupFailure.NoSpace -> "Last backup failed: not enough space in the folder."
        WeeklyBackupFailure.Unreadable -> "Last backup failed: your setup could not be read."
        WeeklyBackupFailure.WriteFailed -> "Last backup failed: the folder could not be written."
        WeeklyBackupFailure.GrantFailed -> "Android would not keep access to that folder. Choose another folder."
    }
}

/** One document in the chosen folder. [uri] is the provider's own child document URI. */
internal data class WeeklyBackupFile(val name: String, val uri: String)

/**
 * T266/T270: which weekly backup files to remove. Weekly files have their own `parc-weekly-` prefix,
 * so a manual `parc-layout-` export in the same folder is never counted or deleted. Newest four by
 * the local date in the name stay; the rest are returned for deletion.
 */
internal object WeeklyBackupFiles {
    const val KeepCount = 4
    private val NamePattern = Regex("""^parc-weekly-(\d{4}-\d{2}-\d{2})\.json$""")
    private const val TempSuffix = ".tmp"

    /** A half-written document: the name is written first as `<name>.tmp`, then renamed. */
    fun tempNameFor(name: String): String = name + TempSuffix

    fun isBackup(name: String): Boolean {
        val date = NamePattern.matchEntire(name)?.groupValues?.getOrNull(1) ?: return false
        return runCatching { LocalDate.parse(date) }.isSuccess
    }

    fun toDelete(files: List<WeeklyBackupFile>): List<WeeklyBackupFile> {
        val backups = files.filter { isBackup(it.name) }.sortedBy { it.name }
        return backups.take((backups.size - KeepCount).coerceAtLeast(0))
    }

    /** `parc-weekly-YYYY-MM-DD.json`, the manual export's local date with its own prefix. */
    fun nameFor(nowMillis: Long, zone: ZoneId): String =
        "parc-weekly-" + BackupFileName.of(nowMillis, zone).removePrefix("parc-layout-")
}

/**
 * T266: the one WorkManager job. Charging and device-idle constraints are the card's "charging and
 * idle"; the period is one week. The job is unique so turning the switch on again never doubles it.
 */
internal object WeeklyBackupScheduler {
    const val WorkName = "weekly-backup"

    fun sync(context: Context) {
        val settings = WeeklyBackupStore(context).read()
        if (settings.enabled && !settings.folderUri.isNullOrBlank()) schedule(context, replace = false)
        else cancel(context)
    }

    /** A user change (switch or folder) replaces any pending job, so an old cancellation cannot win. */
    fun restart(context: Context) = schedule(context, replace = true)

    private fun schedule(context: Context, replace: Boolean) {
        val request = PeriodicWorkRequestBuilder<WeeklyBackupWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresDeviceIdle(true)
                    .build(),
            )
            .addTag(WorkName)
            .build()
        val policy = if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WorkName, policy, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WorkName)
    }
}
