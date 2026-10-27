package com.brightblu.parc.core.data.usage

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import com.brightblu.parc.model.SlotPosition

/**
 * The usage layer's decision rules (T34). Framework-free and deterministic: no Android, no
 * coroutines, no clock of its own, so the daily gate and the 25 % margin are unit-tested against a
 * fake clock and a fixed score map.
 *
 * Rules:
 * - Candidates are the installed apps that are neither hard-pinned in the layout nor hidden.
 * - An incumbent (the app in a usage slot) keeps its slot unless a *free* challenger (a candidate
 *   that is not already an incumbent of another usage slot) beats it by more than [ReplaceMargin].
 *   So the layer's own order is stable, and only a newcomer ever displaces anyone.
 * - A slot with no eligible resident (empty, or holding a hidden or uninstalled app) is filled by the
 *   best free candidate regardless of score. A slot holding a hard pin, a folder, an action or a link
 *   is never changed.
 * - The best free candidate takes the first slot it can win, so the strongest newcomer lands as near
 *   the thumb as its score earns.
 */
internal object UsageLayerRanking {

    /** A challenger must beat an incumbent by *more* than this fraction of the incumbent's score. */
    const val ReplaceMargin = 0.25

    /**
     * Re-ranking happens on the first cold start after 04:00 local, so the day key is a local day
     * shifted by four hours. A 03:59 start belongs to the previous day and does not re-rank; a 04:01
     * start does.
     */
    const val DayBoundaryMillis = 4L * 60L * 60L * 1000L

    private const val MillisPerDay = UsageRanking.MillisPerDay

    /** The 04:00 day key of an instant, given the local UTC offset in effect then. */
    fun dayKey(nowMillis: Long, zoneOffsetMillis: Int): Long =
        Math.floorDiv(nowMillis + zoneOffsetMillis - DayBoundaryMillis, MillisPerDay)

    /** True when no re-rank has run on [dayKey] yet. */
    fun isDue(lastRankDay: Long?, dayKey: Long): Boolean = lastRankDay != dayKey

    /**
     * The requested positions that exist in [layout], in the requested order and without repeats.
     * Falls back to [DefaultUsageSlots] when none of them do, and returns empty when not even those
     * exist, which the caller reads as "this geometry has no usage layer".
     */
    fun positionsIn(layout: ParcLayout, requested: List<SlotPosition>): List<SlotPosition> {
        val valid = requested.filter { layout.fits(it) }.distinct()
        if (valid.isNotEmpty()) return valid
        return DefaultUsageSlots.filter { layout.fits(it) }.distinct()
    }

    /**
     * The changes one pass would make. Empty means nothing moves, which is the normal outcome: with
     * no launch history every score is zero and no challenger beats an incumbent.
     */
    fun plan(
        positions: List<SlotPosition>,
        layout: ParcLayout,
        installed: Set<AppId>,
        scores: Map<AppId, Double>,
    ): List<UsageSwap> {
        if (positions.isEmpty()) return emptyList()
        val occupants = positions.map { layout.slotsAt(it) }
        val apps = occupants.map { (it as? Slot.App)?.id }
        val lockedPackages = com.brightblu.parc.core.data.lock.LockedApps.lockedAppIds(layout.folders)
            .map { it.value.substringBefore('/') }.toSet()
        val eligible = installed.filterNot { it.value.substringBefore('/') in lockedPackages }.toSet() - layout.pins - layout.hidden
        val incumbentIds = apps.filterNotNull().toSet()
        val free = eligible.asSequence()
            .filter { it !in incumbentIds }
            .sortedWith(compareByDescending<AppId> { scores[it] ?: 0.0 }.thenBy { it.value })
            .toMutableList()

        val changes = ArrayList<UsageSwap>(positions.size)
        apps.forEachIndexed { index, app ->
            val winner: AppId? = when {
                app != null && app in layout.pins -> app
                app != null && app in eligible -> {
                    val challenger = free.firstOrNull()
                    if (challenger != null && beats(scores, challenger, app)) {
                        free.removeAt(0)
                        challenger
                    } else {
                        app
                    }
                }
                // A folder, action or link in one of these slots is not ours to replace.
                app == null && occupants[index] != null -> null
                // Empty, or an app that is hidden or gone: the best candidate takes it.
                else -> free.firstOrNull()?.also { free.removeAt(0) }
            }
            if (winner != null && winner != app) {
                changes += UsageSwap(positions[index], app, winner)
            }
        }
        return changes
    }

    /** Repair privacy holes without reranking any other learned slot or moving a hard pin. */
    fun repairLockedSlots(layout: ParcLayout, positions: List<SlotPosition>, installed: Set<AppId>, scores: Map<AppId, Double>): ParcLayout {
        val packages = com.brightblu.parc.core.data.lock.LockedApps.lockedAppIds(layout.folders)
            .map { it.value.substringBefore('/') }.toSet()
        val bad = positions.filter { position ->
            val id = (layout.slotsAt(position) as? Slot.App)?.id
            id != null && id !in layout.pins && id.value.substringBefore('/') in packages
        }.toSet()
        if (bad.isEmpty()) return layout
        val winners = plan(positions, layout, installed, scores).filter { it.position in bad }.associate { it.position to it.to }
        return layout.copy(arcs = layout.arcs.map { assignment -> assignment.copy(slots = assignment.slots.mapNotNull { (index, slot) ->
            val position = SlotPosition(assignment.arc.id, index)
            if (position !in bad) index to slot else winners[position]?.let { index to Slot.App(it) }
        }.toMap()) })
    }

    /** Strictly more than [ReplaceMargin] above the incumbent; equal scores never move anything. */
    fun beats(scores: Map<AppId, Double>, challenger: AppId, incumbent: AppId): Boolean =
        (scores[challenger] ?: 0.0) > (scores[incumbent] ?: 0.0) * (1.0 + ReplaceMargin)

    /** The slot payload in a position, or null when the position is empty. */
    private fun ParcLayout.slotsAt(position: SlotPosition): Slot? =
        arcs.firstOrNull { it.arc.id == position.arcId }?.slots?.get(position.index)

    private fun ParcLayout.fits(position: SlotPosition): Boolean =
        position.index >= 0 && arcs.any { it.arc.id == position.arcId && position.index < it.arc.slotCapacity }
}
