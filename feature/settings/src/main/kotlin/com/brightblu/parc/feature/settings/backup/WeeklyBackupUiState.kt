package com.brightblu.parc.feature.settings.backup

/**
 * T266: what the Backup screen shows about the weekly automatic backup. Pure display state; the
 * app owns the stored folder and the WorkManager job. [lastBackupLabel] is already formatted for
 * the device's own day and clock, and [failureLabel] is a plain sentence or null when the last
 * run succeeded (or none failed).
 */
data class WeeklyBackupUiState(
    val enabled: Boolean = false,
    val folderName: String? = null,
    val lastBackupLabel: String = "Never",
    val failureLabel: String? = null,
)
