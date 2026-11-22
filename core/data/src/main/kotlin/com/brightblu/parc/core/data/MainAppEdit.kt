package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot

/** Expected occupants come from the displayed rows. A stale tap can never edit their replacements. */
sealed interface MainAppEdit {
    val id: AppId

    data class Add(override val id: AppId) : MainAppEdit
    data class Restore(override val id: AppId, val index: Int, val pinned: Boolean) : MainAppEdit
    data class Remove(override val id: AppId, val index: Int) : MainAppEdit
    data class Move(override val id: AppId, val index: Int, val to: Int, val neighbor: Slot?) : MainAppEdit
}

class MainAppEditRefused(message: String) : IllegalStateException(message)

/** Called inside the same layout transaction used for drag and search placement. */
fun ParcLayout.editMainApp(edit: MainAppEdit, locked: Boolean): ParcLayout {
    if (locked) throw MainAppEditRefused("Layout locked. Unlock in Settings › Home › Arcs.")
    fun stale(): Nothing = throw MainAppEditRefused("The inner arc changed. Check its positions and try again.")
    val inner = arcs.firstOrNull { it.arc.id == MainAppsArcId } ?: stale()
    val slots = inner.slots.toMutableMap()
    val app = Slot.App(edit.id)
    val visible = 0 until minOf(MainAppsSlotCount, inner.arc.slotCapacity)
    var nextPins = pins
    when (edit) {
        is MainAppEdit.Add -> {
            if (visible.any { slots[it] == app }) stale()
            val free = visible.firstOrNull { slots[it] == null }
                ?: throw MainAppEditRefused("Inner arc is full. Remove a main app first.")
            slots.entries.removeAll { it.value == app }
            slots[free] = app
            nextPins = pins + edit.id
        }
        is MainAppEdit.Restore -> {
            if (edit.index !in visible || slots[edit.index] != null ||
                arcs.any { it.arc.layer == Layer.Home && app in it.slots.values }) stale()
            slots[edit.index] = app
            if (edit.pinned) nextPins = pins + edit.id
        }
        is MainAppEdit.Remove -> {
            if (edit.index !in visible || slots[edit.index] != app) stale()
            slots.remove(edit.index)
            if (app !in slots.values && arcs.none { it !== inner && app in it.slots.values }) {
                nextPins = pins - edit.id
            }
        }
        is MainAppEdit.Move -> {
            if (edit.index !in visible || edit.to !in visible || slots[edit.index] != app || slots[edit.to] != edit.neighbor ||
                (edit.neighbor != null && edit.neighbor !is Slot.App)) stale()
            if (edit.neighbor == null) slots.remove(edit.index) else slots[edit.index] = edit.neighbor
            slots[edit.to] = app
        }
    }
    return copy(arcs = arcs.map {
        when {
            it === inner -> it.copy(slots = slots)
            edit is MainAppEdit.Add && it.arc.layer == Layer.Home && app in it.slots.values ->
                it.copy(slots = it.slots.filterValues { occupant -> occupant != app })
            else -> it
        }
    }, pins = nextPins)
}
