package com.brightblu.parc

import com.brightblu.parc.model.*

/** Framework boundary, kept small enough to exercise cancellation and failures with JVM fakes. */
internal interface PinRequest {
    val valid: Boolean
    val shortcut: Slot.Shortcut?
    val enabled: Boolean
    fun accept(): Boolean
}

internal enum class PinResult { Added, Declined, Unavailable, Duplicate }

internal object ShortcutPlacement {
    fun same(a: Slot.Shortcut, b: Slot.Shortcut): Boolean = a.packageName == b.packageName &&
        a.shortcutId == b.shortcutId && a.userSerial == b.userSerial

    fun contains(layout: ParcLayout, shortcut: Slot.Shortcut) = layout.arcs.any { arc ->
        arc.slots.values.any { it is Slot.Shortcut && same(it, shortcut) }
    }

    /** First visible hole, then append to the last ring; existing items are never overwritten. */
    fun add(layout: ParcLayout, shortcut: Slot.Shortcut, geometry: List<ArcSpec>, folder: FolderId?): ParcLayout {
        if (contains(layout, shortcut) || geometry.isEmpty()) return layout
        if (folder != null && layout.folders.none { it.id == folder && !it.locked }) return layout
        val visible = geometry.filter { spec -> layout.arcs.any { it.arc.id == spec.id } }
        if (visible.isEmpty()) return layout
        val destination = visible.firstOrNull { spec ->
            (0 until spec.slotCapacity).any { i -> layout.arcs.first { it.arc.id == spec.id }.slots[i] == null }
        } ?: visible.last()
        val arc = layout.arcs.first { it.arc.id == destination.id }
        val index = (0 until destination.slotCapacity).firstOrNull { it !in arc.slots }
            ?: ((arc.slots.keys.maxOrNull() ?: -1) + 1)
        return layout.copy(arcs = layout.arcs.map {
            if (it.arc.id == arc.arc.id) it.copy(slots = it.slots + (index to shortcut.copy(folderId = folder))) else it
        })
    }

    fun remove(layout: ParcLayout, shortcut: Slot.Shortcut) = layout.copy(arcs = layout.arcs.map { arc ->
        arc.copy(slots = arc.slots.filterValues { it !is Slot.Shortcut || !same(it, shortcut) })
    })
}

/** Never accepts before a user choice; a failed/cancelled request never creates a slot. */
internal class PinController(private val request: PinRequest) {
    private var finished = false
    fun decline(): PinResult { finished = true; return PinResult.Declined }
    fun confirm(layout: ParcLayout, geometry: List<ArcSpec>, folder: FolderId?,
        save: (ParcLayout) -> Unit): PinResult {
        if (finished || !request.valid || !request.enabled) return PinResult.Unavailable
        val shortcut = request.shortcut ?: return PinResult.Unavailable
        if (ShortcutPlacement.contains(layout, shortcut)) { finished = true; return PinResult.Duplicate }
        val next = ShortcutPlacement.add(layout, shortcut, geometry, folder)
        if (next == layout) return PinResult.Unavailable
        finished = true
        val accepted = try { request.accept() } catch (_: RuntimeException) { false }
        if (!accepted) return PinResult.Unavailable
        save(next)
        return PinResult.Added
    }
}
