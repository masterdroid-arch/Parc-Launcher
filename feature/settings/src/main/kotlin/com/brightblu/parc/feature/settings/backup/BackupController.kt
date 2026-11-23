package com.brightblu.parc.feature.settings.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val protection: BackupProtection? = null,
) {
    private val stateFlow = MutableStateFlow(BackupUiState(service.suggestedFileName(), encrypted = protection?.enabled == true))
    val state: StateFlow<BackupUiState> = stateFlow.asStateFlow()

    private var pendingUri: String? = null
    private var afterEnable: (() -> Unit)? = null

    /** Writes the current layout to the document the user chose. */
    fun exportTo(uri: String) {
        if (stateFlow.value.busy || stateFlow.value.passphrasePurpose != null) return
        if (protection?.enabled == true) {
            pendingUri = uri
            stateFlow.value = stateFlow.value.copy(passphrasePurpose = PassphrasePurpose.Export, message = null)
        } else launch { service.exportTo(uri) }
    }

    fun toggleEncryption() {
        if (stateFlow.value.busy) return
        if (protection?.enabled == true) launch {
            try {
                withContext(Dispatchers.IO) { protection.disable() }
                stateFlow.value = stateFlow.value.copy(encrypted = false)
                BackupOutcome.ProtectionChanged
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                BackupOutcome.Failed(BackupFailure.WriteFailed)
            }
        } else stateFlow.value = stateFlow.value.copy(passphrasePurpose = PassphrasePurpose.Enable, message = null)
    }

    fun enterPassphraseAgain() {
        if (!stateFlow.value.busy) stateFlow.value = stateFlow.value.copy(passphrasePurpose = PassphrasePurpose.Enable, message = null)
    }

    /**
     * T402: runs [then] only once backups have a passphrase. With one already set it runs at once;
     * otherwise the passphrase set-up opens, and cancelling it drops [then], so whatever needed the
     * passphrase (the weekly switch) stays off.
     */
    fun requirePassphrase(then: () -> Unit) {
        if (stateFlow.value.busy || stateFlow.value.passphrasePurpose != null) return
        if (protection?.enabled == true) { then(); return }
        afterEnable = then
        stateFlow.value = stateFlow.value.copy(passphrasePurpose = PassphrasePurpose.Enable, message = null)
    }

    fun cancelPassphrase() {
        afterEnable = null
        pendingUri = null
        stateFlow.value = stateFlow.value.copy(passphrasePurpose = null, message = null)
    }

    fun submitPassphrase(value: String, repeat: String) {
        val purpose = stateFlow.value.passphrasePurpose ?: return
        if (stateFlow.value.busy) return
        if (purpose != PassphrasePurpose.Restore && value.length < 8) {
            stateFlow.value = stateFlow.value.copy(message = "Use at least 8 characters")
            return
        }
        if (purpose == PassphrasePurpose.Enable && value != repeat) {
            stateFlow.value = stateFlow.value.copy(message = "The passphrases do not match")
            return
        }
        val secret = value.toCharArray()
        val uri = pendingUri
        pendingUri = null
        stateFlow.value = stateFlow.value.copy(passphrasePurpose = null, busy = true, message = null)
        scope.launch {
            try {
                when (purpose) {
                    PassphrasePurpose.Enable -> {
                        withContext(Dispatchers.IO) { protection?.enable(secret) }
                        val on = protection?.enabled == true
                        stateFlow.value = stateFlow.value.copy(busy = false, encrypted = on)
                        val next = afterEnable
                        afterEnable = null
                        if (on) next?.invoke()
                    }
                    PassphrasePurpose.Export -> {
                        val outcome = service.exportTo(requireNotNull(uri), secret)
                        stateFlow.value = stateFlow.value.copy(busy = false, message = backupMessage(outcome))
                    }
                    PassphrasePurpose.Restore -> {
                        val document = requireNotNull(uri)
                        acceptRestore(service.prepareRestore(document, secret), document)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                afterEnable = null
                stateFlow.value = stateFlow.value.copy(busy = false, message = "Could not save the passphrase")
            } finally {
                secret.fill('\u0000')
            }
        }
    }

    private fun acceptRestore(outcome: BackupOutcome, uri: String) {
        pending = outcome as? BackupOutcome.Ready
        when (outcome) {
            is BackupOutcome.Ready -> stateFlow.value = stateFlow.value.copy(busy = false, pendingRestore = outcome.summary)
            BackupOutcome.NeedsPassphrase -> {
                pendingUri = uri
                stateFlow.value = stateFlow.value.copy(busy = false, passphrasePurpose = PassphrasePurpose.Restore)
            }
            else -> stateFlow.value = stateFlow.value.copy(busy = false, message = backupMessage(outcome))
        }
    }

    private var pending: BackupOutcome.Ready? = null

    /**
     * Reads and checks the document the user chose, then waits for [confirmRestore] (T87). Nothing is
     * changed yet; [BackupUiState.pendingRestore] carries the summary for the confirm step.
     */
    fun restoreFrom(uri: String) {
        if (stateFlow.value.busy || stateFlow.value.passphrasePurpose != null) return
        pending = null
        stateFlow.value = stateFlow.value.copy(busy = true, message = null, pendingRestore = null)
        scope.launch {
            acceptRestore(service.prepareRestore(uri), uri)
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
    BackupOutcome.ProtectionChanged -> "Backup encryption turned off"
    BackupOutcome.NeedsPassphrase -> "Enter your passphrase"
    is BackupOutcome.Exported -> "Backup saved"
    is BackupOutcome.Restored -> "Restored " + outcome.summary.describe()
    is BackupOutcome.Ready -> "Ready to restore"
    is BackupOutcome.Failed -> when (outcome.failure) {
        BackupFailure.UnlockFailed -> "Wrong passphrase or damaged file"
        BackupFailure.ReadFailed -> "Could not read that file"
        BackupFailure.WriteFailed -> "Could not save the backup"
        BackupFailure.InvalidDocument -> "That file is not a Parc backup"
    }
}
