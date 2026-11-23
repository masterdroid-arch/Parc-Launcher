package com.brightblu.parc

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotOrder
import com.brightblu.parc.model.SlotPosition
import com.brightblu.parc.ring.RingRotation

/**
 * T312: the per-arc "Maximum apps" setting. A value of [Auto] (or a missing key) means the arc keeps
 * as many items as the screen shows today; a stored number is the arc's capacity. The setting lives in
 * the app-local `parc_ui` store and travels in the backup under one text key.
 */
internal object ArcMaximums {
    const val Auto = 0
    const val Max = 24
    const val Key = "arc_maximums"

    /** `A2=12;A4=8`; Auto entries are omitted so a missing key and an all-Auto map encode the same. */
    fun encode(map: Map<String, Int>): String =
        map.entries.filter { it.value in 1..Max }.sortedBy { it.key }
            .joinToString(";") { "${it.key}=${it.value}" }

    fun decode(source: String): Map<String, Int> {
        if (source.isBlank()) return emptyMap()
        val out = HashMap<String, Int>()
        for (part in source.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim().toIntOrNull() ?: continue
            if (key.isBlank() || value !in 1..Max) continue
            out[key] = value
        }
        return out
    }
}

/** One arc row of the shared chooser. [capacity] is the effective ceiling (Auto = visible slots). */
internal data class ArcChoice(
    val arcId: String,
    val index: Int,
    val name: String,
    val occupied: Int,
    val capacity: Int,
) {
    val full: Boolean get() = occupied >= capacity
    val over: Int get() = (occupied - capacity).coerceAtLeast(0)
    val label: String get() = if (full) "$name \u00B7 Full" else "$name \u00B7 $occupied of $capacity"
}

/** The layout after placing an app on a Home arc, and the stored index it landed on. */
internal data class ArcPlacement(val layout: ParcLayout, val index: Int)

/**
 * T312: the one shared "Add to arc" rule. It lists the shown Home arcs from the hub outward with a
 * "how full" count, and places an app in the arc's first free spot with the same write that moves an
 * existing copy (never a duplicate). Occupied = saved apps, folders, links, fixed controls and the
 * displayed New folder, using the same rule Home draws. Pure Kotlin, no Android or Compose types.
 */
internal object ArcChooser {
    /** Arc name for the chooser and Settings, from the hub outward. */
    fun arcName(index: Int, count: Int): String = when {
        count <= 0 -> "Arc ${index + 1}"
        index == 0 -> "Inner arc"
        index >= count - 1 -> "Outer arc"
        else -> "Arc ${index + 1}"
    }

    /** The arc's capacity: Auto means the visible slots; a number is the stored maximum (1..24). */
    fun capacity(visibleSlots: Int, maximum: Int): Int =
        if (maximum <= ArcMaximums.Auto) visibleSlots.coerceAtLeast(0)
        else maximum.coerceIn(1, ArcMaximums.Max)

    /** The Home slot the New folder is drawn on (the outer arc's first vacant visible slot), or null. */
    fun newFolderSpot(layout: ParcLayout, outerArcId: String, newFolderHidden: Boolean): SlotPosition? {
        val inbox = layout.folders.firstOrNull { it.id == FolderId(NewFolderKey) } ?: return null
        if (newFolderHidden || inbox.appIds.isEmpty()) return null
        val placed = layout.arcs.any { a -> a.slots.values.any { it == Slot.Folder(inbox.id) } }
        if (placed) return null
        val outer = layout.arcs.firstOrNull { it.arc.id == outerArcId && it.arc.layer == Layer.Home } ?: return null
        val vacant = (0 until outer.arc.slotCapacity).firstOrNull { it !in outer.slots } ?: return null
        return SlotPosition(outerArcId, vacant)
    }

    /** Stored slot indices plus the New folder's displayed spot when it sits on this arc. */
    fun occupiedIndices(layout: ParcLayout, arcId: String, newFolderSpot: SlotPosition?): Set<Int> {
        val stored = layout.arcs.firstOrNull { it.arc.id == arcId }?.slots?.keys.orEmpty()
        return if (newFolderSpot?.arcId == arcId) stored + newFolderSpot.index else stored
    }

    /** First free stored index in `0 until capacity`, or null when the arc is full. */
    fun firstFreeSpot(layout: ParcLayout, arc: ArcSpec, maximum: Int, newFolderSpot: SlotPosition?): SlotPosition? {
        val cap = capacity(arc.slotCapacity, maximum)
        val occupied = occupiedIndices(layout, arc.id, newFolderSpot)
        if (occupied.size >= cap) return null
        return (0 until cap).firstOrNull { it !in occupied }?.let { SlotPosition(arc.id, it) }
    }

    /** One row per shown Home arc, from the hub outward, with its occupied and capacity counts. */
    fun choices(layout: ParcLayout, arcs: List<ArcSpec>, maximums: Map<String, Int>,
        newFolderSpot: SlotPosition?): List<ArcChoice> {
        val home = arcs.filter { it.layer == Layer.Home }.sortedBy { it.index }
        return home.map { arc ->
            val occupied = occupiedIndices(layout, arc.id, newFolderSpot).size
            ArcChoice(arc.id, arc.index, arcName(arc.index, home.size), occupied,
                capacity(arc.slotCapacity, maximums[arc.id] ?: ArcMaximums.Auto))
        }
    }

    /**
     * The layout after placing [appId] on Home arc [arcId] (never a duplicate), or null when the arc
     * is unknown or full. An app already on that arc stays put; on another arc it moves. A new app is
     * placed and pinned, matching the old search write.
     */
    fun placement(layout: ParcLayout, appId: AppId, arcId: String, maximum: Int,
        newFolderSpot: SlotPosition?): ArcPlacement? {
        val assignment = layout.arcs.firstOrNull { it.arc.id == arcId && it.arc.layer == Layer.Home } ?: return null
        val arc = assignment.arc
        val existing = FolderDrop.homePosition(layout, appId)
        if (existing != null && existing.arcId == arcId) return ArcPlacement(layout, existing.index)
        val spot = firstFreeSpot(layout, arc, maximum, newFolderSpot) ?: return null
        val next = if (existing == null) {
            val arcs = layout.arcs.map { a ->
                if (a.arc.id == arcId) a.copy(slots = a.slots + (spot.index to Slot.App(appId))) else a
            }
            layout.copy(arcs = arcs, pins = layout.pins + appId)
        } else {
            SlotOrder.move(layout, existing, spot) ?: return null
        }
        return ArcPlacement(next, spot.index)
    }

    /**
     * The rotation offset that brings the item at stored index [storedIndex] into view, or null when
     * the arc does not rotate (its items all fit the visible window). [occupied] are the arc's stored
     * indices after the placement; the dense rank selects the item to reveal.
     */
    fun revealOffset(storedIndex: Int, occupied: Collection<Int>, visibleCapacity: Int,
        currentOffset: Float): Float? {
        val count = occupied.size
        if (!RingRotation.rotates(count, visibleCapacity)) return null
        val rank = occupied.count { it < storedIndex }
        return RingRotation.nearestOffsetFor(rank, currentOffset, count)
    }

    fun fullMessage(name: String): String = "$name is full. Choose another arc or raise its maximum in Settings."
    const val AllFullMessage = "All arcs are full. Remove an app or raise a maximum in Settings."
    fun addedMessage(name: String): String = "Added to $name"

    /**
     * T376: the shared sheet's subtitle names how many arcs are full, so the full state is never hidden
     * behind the rows. Empty when no arc is full, and when every arc is full (that case leads with
     * [AllFullMessage] instead).
     */
    fun fullSubtitle(choices: List<ArcChoice>): String {
        val full = choices.count { it.full }
        return if (full > 0 && full < choices.size) "$full of ${choices.size} arcs full" else ""
    }
}
