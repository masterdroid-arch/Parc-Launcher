package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.LayoutRepository

/**
 * Applies folder-editor documents to the layout (T11). One method, so the editor's persistence has a
 * single seam that unit tests can substitute.
 */
interface FolderEditWriter {
    /**
     * Writes the difference between two documents. Nothing is written when the result is
     * [FolderEditResult.Unsupported].
     */
    suspend fun apply(previous: FolderDocument, next: FolderDocument): FolderEditResult
}

/** Result of one [FolderEditWriter.apply]. */
sealed interface FolderEditResult {
    data object Applied : FolderEditResult

    /** The frozen contract cannot express this edit. Nothing was written; [reason] is user-facing. */
    data class Unsupported(val reason: String) : FolderEditResult
}

/**
 * [FolderEditWriter] over the frozen [LayoutRepository]. Folders are created, renamed, and have their
 * members added or removed, which is everything the contract offers.
 *
 * Reordering a folder needs an ordered rewrite: [LayoutRepository.addToFolder] only appends, so a
 * changed order is rebuilt by removing the affected members and adding them back in the new order.
 * That is a sequence of writes, not one transaction, and a folder cannot hold a hole. Deleting a
 * folder is impossible (`createFolder`/`renameFolder` are the only folder mutations), so undoing a
 * creation is reported as [FolderEditResult.Unsupported]. Both gaps are requested in `reports/T11.md`.
 */
class LayoutFolderWriter(private val repository: LayoutRepository) : FolderEditWriter {

    override suspend fun apply(previous: FolderDocument, next: FolderDocument): FolderEditResult {
        val removed = previous.folders.mapTo(mutableSetOf()) { it.id } -
            next.folders.mapTo(mutableSetOf()) { it.id }
        if (removed.isNotEmpty()) {
            return FolderEditResult.Unsupported("Parc cannot delete folders yet")
        }
        next.folders.forEach { desired ->
            val current = repository.layout.value.folders.firstOrNull { it.id == desired.id }
            if (current == null) {
                repository.createFolder(desired.id, desired.name)
                desired.appIds.forEach { repository.addToFolder(desired.id, it) }
            } else {
                if (current.name != desired.name) repository.renameFolder(desired.id, desired.name)
                syncMembers(desired.id, current.appIds, desired.appIds)
            }
        }
        return FolderEditResult.Applied
    }

    /** Appends, drops, or rebuilds a changed order. The repository holds the apps; the order is ours. */
    private suspend fun syncMembers(folderId: FolderId, current: List<AppId>, desired: List<AppId>) {
        if (current == desired) return
        if (desired.size > current.size && desired.subList(0, current.size) == current) {
            desired.drop(current.size).forEach { repository.addToFolder(folderId, it) }
            return
        }
        if (desired.size < current.size && current.filter { it in desired } == desired) {
            current.filterNot { it in desired }.forEach { repository.removeFromFolder(folderId, it) }
            return
        }
        current.forEach { repository.removeFromFolder(folderId, it) }
        desired.forEach { repository.addToFolder(folderId, it) }
    }
}
