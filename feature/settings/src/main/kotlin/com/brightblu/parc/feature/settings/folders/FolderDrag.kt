package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppId
import kotlin.math.roundToInt

/** Why a drop is refused. Drives the REJECT haptic and the ring's drop highlight (DESIGN.md §2.7). */
enum class DropRejection(val message: String) {
    OutOfRange("That slot is off the ring"),
    SameSlot("Already in that slot"),
    EmptySource("Nothing to move"),
    PinnedTarget("Pinned apps stay put"),
}

/** Outcome of [FolderDrag.canDrop]. */
sealed interface DropVerdict {
    data object Allowed : DropVerdict
    data class Rejected(val reason: DropRejection) : DropVerdict
}

/**
 * Pure drag logic for folder slots (T11). Slot indices are positions in [FolderGrid], not screen
 * coordinates: the ring turns a finger position into a slot with `RingLayout.hitTest`, the editor
 * turns a row offset into a slot with [dropTargetSlot].
 *
 * The folder list is packed, so a drop moves the app to the target position and shifts the apps
 * between the two positions by one, exactly like a list reorder. A drop on an empty slot therefore
 * lands at the end of the packed order.
 */
object FolderDrag {
    /**
     * A drop is allowed when both slots exist, the source holds an app and the target is not held by
     * a pinned app. The dragged app may itself be pinned: pins stop rank recomputes, not the user's
     * own edit (DESIGN.md §4).
     */
    fun canDrop(grid: FolderGrid, from: Int, to: Int, pinned: Set<AppId> = emptySet()): DropVerdict {
        if (from !in 0 until grid.capacity || to !in 0 until grid.capacity) {
            return DropVerdict.Rejected(DropRejection.OutOfRange)
        }
        if (from == to) return DropVerdict.Rejected(DropRejection.SameSlot)
        if (grid.appAt(from) == null) return DropVerdict.Rejected(DropRejection.EmptySource)
        val occupant = grid.appAt(to)
        if (occupant != null && occupant in pinned) {
            return DropVerdict.Rejected(DropRejection.PinnedTarget)
        }
        return DropVerdict.Allowed
    }

    /** Applies an allowed drop. Throws [IllegalArgumentException] when [canDrop] refuses it. */
    fun reorder(grid: FolderGrid, from: Int, to: Int, pinned: Set<AppId> = emptySet()): FolderGrid {
        val verdict = canDrop(grid, from, to, pinned)
        require(verdict is DropVerdict.Allowed) {
            "Refused drop from $from to $to: ${(verdict as DropVerdict.Rejected).reason}"
        }
        val ids = grid.appIds.toMutableList()
        val moved = ids.removeAt(from)
        ids.add(to.coerceIn(0, ids.size), moved)
        return grid.reordered(ids)
    }

    /**
     * Target slot for a drag that started on [from] and has travelled [dragYDp] down a list of
     * [slots] rows of [rowHeightDp]. Clamped to the grid; used by the Compose list and testable
     * without a device.
     */
    fun dropTargetSlot(from: Int, dragYDp: Float, rowHeightDp: Float, slots: Int): Int {
        require(rowHeightDp > 0f) { "Row height must be positive" }
        if (slots <= 0) return -1
        if (!dragYDp.isFinite()) return from.coerceIn(0, slots - 1)
        val steps = (dragYDp / rowHeightDp).roundToInt()
        return (from + steps).coerceIn(0, slots - 1)
    }
}
