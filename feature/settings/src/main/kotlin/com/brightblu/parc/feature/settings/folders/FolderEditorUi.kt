package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.FolderId

/** One row of the folder list. */
data class FolderRow(val id: FolderId, val name: String, val appCount: Int)

/** One slot of the open folder. [unknown] marks an app that is no longer installed. */
data class FolderSlotRow(
    val slot: Int,
    val arcIndex: Int,
    val appId: AppId?,
    val label: String,
    val unknown: Boolean,
    val pinned: Boolean,
    val suspended: Boolean,
    val dragged: Boolean,
    val dropTarget: Boolean,
    val refusesDrop: Boolean,
)

/** One row of the app picker. */
data class FolderAppRow(
    val appId: AppId,
    val label: String,
    val inFolder: Boolean,
    val suspended: Boolean,
)

/** The inline name field. */
data class FolderNameUi(val title: String, val draft: String, val confirmLabel: String, val issue: String?, val isCreate: Boolean = false)

/** Everything the Compose list draws. Immutable, non-null; no bitmaps and no Android types. */
data class FolderEditorUi(
    val page: EditorPage,
    val title: String,
    val folders: List<FolderRow>,
    val slots: List<FolderSlotRow>,
    val picker: List<FolderAppRow>,
    val prompt: FolderNameUi?,
    val notice: String?,
    val canUndo: Boolean,
    val undoLabel: String?,
    val folderIsFull: Boolean,
)

/**
 * Projects an editor snapshot for the lists. Pure: labels come from [apps], nothing else is read.
 * Slots of the open folder are laid out in grid order and always fill the whole grid, so an empty
 * slot is visible as an empty row (the ring draws a dot there while editing, T12).
 */
fun FolderEditorState.toUi(apps: List<AppEntry>): FolderEditorUi {
    val entries = apps.associateBy { it.id }
    val open = openFolder
    val grid = grid
    val drag = this.drag

    val slots = if (grid == null || page == EditorPage.Folders) emptyList() else (0 until grid.capacity).map { slot ->
        val id = grid.appAt(slot)
        val entry = id?.let(entries::get)
        val verdict = if (id != null && drag != null) {
            FolderDrag.canDrop(grid, drag.from, slot, document.pins)
        } else {
            null
        }
        FolderSlotRow(
            slot = slot,
            arcIndex = grid.arcIndexOf(slot),
            appId = id,
            label = entry?.label.orEmpty(),
            unknown = id != null && entry == null,
            pinned = id != null && id in document.pins,
            suspended = entry?.suspended == true,
            dragged = id != null && drag?.from == slot,
            dropTarget = drag != null && drag.over == slot && drag.from != slot,
            refusesDrop = verdict is DropVerdict.Rejected,
        )
    }

    val picker = apps.asSequence()
        .filterNot { it.id in document.hidden }
        .sortedBy { it.label.lowercase() }
        .map { FolderAppRow(it.id, it.label, open?.appIds?.contains(it.id) == true, it.suspended) }
        .toList()

    val title = when (page) {
        EditorPage.Folders -> "Folders"
        EditorPage.Contents -> open?.name.orEmpty()
        EditorPage.AppPicker -> "Add to ${open?.name.orEmpty()}"
    }
    val prompt = this.prompt?.let {
        FolderNameUi(
            title = if (it.isCreate) "New folder" else "Rename folder",
            draft = it.draft,
            confirmLabel = if (it.isCreate) "Add" else "Done",
            issue = it.issue,
            isCreate = it.isCreate,
        )
    }
    return FolderEditorUi(
        page = page,
        title = title,
        folders = document.folders.map { FolderRow(it.id, it.name, it.appIds.size) },
        slots = slots,
        picker = picker,
        prompt = prompt,
        notice = notice,
        canUndo = canUndo,
        undoLabel = undoLabel,
        folderIsFull = grid != null && !grid.hasFreeSlot,
    )
}
