package com.brightblu.parc.feature.settings.backup

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives [LayoutBackupService] from the UI (T17).
 *
 * Threading: [exportTo] and [restoreFrom] are safe to call from the main thread; the work is launched
 * into [scope] and never blocks the frame. [state] is a [StateFlow]. A second request is ignored while
 * one is running, so two picker results cannot interleave.
 */
class BackupController(
    private val service: LayoutBackupService,
    private val scope: CoroutineScope,
) {
    private val stateFlow = MutableStateFlow(BackupUiState(service.suggestedFileName()))
    val state: StateFlow<BackupUiState> = stateFlow.asStateFlow()

    /** Writes the current layout to the document the user chose. No-op while a request is running. */
    fun exportTo(uri: String) = launch { service.exportTo(uri) }

    private var pending: BackupOutcome.Ready? = null

    /**
     * Reads and checks the document the user chose, then waits for [confirmRestore] (T87). Nothing is
     * changed yet; [BackupUiState.pendingRestore] carries the summary for the confirm step.
     */
    fun restoreFrom(uri: String) {
        if (stateFlow.value.busy) return
        stateFlow.value = stateFlow.value.copy(busy = true, message = null, pendingRestore = null)
        scope.launch {
            when (val outcome = service.prepareRestore(uri)) {
                is BackupOutcome.Ready -> {
                    pending = outcome
                    stateFlow.value = stateFlow.value.copy(busy = false, pendingRestore = outcome.summary)
                }
                else -> {
                    pending = null
                    stateFlow.value = stateFlow.value.copy(busy = false, message = backupMessage(outcome))
                }
            }
        }
    }

    /** Applies the checked document after the user agreed to replace the current setup. */
    fun confirmRestore() {
        val ready = pending ?: return
        if (stateFlow.value.busy) return
        pending = null
        stateFlow.value = stateFlow.value.copy(busy = true, message = null, pendingRestore = null)
        scope.launch {
            val outcome = service.confirm(ready)
            stateFlow.value = stateFlow.value.copy(busy = false, message = backupMessage(outcome))
        }
    }

    /** Drops the checked document; nothing was changed. */
    fun cancelRestore() {
        pending = null
        stateFlow.value = stateFlow.value.copy(pendingRestore = null, message = "Restore cancelled")
    }

    private fun launch(action: suspend () -> BackupOutcome) {
        if (stateFlow.value.busy) return
        stateFlow.value = stateFlow.value.copy(busy = true, message = null)
        scope.launch {
            val outcome = action()
            stateFlow.value = stateFlow.value.copy(busy = false, message = backupMessage(outcome))
        }
    }
}

/** Plain-language result line for the screen. No em dash, per the workspace writing rules. */
fun backupMessage(outcome: BackupOutcome): String = when (outcome) {
    is BackupOutcome.Exported -> "Backup saved"
    is BackupOutcome.Restored -> "Restored " + outcome.summary.describe()
    is BackupOutcome.Ready -> "Ready to restore"
    is BackupOutcome.Failed -> when (outcome.failure) {
        BackupFailure.ReadFailed -> "Could not read that file"
        BackupFailure.WriteFailed -> "Could not save the backup"
        BackupFailure.InvalidDocument -> "That file is not a Parc backup"
    }
}
