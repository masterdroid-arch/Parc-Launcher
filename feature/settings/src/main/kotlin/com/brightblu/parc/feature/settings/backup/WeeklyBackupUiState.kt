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
) {
    companion object {
        /** T402: shown while the weekly backup cannot run because no passphrase is set. */
        const val PausedLine = "Weekly backup paused: add a passphrase"

        /**
         * T402: the one line under the weekly rows. A weekly backup that is on while backups have no
         * passphrase is paused whether or not a run has noticed yet, so the line never depends on
         * timing; otherwise it is the last run's own result.
         */
        fun statusLine(state: WeeklyBackupUiState, passphraseOn: Boolean): String? =
            if (state.enabled && !passphraseOn) PausedLine else state.failureLabel
    }
}
