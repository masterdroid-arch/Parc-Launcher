package com.brightblu.parc.feature.settings.backup

import com.brightblu.parc.core.data.backup.RestoreSummary

/**
 * Immutable backup screen state (T17). Non-null; [suggestedFileName] is the name the export picker
 * offers, [message] the last plain-language result, and [busy] true while a document is being written
 * or read so the buttons can be disabled.
 */
data class BackupUiState(
    val suggestedFileName: String,
    val busy: Boolean = false,
    val message: String? = null,
    /** Set after a file was read and checked; the screen asks before replacing anything (T87). */
    val pendingRestore: RestoreSummary? = null,
)
