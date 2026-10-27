package com.brightblu.parc.model

/** Persisted counts are independent of device geometry. Zero means automatic capacity. */
data class LayoutCounts(val arcs: Int = 4, val icons: Int = 0) {
    fun normalized() = LayoutCounts(if (arcs == 3) 3 else 4, if (icons == 0) 0 else icons.coerceAtLeast(3))
    fun capacity(geometric: Int): Int = if (icons == 0) geometric else minOf(icons.coerceAtLeast(3), geometric)
    companion object {
        /** Move the removed outer ring to the next remaining ring, keeping holes and all payloads. */
        fun migrate(layout: ParcLayout, count: Int): ParcLayout {
            if (count != 3) return layout
            val outer = layout.arcs.firstOrNull { it.arc.id == "A4" } ?: return layout
            val target = layout.arcs.firstOrNull { it.arc.id == "A3" } ?: return layout
            if (outer.slots.isEmpty()) return layout
            val merged = target.slots.toMutableMap()
            var cursor = (merged.keys.maxOrNull() ?: -1) + 1
            outer.slots.toSortedMap().values.forEach { merged[cursor++] = it }
            return layout.copy(arcs = layout.arcs.map { when (it.arc.id) {
                "A3" -> it.copy(slots = merged.toMap())
                "A4" -> it.copy(slots = emptyMap())
                else -> it
            } })
        }
    }
}
