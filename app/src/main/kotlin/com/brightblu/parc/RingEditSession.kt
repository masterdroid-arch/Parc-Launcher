package com.brightblu.parc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brightblu.parc.feature.settings.folders.FolderDrag
import com.brightblu.parc.feature.settings.folders.FolderGrid
import com.brightblu.parc.feature.settings.folders.DropVerdict
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingEditing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** Main-confined edit host. A folder reorder is persisted as one atomic layout import. */
internal class RingEditSession(private val repository: LayoutRepository, private val scope: CoroutineScope) : RingEditing {
    override var enabled by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set

    /** One-step undo (T30): the layout before the last accepted drop. */
    var undoAvailable by mutableStateOf(false)
        private set
    private var undoSnapshot: ParcLayout? = null

    private var noticeJob: Job? = null
    fun notify(message: String) {
        noticeJob?.cancel()
        notice = message
        noticeJob = scope.launch {
            delay(Metrics.editNoticeMs)
            notice = ""
        }
    }
    fun onHostPaused() = close()

    var emptyPosition by mutableStateOf<SlotPosition?>(null)
    override fun empty(position: SlotPosition) {
        if (enabled && scene?.layer == Layer.Home && !busy) emptyPosition = position
    }
    var scene: RingState? = null
    override fun enter() { enabled = true }
    fun close() { emptyPosition = null; enabled = false; noticeJob?.cancel(); notice = ""; clearUndo() }

    fun undo() {
        val snapshot = undoSnapshot ?: return
        undoSnapshot = null
        undoAvailable = false
        notice = ""
        scope.launch {
            try {
                repository.importLayout(LayoutJson.encode(snapshot))
            } catch (_: IllegalArgumentException) {
                notify("Layout changed. Try again")
            }
        }
    }

    fun clearUndo() {
        undoSnapshot = null
        undoAvailable = false
    }

    override fun canDrop(from: SlotPosition, to: SlotPosition): Boolean {
        val current = scene ?: return false
        return enabled && !busy && editDropAllowed(repository.layout.value, current, from, to)
    }

    override fun drop(from: SlotPosition, to: SlotPosition) {
        if (!canDrop(from, to)) return
        val current = scene ?: return
        val before = repository.layout.value
        val target = before.arcs.firstOrNull { it.arc.id == to.arcId }?.slots?.get(to.index)
        val source = current.slots.firstOrNull { it.position == from }?.slot
        busy = true
        scope.launch {
            try {
                if (current.layer == Layer.Home && target is Slot.Folder && source is Slot.App) {
                    // Dropping an app on a folder disc adds it to that folder (T30). Apps may sit in
                    // several folders, so the home slot is left alone.
                    repository.addToFolder(target.id, source.id)
                } else if (current.layer == Layer.Folder) {
                    val snapshot = repository.layout.value
                    val folder = snapshot.folders.first { it.id == current.arcs.first().folderId }
                    val grid = FolderGrid(current.arcs.map { it.slotCapacity }, folder.appIds)
                    val reordered = FolderDrag.reorder(grid, folderIndex(current, from), folderIndex(current, to), snapshot.pins)
                    repository.importLayout(LayoutJson.encode(snapshot.copy(folders = snapshot.folders.map {
                        if (it.id == folder.id) it.copy(appIds = reordered.appIds) else it
                    })))
                } else repository.move(from, to)
                notify("Moved")
                undoSnapshot = before
                undoAvailable = true
            } catch (_: IllegalArgumentException) {
                notify("Layout changed. Try again")
            } finally { busy = false }
        }
    }
}

/** Stored rank offsets from RingScenes.folder; paged/filtered scenes are refused below. */
internal fun folderIndex(scene: RingState, position: SlotPosition): Int {
    val arc = scene.arcs.indexOfFirst { it.id == position.arcId }
    if (arc < 0 || position.index !in 0 until scene.arcs[arc].slotCapacity) return -1
    return when (arc) { 0 -> 0; 1 -> 4; 2 -> 10; else -> 18 } + position.index
}

internal fun editDropAllowed(layout: ParcLayout, scene: RingState, from: SlotPosition, to: SlotPosition): Boolean {
    if (scene.layer == Layer.Recents || scene.layer == Layer.AllApps || from == to) return false
    if (scene.arcs.none { it.id == to.arcId && to.index in 0 until it.slotCapacity }) return false
    val source = scene.slots.firstOrNull { it.position == from } ?: return false
    if (!source.enabled || source.slot is Slot.Action) return false
    if (scene.layer == Layer.Folder) {
        val folder = layout.folders.firstOrNull { it.id == scene.arcs.firstOrNull()?.folderId } ?: return false
        // Reordering paged/filtered scenes needs a rank-to-document mapping. Refuse rather than
        // applying a visible index to a different app in the stored document.
        if (scene.slots.any { slot -> folder.appIds.getOrNull(folderIndex(scene, slot.position)) != (slot.slot as? Slot.App)?.id }) return false
        val grid = FolderGrid(scene.arcs.map { it.slotCapacity }, folder.appIds)
        return FolderDrag.canDrop(grid, folderIndex(scene, from), folderIndex(scene, to), layout.pins) == DropVerdict.Allowed
    }
    val stored = layout.arcs.firstOrNull { it.arc.id == from.arcId }?.slots?.get(from.index)
    if (stored != source.slot) return false
    // T30: a folder disc on Home is a valid drop target for an app.
    if (source.slot is Slot.App && scene.layer == Layer.Home) {
        val target = layout.arcs.firstOrNull { it.arc.id == to.arcId }?.slots?.get(to.index)
        if (target is Slot.Folder) return true
    }
    val target = layout.arcs.firstOrNull { it.arc.id == to.arcId }?.slots?.get(to.index)
    return target !is Slot.Action && (target !is Slot.App || target.id !in layout.pins)
}
