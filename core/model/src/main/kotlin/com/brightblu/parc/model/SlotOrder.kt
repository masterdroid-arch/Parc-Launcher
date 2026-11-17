package com.brightblu.parc.model

/**
 * T243: the pure slot order behind an edit-mode drag.
 *
 * A drag is a move, not a swap: the dragged slot is taken out and put at [to], and every slot
 * between the two moves one place toward the old position. On one arc that leaves the ring the
 * same length and fills the hole the finger made; across arcs the old arc keeps the hole and the
 * new arc makes room by pushing its later slots one place out. Empty slots move like any other
 * slot, so a drop into a hole simply moves the hole to where the item came from.
 */
object SlotOrder {
    /**
     * The layout after moving the slot at [from] to [to], or null when either arc is unknown, an
     * index is negative, there is nothing at [from], or the two positions are the same.
     *
     * Only the payloads in the two affected arcs change; folders, pins, hidden apps and every
     * other arc are returned unchanged. The caller still owns the validation of what may move.
     */
    fun move(layout: ParcLayout, from: SlotPosition, to: SlotPosition): ParcLayout? {
        if (from == to || from.index < 0 || to.index < 0) return null
        val fromArc = layout.arcs.firstOrNull { it.arc.id == from.arcId } ?: return null
        val toArc = layout.arcs.firstOrNull { it.arc.id == to.arcId } ?: return null
        val moving = fromArc.slots[from.index] ?: return null
        val arcs = if (from.arcId == to.arcId) {
            val original = fromArc.slots
            val next = original.toMutableMap()
            if (original[to.index] == null) {
                // An empty spot takes the app and leaves every neighbour where it is; only the hole
                // moves, so nothing between the two places shifts.
                next.remove(from.index)
            } else if (from.index < to.index) {
                for (i in from.index until to.index) {
                    val shifted = original[i + 1]
                    if (shifted == null) next.remove(i) else next[i] = shifted
                }
            } else {
                for (i in from.index downTo to.index + 1) {
                    val shifted = original[i - 1]
                    if (shifted == null) next.remove(i) else next[i] = shifted
                }
            }
            next[to.index] = moving
            layout.arcs.map { if (it.arc.id == fromArc.arc.id) it.copy(slots = next.toMap()) else it }
        } else {
            val source = fromArc.slots.toMutableMap().apply { remove(from.index) }
            val targetOriginal = toArc.slots
            val target = targetOriginal.toMutableMap()
            // The first hole at or after the drop absorbs the insertion. Repeated cross-arc moves
            // therefore do not push indices out for ever.
            var hole = to.index
            while (target.containsKey(hole)) hole++
            for (i in hole downTo to.index + 1) {
                val shifted = targetOriginal[i - 1]
                if (shifted == null) target.remove(i) else target[i] = shifted
            }
            target[to.index] = moving
            layout.arcs.map {
                when (it.arc.id) {
                    fromArc.arc.id -> it.copy(slots = source.toMap())
                    toArc.arc.id -> it.copy(slots = target.toMap())
                    else -> it
                }
            }
        }
        return layout.copy(arcs = arcs)
    }
}

/**
 * T243: the angular end zone of a rotating arc while an app is dragged near either end. It is the
 * same 12 degree allowance the hub's edge hold uses, expressed without Android or Compose types.
 * Positive means past the last slot, negative before the first; zero means no turn.
 */
object ArcEndTurn {
    const val MarginDegrees = 12f

    fun zone(angleDeg: Float, firstDeg: Float, lastDeg: Float, overflowing: Boolean,
        marginDegrees: Float = MarginDegrees): Int {
        if (!overflowing || !angleDeg.isFinite() || !firstDeg.isFinite() || !lastDeg.isFinite()) return 0
        val direction = if (lastDeg >= firstDeg) 1f else -1f
        val delta = (normalize(angleDeg) - normalize(firstDeg) + 540f) % 360f - 180f
        val along = delta * direction
        val sweep = (lastDeg - firstDeg) * direction
        return when {
            along < -marginDegrees -> -1
            along > sweep + marginDegrees -> 1
            else -> 0
        }
    }

    private fun normalize(angle: Float): Float {
        var value = angle % 360f
        if (value < 0f) value += 360f
        return value
    }
}
