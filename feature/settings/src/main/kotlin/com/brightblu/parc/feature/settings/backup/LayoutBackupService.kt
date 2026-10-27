package com.brightblu.parc.feature.settings.backup

import com.brightblu.parc.core.data.backup.BackupFileStore
import com.brightblu.parc.core.data.backup.BackupFormatException
import com.brightblu.parc.core.data.backup.BackupRegistry
import com.brightblu.parc.core.data.backup.BackupStore
import com.brightblu.parc.core.data.backup.FullBackup
import com.brightblu.parc.core.data.backup.ParsedBackup
import com.brightblu.parc.core.data.backup.RestoreSummary
import com.brightblu.parc.model.LayoutRepository
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/** Why a backup action could not finish. Never leaked to the layout: a failed restore changes nothing. */
enum class BackupFailure { ReadFailed, WriteFailed, InvalidDocument }

/** Result of one export or restore. Immutable, non-null. */
sealed interface BackupOutcome {
    /** The layout JSON was written to the picked document. [sizeBytes] is the UTF-8 byte count. */
    data class Exported(val sizeBytes: Int) : BackupOutcome

    /**
     * The picked document replaced the layout and the settings it contained. Counts describe the
     * restored snapshot ([summary] is what the user was shown before confirming).
     */
    data class Restored(
        val folders: Int,
        val pinned: Int,
        val hidden: Int,
        val summary: RestoreSummary = RestoreSummary(folders, 0, 0),
    ) : BackupOutcome

    /** A file was read and checked; nothing is changed until the user confirms (T87). */
    data class Ready(val summary: RestoreSummary, internal val parsed: ParsedBackup) : BackupOutcome

    /** Nothing changed on disk or in memory. */
    data class Failed(val failure: BackupFailure) : BackupOutcome
}

/**
 * Export and restore of the versioned layout JSON (T17, F-15), the one document the frozen
 * [LayoutRepository] already round-trips (PROJECT.md §6.3).
 *
 * Thread-safe: all state lives in the injected repository and file store. [exportTo] and [restoreFrom]
 * are suspending and safe to call from any dispatcher. A restore is atomic because
 * [LayoutRepository.importLayout] decodes and validates the whole document before it writes; a
 * malformed or foreign file therefore throws and the current layout is untouched.
 */
class LayoutBackupService(
    private val repository: LayoutRepository,
    private val files: BackupFileStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val stores: () -> List<BackupStore> = { BackupRegistry.stores },
) {
    private fun full() = FullBackup(repository, stores())

    /** The name the export picker suggests, dated in the device's own zone. */
    fun suggestedFileName(): String = BackupFileName.of(clock(), zone)

    /** Writes the current layout to [uri]. */
    suspend fun exportTo(uri: String): BackupOutcome {
        val text = full().export()
        return try {
            files.write(uri, text)
            BackupOutcome.Exported(text.toByteArray(Charsets.UTF_8).size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BackupOutcome.Failed(BackupFailure.WriteFailed)
        }
    }

    /**
     * Reads and validates the document at [uri] without changing anything (T87). A [BackupOutcome.Ready]
     * carries the summary to show in the confirm step; pass it to [confirm] to apply it.
     */
    suspend fun prepareRestore(uri: String): BackupOutcome {
        val text = try {
            files.read(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return BackupOutcome.Failed(BackupFailure.ReadFailed)
        }
        return try {
            val parsed = full().parse(text)
            BackupOutcome.Ready(parsed.summary, parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupFormatException) {
            BackupOutcome.Failed(BackupFailure.InvalidDocument)
        } catch (e: Exception) {
            BackupOutcome.Failed(BackupFailure.InvalidDocument)
        }
    }

    /** Applies a document checked by [prepareRestore]. A layout the repository refuses changes nothing. */
    suspend fun confirm(ready: BackupOutcome.Ready): BackupOutcome = try {
        val summary = full().apply(ready.parsed)
        BackupOutcome.Restored(
            folders = repository.layout.value.folders.size,
            pinned = repository.layout.value.pins.size,
            hidden = repository.layout.value.hidden.size,
            summary = summary,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BackupOutcome.Failed(BackupFailure.InvalidDocument)
    }

    /** Reads and applies the document at [uri] in one step (no confirmation). Failures change nothing. */
    suspend fun restoreFrom(uri: String): BackupOutcome =
        when (val prepared = prepareRestore(uri)) {
            is BackupOutcome.Ready -> confirm(prepared)
            else -> prepared
        }
}
