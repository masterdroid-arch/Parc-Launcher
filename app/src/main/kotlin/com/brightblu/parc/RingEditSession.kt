package com.brightblu.parc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

/** Words for the locked layout, used by the edit entry and by every guarded layout action. */
internal object LayoutLockText {
    const val Notice = "Layout is locked. Turn it off in Settings > Home."
}

/** Main-confined edit host. A folder reorder is persisted as one atomic layout import. */
internal class RingEditSession(
    private val repository: LayoutRepository,
    private val scope: CoroutineScope,
    /** T245: read the live "Lock layout" choice; every guarded mutation asks again. */
    private val locked: () -> Boolean = { false },
) : RingEditing {
    override var enabled by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf("")
        private set

    /** T245: the whole-session edit history. Outside edit mode it is one chip that expires. */
    private val history = LayoutUndoHistory()
    var undoAvailable by mutableStateOf(false)
        private set
    /** Short words for the Undo chip ("Moved", "App added"). Observable so the chip can change. */
    var undoLabel by mutableStateOf("")
        private set
    /** Bumped by every change so the host restarts the chip's few seconds. */
    var undoRevision by mutableIntStateOf(0)
        private set

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
        if (enabled && !locked() && scene?.layer == Layer.Home && !busy) emptyPosition = position
    }
    var scene: RingState? = null

    /** T245: a locked Home explains itself instead of entering edit mode. */
    override fun enter() {
        if (locked()) { refuseLocked(); return }
        clearUndo()
        enabled = true
    }

    fun close() { emptyPosition = null; enabled = false; noticeJob?.cancel(); notice = ""; clearUndo() }

    fun refuseLocked() = notify(LayoutLockText.Notice)

    /** One step from the session history. The chip stays while earlier steps remain. */
    fun undo() {
        val target = history.undo() ?: return
        notice = ""
        syncUndo()
        scope.launch {
            try {
                repository.importLayout(LayoutJson.encode(target))
            } catch (_: IllegalArgumentException) {
                notify("Layout changed. Try again")
            }
        }
    }

    fun clearUndo() {
        history.clear()
        syncUndo()
    }

    /** Records [before] only when the mutation really changed the layout. Safe to call from the host. */
    fun recordChange(before: ParcLayout, label: String) {
        if (before == repository.layout.value) return
        history.record(before, label)
        syncUndo()
    }

    /**
     * One layout mutation with undo. A guarded change is refused while the layout is locked and the
     * user is told why; a folder-only change (rename, sort, membership) is still recorded.
     */
    suspend fun change(label: String, guarded: Boolean = true, block: suspend () -> Unit) {
        if (guarded && locked()) { refuseLocked(); return }
        val before = repository.layout.value
        try {
            block()
            recordChange(before, label)
        } catch (_: IllegalArgumentException) {
            notify("Layout changed. Try again")
        }
    }

    private fun syncUndo() {
        undoAvailable = history.available
        undoLabel = history.label
        undoRevision++
    }

    override fun canDrop(from: SlotPosition, to: SlotPosition): Boolean {
        val current = scene ?: return false
        return enabled && !busy && !locked() && editDropAllowed(repository.layout.value, current, from, to)
    }

    override fun drop(from: SlotPosition, to: SlotPosition) {
        if (!canDrop(from, to)) return
        val current = scene ?: return
        val before = repository.layout.value
        val target = before.arcs.firstOrNull { it.arc.id == to.arcId }?.slots?.get(to.index)
        val source = current.slots.firstOrNull { it.position == from }?.slot
        busy = true
        scope.launch {
            var message = "Moved"
            try {
                if (current.layer == Layer.Home && target is Slot.Folder && source is Slot.App) {
                    // Dropping an app on a folder disc adds it to that folder (T30). Apps may sit in
                    // several folders, so the home slot is left alone.
                    repository.addToFolder(target.id, source.id)
                    // T271: say it stays on the arc, so it does not read like a duplicate bug.
                    message = "Added to the folder. It stays on the arc."
                } else if (current.layer == Layer.Folder) {
                    val snapshot = repository.layout.value
                    val folder = snapshot.folders.first { it.id == current.arcs.first().folderId }
                    if (source is Slot.App && FolderDrop.homeArc(snapshot, to.arcId) != null) {
                        // T244: the app leaves the folder and lands on the Home arc under the finger.
                        val next = FolderDrop.exit(snapshot, folder.id, source.id, to.arcId)
                        if (next == null) {
                            notify("Layout changed. Try again")
                            return@launch
                        }
                        repository.importLayout(LayoutJson.encode(next))
                        // The last app leaving is not an error: the folder stays, empty, and says so.
                        if (next.folders.firstOrNull { it.id == folder.id }?.appIds.orEmpty().isEmpty()) {
                            message = "Moved. The folder is empty now. Go Back to leave it."
                        }
                    } else {
                        val grid = FolderGrid(current.arcs.map { it.slotCapacity }, folder.appIds)
                        val reordered = FolderDrag.reorder(grid, folderIndex(current, from), folderIndex(current, to), snapshot.pins)
                        repository.importLayout(LayoutJson.encode(snapshot.copy(folders = snapshot.folders.map {
                            if (it.id == folder.id) it.copy(appIds = reordered.appIds) else it
                        })))
                    }
                } else {
                    // T243: a drag reorders the arc (the neighbours make room); it does not swap.
                    val next = SlotOrder.move(before, from, to)
                    if (next == null) {
                        notify("Layout changed. Try again")
                        return@launch
                    }
                    repository.importLayout(LayoutJson.encode(next))
                }
                notify(message)
                recordChange(before, "Moved")
            } catch (_: IllegalArgumentException) {
                notify("Layout changed. Try again")
            } finally { busy = false }
        }
    }
}

/** Folder rank from the live arcs' capacities; paged/filtered scenes are refused below. */
internal fun folderIndex(scene: RingState, position: SlotPosition): Int {
    var offset = 0
    for (arc in scene.arcs) {
        if (arc.id == position.arcId) {
            if (position.index !in 0 until arc.slotCapacity) return -1
            return offset + position.index
        }
        offset += arc.slotCapacity
    }
    return -1
}

internal fun editDropAllowed(layout: ParcLayout, scene: RingState, from: SlotPosition, to: SlotPosition): Boolean {
    if (scene.layer == Layer.Recents || scene.layer == Layer.AllApps || from == to) return false
    val source = scene.slots.firstOrNull { it.position == from } ?: return false
    if (!source.enabled || source.slot is Slot.Action) return false
    if (scene.layer == Layer.Folder) {
        val folder = layout.folders.firstOrNull { it.id == scene.arcs.firstOrNull()?.folderId } ?: return false
        val home = FolderDrop.homeArc(layout, to.arcId)
        if (home != null) {
            if (to.index !in 0 until home.arc.slotCapacity) return false
            val app = source.slot as? Slot.App ?: return false
            return FolderDrop.canExit(layout, folder.id, app.id, to.arcId)
        }
        // Reordering paged/filtered scenes needs a rank-to-document mapping. Refuse rather than
        // applying a visible index to a different app in the stored document.
        if (scene.slots.any { slot -> folder.appIds.getOrNull(folderIndex(scene, slot.position)) != (slot.slot as? Slot.App)?.id }) return false
        if (scene.arcs.none { it.id == to.arcId && to.index in 0 until it.slotCapacity }) return false
        val grid = FolderGrid(scene.arcs.map { it.slotCapacity }, folder.appIds)
        return FolderDrag.canDrop(grid, folderIndex(scene, from), folderIndex(scene, to), layout.pins) == DropVerdict.Allowed
    }
    if (scene.arcs.none { it.id == to.arcId && to.index in 0 until it.slotCapacity }) return false
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
