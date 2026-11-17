package com.brightblu.parc

import com.brightblu.parc.model.*

/**
 * T244: moving an app out of an open folder onto a Home arc.
 *
 * A Home arc is the drop target, not one particular slot: the app lands in the first free slot on
 * that arc, or stays in the Home slot it already has on that arc. The app leaves the folder; apps
 * may belong to several folders, but they are never placed on Home twice.
 *
 * Pure and framework-free so the ring host and the tests use the same rule.
 */
internal object FolderDrop {
    /** The Home arc with [arcId], or null when the id belongs to a folder, All apps or nothing. */
    fun homeArc(layout: ParcLayout, arcId: String): ArcAssignment? =
        layout.arcs.firstOrNull { it.arc.id == arcId && it.arc.layer == Layer.Home }

    /** The Home slot that already holds [appId], or null when the app is only inside folders. */
    fun homePosition(layout: ParcLayout, appId: AppId): SlotPosition? =
        layout.arcs.asSequence()
            .filter { it.arc.layer == Layer.Home }
            .flatMap { arc ->
                arc.slots.asSequence()
                    .filter { it.value == Slot.App(appId) }
                    .map { SlotPosition(arc.arc.id, it.key) }
            }
            .firstOrNull()

    /**
     * Where an app leaves [arcId] for: its own slot when it is already on that arc, otherwise the
     * first free slot on the arc. Null when the arc is unknown or full.
     */
    fun target(layout: ParcLayout, appId: AppId, arcId: String): SlotPosition? {
        val arc = homeArc(layout, arcId) ?: return null
        val existing = homePosition(layout, appId)
        if (existing != null && existing.arcId == arcId) return existing
        val free = (0 until arc.arc.slotCapacity).firstOrNull { it !in arc.slots } ?: return null
        return SlotPosition(arcId, free)
    }

    /**
     * The layout after [appId] leaves [folderId] and lands on Home arc [arcId], or null when the
     * folder does not hold the app, the arc is not a Home arc, or the arc has no room.
     */
    fun exit(layout: ParcLayout, folderId: FolderId, appId: AppId, arcId: String): ParcLayout? {
        val folder = layout.folders.firstOrNull { it.id == folderId } ?: return null
        if (appId !in folder.appIds) return null
        val target = target(layout, appId, arcId) ?: return null
        val folders = layout.folders.map {
            if (it.id == folderId) it.copy(appIds = it.appIds.filterNot { id -> id == appId }) else it
        }
        val withoutFolder = layout.copy(folders = folders)
        val existing = homePosition(layout, appId)
        if (existing == null) {
            val arcs = withoutFolder.arcs.map { assignment ->
                if (assignment.arc.id == arcId)
                    assignment.copy(slots = assignment.slots + (target.index to Slot.App(appId)))
                else assignment
            }
            return withoutFolder.copy(arcs = arcs)
        }
        if (existing == target) return withoutFolder
        return SlotOrder.move(withoutFolder, existing, target)
    }

    fun canExit(layout: ParcLayout, folderId: FolderId, appId: AppId, arcId: String): Boolean =
        exit(layout, folderId, appId, arcId) != null
}
