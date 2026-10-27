package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId

/** Which list the editor is showing. One page at a time, no overlays. */
enum class EditorPage { Folders, Contents, AppPicker }

/** In-flight drag inside a folder: the slot the finger started on and the slot it is over. */
data class DragState(val from: Int, val over: Int)

/** Inline name field. A null [folderId] means the folder is being created. */
data class NamePrompt(val folderId: FolderId?, val draft: String, val issue: String? = null) {
    val isCreate: Boolean get() = folderId == null
}

/** One undo step: the document before the action and the action's name, for the Undo row. */
data class UndoStep(val document: FolderDocument, val label: String)

/**
 * Immutable folder-editor snapshot (T11). Non-null, no Android types; the reducer in
 * [FolderEditorMachine] is the only way it changes. [arcCapacities] is host-injected geometry.
 */
data class FolderEditorState(
    val arcCapacities: List<Int>,
    val document: FolderDocument = FolderDocument(),
    val page: EditorPage = EditorPage.Folders,
    val openFolderId: FolderId? = null,
    val drag: DragState? = null,
    val prompt: NamePrompt? = null,
    val notice: String? = null,
    val undo: List<UndoStep> = emptyList(),
) {
    val openFolder: Folder? get() = document.folder(openFolderId)

    /** Grid of the open folder, or null on the folder list. */
    val grid: FolderGrid? get() = openFolder?.let { FolderGrid(arcCapacities, it.appIds) }

    val canUndo: Boolean get() = undo.isNotEmpty()

    val undoLabel: String? get() = undo.lastOrNull()?.label
}

/** Everything the editor can be asked to do. Immutable, non-null. */
sealed interface FolderEditorEvent {
    data class OpenFolder(val id: FolderId) : FolderEditorEvent
    data object CloseFolder : FolderEditorEvent

    data object BeginCreateFolder : FolderEditorEvent
    data object BeginRename : FolderEditorEvent
    data class NameChanged(val text: String) : FolderEditorEvent
    data object ApplyNameDraft : FolderEditorEvent
    data object ConfirmName : FolderEditorEvent
    data object CancelName : FolderEditorEvent

    data class AddApp(val id: AppId) : FolderEditorEvent
    data class RemoveApp(val id: AppId) : FolderEditorEvent
    data object OpenAppPicker : FolderEditorEvent

    data class DragStart(val slot: Int) : FolderEditorEvent
    data class DragTo(val slot: Int) : FolderEditorEvent
    data object DragCancel : FolderEditorEvent
    data object Drop : FolderEditorEvent

    data object Undo : FolderEditorEvent
}
