package com.brightblu.parc.ring

import com.brightblu.parc.model.SlotPosition

/** Optional editing adapter for the Canvas implementation; the frozen renderer contract is unchanged. */
interface RingEditing {
    val enabled: Boolean
    fun empty(position: SlotPosition) {}
    fun enter()
    fun canDrop(from: SlotPosition, to: SlotPosition): Boolean
    fun drop(from: SlotPosition, to: SlotPosition)
}
