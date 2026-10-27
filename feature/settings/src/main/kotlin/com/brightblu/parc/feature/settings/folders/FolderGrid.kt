package com.brightblu.parc.feature.settings.folders

import com.brightblu.parc.model.AppId

/**
 * Contents of one folder as an ordered grid of ring slots (T11).
 *
 * The folder level packs its apps into arcs by rank bucket (DESIGN.md §4), so a folder has no holes:
 * slot `i` holds `appIds[i]` while `i < appIds.size`, every later slot is empty, and the last arc may
 * page (the ring shows a further page instead of overflowing). [appIds] may therefore be longer than
 * [capacity]; the editor only ever adds an app while a slot is free.
 *
 * Immutable, non-null, no Android types. [arcCapacities] is injected by the host from the live ring
 * geometry (`RingScenes.folder`), never guessed here.
 */
data class FolderGrid(
    val arcCapacities: List<Int>,
    val appIds: List<AppId>,
) {
    init {
        require(arcCapacities.isNotEmpty()) { "A folder grid needs at least one arc" }
        require(arcCapacities.all { it >= 0 }) { "Arc capacities cannot be negative" }
        require(appIds.distinct().size == appIds.size) { "A folder cannot list an app twice" }
    }

    /** Slots across all arcs. Zero when the folder level has no room on this screen. */
    val capacity: Int get() = arcCapacities.sum()

    /** Apps past this point page onto the last arc; the editor has no free slot to offer. */
    val hasFreeSlot: Boolean get() = appIds.size < capacity

    fun contains(id: AppId): Boolean = id in appIds

    /** Arc index of [slot], or -1 when the slot is outside the grid. */
    fun arcIndexOf(slot: Int): Int {
        if (slot < 0 || slot >= capacity) return -1
        var offset = 0
        arcCapacities.forEachIndexed { index, size ->
            if (slot < offset + size) return index
            offset += size
        }
        return -1
    }

    /** Payload of [slot], or null when the slot is empty or outside the grid. */
    fun appAt(slot: Int): AppId? = appIds.getOrNull(slot)

    fun isEmptySlot(slot: Int): Boolean = slot >= 0 && slot < capacity && slot >= appIds.size

    /** First slot without an app, or -1 when every slot is taken. */
    fun firstFreeSlot(): Int = if (hasFreeSlot) appIds.size else -1

    /** Appended copy. A full grid or an app already in the folder returns the same grid. */
    fun appended(id: AppId): FolderGrid =
        if (!hasFreeSlot || contains(id)) this else copy(appIds = appIds + id)

    fun removed(id: AppId): FolderGrid = copy(appIds = appIds.filterNot { it == id })

    /** Same apps in a new order. Throws when the order is not a permutation of the current one. */
    fun reordered(ids: List<AppId>): FolderGrid {
        require(ids.sortedBy { it.value } == appIds.sortedBy { it.value }) {
            "A reorder must keep exactly the same apps"
        }
        return copy(appIds = ids)
    }
}
