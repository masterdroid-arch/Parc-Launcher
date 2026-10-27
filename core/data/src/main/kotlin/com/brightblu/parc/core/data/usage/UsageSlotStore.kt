package com.brightblu.parc.core.data.usage

import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.model.SlotPosition
import kotlinx.coroutines.flow.first

/**
 * Persisted state of the usage layer (T34).
 *
 * [slots] is the list of home positions that make up the layer, in reach order; it is stored so the
 * layer can be resized or moved to different arcs without a code change. [lastRankDay] is the 04:00
 * day key of the last re-rank, or null when none has run.
 *
 * Immutable, non-null. Contains positions and a day number, never app names.
 */
internal data class UsageSlotState(
    val slots: List<SlotPosition> = DefaultUsageSlots,
    val lastRankDay: Long? = null,
)

/** A2 slots 0 to 3: the learned slots of the v2 seed, nearest the thumb first. */
internal val DefaultUsageSlots: List<SlotPosition> = listOf(
    SlotPosition("A2", 0),
    SlotPosition("A2", 1),
    SlotPosition("A2", 2),
    SlotPosition("A2", 3),
)

/**
 * Storage boundary for [UsageSlotState]; the production implementation is the app's DataStore, so a
 * test can substitute memory.
 */
internal interface UsageSlotStore {
    suspend fun read(): UsageSlotState

    suspend fun write(state: UsageSlotState)
}

/**
 * DataStore-backed [UsageSlotStore] over [ParcPrefs].
 *
 * The slot list is one string, `A2:0,A2:1,A2:2,A2:3`, under the `usageSlots` key the card names. A
 * missing, blank or unreadable value reads as [DefaultUsageSlots]: the layer must always have slots,
 * and a pref nobody can parse is not worth guessing at.
 */
internal class PrefsUsageSlotStore(private val prefs: ParcPrefs) : UsageSlotStore {

    override suspend fun read(): UsageSlotState = UsageSlotState(
        slots = decodeUsageSlots(prefs.usageSlots.first()),
        lastRankDay = prefs.usageRankDay.first(),
    )

    override suspend fun write(state: UsageSlotState) {
        prefs.setUsageSlots(encodeUsageSlots(state.slots))
        state.lastRankDay?.let { prefs.setUsageRankDay(it) }
    }
}

/** `A2:0,A2:1` from a slot list; the inverse of [decodeUsageSlots]. */
internal fun encodeUsageSlots(slots: List<SlotPosition>): String =
    slots.joinToString(",") { "${it.arcId}:${it.index}" }

/**
 * Parses a stored slot list. A missing, blank, malformed or duplicated entry means the whole value is
 * not trusted and [DefaultUsageSlots] comes back instead, rather than a layer that silently shrank.
 * Position validity against the real arcs is the caller's check, because it depends on the geometry.
 */
internal fun decodeUsageSlots(value: String?): List<SlotPosition> {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return DefaultUsageSlots
    val positions = ArrayList<SlotPosition>(DefaultUsageSlots.size)
    for (part in text.split(',')) {
        val trimmed = part.trim()
        val separator = trimmed.lastIndexOf(':')
        if (separator <= 0) return DefaultUsageSlots
        val arcId = trimmed.substring(0, separator)
        val index = trimmed.substring(separator + 1).toIntOrNull() ?: return DefaultUsageSlots
        if (index < 0 || arcId.isBlank()) return DefaultUsageSlots
        val position = SlotPosition(arcId, index)
        if (position in positions) return DefaultUsageSlots
        positions += position
    }
    return positions.ifEmpty { DefaultUsageSlots }
}
