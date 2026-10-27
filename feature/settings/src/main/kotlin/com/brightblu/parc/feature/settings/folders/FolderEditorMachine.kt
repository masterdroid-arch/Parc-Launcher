package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder

/**
 * Pure reducer for the folder editor (T11). Same event, same state, same result: no Android types,
 * no clocks, no randomness. Persisting the resulting document is [FolderEditorController]'s job.
 *
 * Structure: the folder list is the entry page; a folder opens as a packed slot grid; the app picker
 * is a page on the open folder. Every change to the document pushes one undo step, so Undo always
 * restores the previous document.
 */
object FolderEditorMachine {
    /** Undo depth. Enough for one editing session, bounded so the state stays small. */
    const val MaxUndoSteps = 24

    fun reduce(input: FolderEditorState, event: FolderEditorEvent): FolderEditorState {
        val leaving = event == FolderEditorEvent.CloseFolder || event == FolderEditorEvent.OpenAppPicker
        val state = if (leaving) reduce(input, FolderEditorEvent.ApplyNameDraft) else input
        if (leaving && state.prompt?.issue != null) return state
        return when (event) {
            is FolderEditorEvent.OpenFolder -> {
                val folder = state.document.folder(event.id)
                if (folder == null) state
                else state.copy(
                    page = EditorPage.Contents,
                    openFolderId = folder.id,
                    drag = null,
                    prompt = null,
                    notice = null,
                )
            }

            FolderEditorEvent.CloseFolder -> when {
                state.page == EditorPage.AppPicker ->
                    state.copy(page = EditorPage.Contents, drag = null, prompt = null, notice = null)

                else -> state.copy(
                    page = EditorPage.Folders,
                    openFolderId = null,
                    drag = null,
                    prompt = null,
                    notice = null,
                )
            }

            FolderEditorEvent.BeginCreateFolder -> state.copy(
                page = EditorPage.Folders,
                openFolderId = null,
                prompt = NamePrompt(folderId = null, draft = ""),
                notice = null,
            )

            FolderEditorEvent.BeginRename -> {
                val folder = state.openFolder
                if (folder == null) state
                else state.copy(prompt = NamePrompt(folder.id, folder.name), notice = null)
            }

            is FolderEditorEvent.NameChanged -> {
                val prompt = state.prompt
                if (prompt == null) state
                else state.copy(prompt = prompt.copy(draft = event.text, issue = null))
            }

            FolderEditorEvent.CancelName -> state.copy(prompt = null)

            FolderEditorEvent.ApplyNameDraft -> {
                val prompt = state.prompt
                if (prompt == null || prompt.isCreate) state else {
                    val applied = confirmName(state)
                    if (applied.prompt != null) applied else applied.copy(prompt = prompt.copy(issue = null))
                }
            }
            FolderEditorEvent.ConfirmName -> confirmName(state)

            is FolderEditorEvent.AddApp -> addApp(state, event.id)

            is FolderEditorEvent.RemoveApp -> removeApp(state, event.id)

            FolderEditorEvent.OpenAppPicker ->
                if (state.openFolder == null) state else state.copy(page = EditorPage.AppPicker)

            is FolderEditorEvent.DragStart -> dragStart(state, event.slot)

            is FolderEditorEvent.DragTo -> {
                val drag = state.drag
                val capacity = state.grid?.capacity ?: 0
                if (drag == null || capacity <= 0) state
                else state.copy(drag = drag.copy(over = event.slot.coerceIn(0, capacity - 1)))
            }

            FolderEditorEvent.DragCancel -> state.copy(drag = null)

            FolderEditorEvent.Drop -> drop(state)

            FolderEditorEvent.Undo -> undo(state)
        }
    }

    private fun confirmName(state: FolderEditorState): FolderEditorState {
        val prompt = state.prompt ?: return state
        val name = FolderNames.normalise(prompt.draft)
        val others = state.document.folders.filter { it.id != prompt.folderId }.map { it.name }
        val issue = FolderNames.issue(name, others)
        if (issue != null) return state.copy(prompt = prompt.copy(issue = issue))

        val creating = prompt.folderId == null
        val folder: Folder = if (creating) {
            Folder(id = FolderIds.fromName(name, state.document.folders.mapTo(mutableSetOf()) { it.id }), name = name)
        } else {
            state.openFolder?.copy(name = name) ?: return state
        }
        val document = state.document.withFolder(folder)
        val next = if (document == state.document) state else push(state, document, if (creating) "New folder" else "Rename")
        return next.copy(
            prompt = null,
            page = if (creating) EditorPage.Contents else next.page,
            openFolderId = if (creating) folder.id else next.openFolderId,
            notice = if (creating) "Folder created" else null,
        )
    }

    private fun addApp(state: FolderEditorState, id: AppId): FolderEditorState {
        val folder = state.openFolder
        val grid = state.grid
        return when {
            folder == null || grid == null -> state
            !grid.hasFreeSlot -> state.copy(notice = "This folder is full")
            grid.contains(id) -> state
            else -> push(
                state,
                state.document.withFolder(folder.copy(appIds = grid.appended(id).appIds)),
                "Add app",
            ).copy(notice = null)
        }
    }

    private fun removeApp(state: FolderEditorState, id: AppId): FolderEditorState {
        val folder = state.openFolder
        val grid = state.grid
        return if (folder == null || grid == null || !grid.contains(id)) state
        else push(
            state,
            state.document.withFolder(folder.copy(appIds = grid.removed(id).appIds)),
            "Remove app",
        ).copy(notice = null, drag = null)
    }

    private fun dragStart(state: FolderEditorState, slot: Int): FolderEditorState {
        val capacity = state.grid?.capacity ?: 0
        return if (slot !in 0 until capacity) state
        else state.copy(drag = DragState(slot, slot), notice = null)
    }

    private fun drop(state: FolderEditorState): FolderEditorState {
        val folder = state.openFolder
        val grid = state.grid
        val drag = state.drag
        if (folder == null || grid == null || drag == null) return state.copy(drag = null)
        return when (val verdict = FolderDrag.canDrop(grid, drag.from, drag.over, state.document.pins)) {
            DropVerdict.Allowed -> {
                val moved = FolderDrag.reorder(grid, drag.from, drag.over, state.document.pins)
                push(
                    state,
                    state.document.withFolder(folder.copy(appIds = moved.appIds)),
                    "Move app",
                ).copy(drag = null, notice = null)
            }

            is DropVerdict.Rejected -> state.copy(drag = null, notice = verdict.reason.message)
        }
    }

    private fun undo(state: FolderEditorState): FolderEditorState {
        val step = state.undo.lastOrNull() ?: return state
        val stillOpen = step.document.folder(state.openFolderId)
        return state.copy(
            document = step.document,
            undo = state.undo.dropLast(1),
            drag = null,
            prompt = null,
            notice = "Undid: ${step.label}",
            openFolderId = state.openFolderId.takeIf { stillOpen != null },
            page = if (stillOpen == null) EditorPage.Folders else state.page,
        )
    }

    private fun push(
        state: FolderEditorState,
        document: FolderDocument,
        label: String,
    ): FolderEditorState = state.copy(
        document = document,
        undo = (state.undo + UndoStep(state.document, label)).takeLast(MaxUndoSteps),
    )
}
