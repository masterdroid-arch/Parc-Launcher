package com.brightblu.parc

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotPosition

/**
 * T246: the one-time prompt for an app installed after this version was first used. It is offered
 * only while the app is still in New and has not been placed on an arc by another path. Pure, so the
 * "once" and "only a new app" rules can be tested without Android.
 */
internal object NewAppPrompt {
    /** The newest unseen install that is waiting in New, or null. */
    fun candidate(
        installed: List<AppEntry>,
        newFolderIds: Set<AppId>,
        seen: Set<String>,
        since: Long,
        hidden: Set<AppId> = emptySet(),
        placed: Set<AppId> = emptySet(),
    ): AppEntry? = installed.asSequence()
        .filter { !it.isSystem }
        .filter { it.id in newFolderIds && it.id !in hidden && it.id !in placed }
        .filter { it.id.value !in seen }
        .filter { it.installTime >= since }
        .maxWithOrNull(compareBy<AppEntry> { it.installTime }.thenBy { it.id.value })

    /** The quiet one-line question; the app label is the only value in it. */
    fun question(label: String): String = "Add $label to an arc?"
}

/**
 * T246: removes one app from one Home slot and from the pins, leaving every other slot at its stored
 * index. A missing slot, another app, or a folder arc returns null and writes nothing.
 */
internal object ArcSlotRemoval {
    fun remove(layout: ParcLayout, position: SlotPosition, appId: AppId): ParcLayout? {
        val assignment = layout.arcs.firstOrNull { it.arc.id == position.arcId } ?: return null
        if (assignment.arc.layer != Layer.Home) return null
        if (assignment.slots[position.index] != Slot.App(appId)) return null
        return layout.copy(
            arcs = layout.arcs.map { current ->
                if (current.arc.id != assignment.arc.id) current
                else current.copy(slots = current.slots - position.index)
            },
            pins = layout.pins - appId,
        )
    }
}
