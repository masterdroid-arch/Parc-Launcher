package com.brightblu.parc.ring

import com.brightblu.parc.model.*
import kotlin.math.*

/** Pure snapshot builders. Input order is the cold-start ranking; never reorder during a session. */
object RingScenes {
    fun home(layout: RingLayout, slots: List<RingSlot>, clock: String = "",
        rotations: Map<String, Float> = emptyMap()): RingState =
        RingState(layout.arcs, slots, 0f, clock = clock, rotations = rotations)

    /**
     * A1/A2/A3 own ranks 0..3/4..9/10..17; every remaining app becomes an item on A4, whose ring
     * rotates when it holds more than its window (T29 replaces paging with dial rotation).
     */
    fun folder(layout: RingLayout, folder: Folder, ranked: List<RingSlot>,
        rotations: Map<String, Float> = emptyMap()): RingState {
        val radii = floatArrayOf(Metrics.homeRadius1Dp, Metrics.folderRadius2Dp,
            Metrics.folderRadius3Dp, Metrics.folderRadius4Dp)
        val buckets = intArrayOf(4, 6, 8, Int.MAX_VALUE)
        val arcs = layout.arcs.mapIndexed { index, home ->
            val radius = radii[index]
            val far = max(Metrics.keepOutLeftDp,
                if (layout.options.mirrored) layout.insets.right else layout.insets.left) + Metrics.touchTargetDp / 2f
            val width = if (layout.options.mirrored) layout.widthDp - layout.originXDp else layout.originXDp
            val low = acos(((width - far) / radius).coerceIn(0f, 1f))
            val high = asin(((layout.originYDp - max(Metrics.keepOutTopDp, layout.insets.top)) / radius).coerceIn(0f, 1f))
            val capacity = if (high < low) 0 else min(buckets[index], floor((high - low) * radius / Metrics.minSlotPitchDp).toInt() + 1)
            val capped = com.brightblu.parc.model.LayoutCounts(icons = layout.options.iconsPerArc).capacity(capacity)
            fun degrees(a: Float): Float = if (layout.options.mirrored) 360f - Math.toDegrees(a.toDouble()).toFloat()
                else 180f + Math.toDegrees(a.toDouble()).toFloat()
            home.copy(id = "${folder.id.value}/A${index + 1}", radiusDp = radius,
                startAngle = degrees(low), endAngle = degrees(max(low, high)), slotCapacity = capped,
                layer = Layer.Folder, folderId = folder.id,
                slotAngles = List(capped) { degrees(if (capped == 1) (low + high) / 2f else low + it * (high - low) / (capped - 1)) })
        }
        var cursor = 0
        val slots = arcs.flatMapIndexed { i, arc ->
            val remaining = (ranked.size - cursor).coerceAtLeast(0)
            val size = if (i == arcs.lastIndex) remaining else min(arc.slotCapacity, remaining)
            ranked.subList(cursor, cursor + size).mapIndexed { j, slot ->
                slot.copy(position = SlotPosition(arc.id, j))
            }.also { cursor += size }
        }
        return RingState(arcs, slots, 1f, layer = Layer.Folder, title = folder.name, rotations = rotations)
    }

    /**
     * Every visible, non-hidden app on rotating home-radius arcs, ordered A–Z by label (T35).
     * T83: the builder is total. Odd geometry (no arcs, every arc clipped to zero capacity) still
     * produces a usable A–Z scene through one fallback slot per arc instead of throwing, so the
     * host never keeps a stale Home scene while the shell says All-apps.
     */
    /**
     * OPUS1 (the owner, 2026-10-25): the All-apps view is ONE ring that holds every app. It uses the
     * outermost shown arc (the longest, with the most icons visible at once), starts A at its top
     * end and runs A to Z down the arc; turning the ring cycles through the whole list and wraps.
     * The other arcs are not shown in this view. [ordered] is already filtered (hidden apps and
     * apps of locked folders are not in it) and sorted.
     */
    fun allApps(layout: RingLayout, ordered: List<RingSlot>, rotations: Map<String, Float> = emptyMap()): RingState {
        if (layout.arcs.isEmpty()) return RingState(emptyList(), emptyList(), 0f, layer = Layer.AllApps)
        val outerIndex = layout.arcs.lastIndex
        val home = layout.arcs[outerIndex]
        val source = if (home.slotCapacity <= 0) {
            // One slot at the middle of the sweep keeps the ring drawable without new geometry.
            val angle = (home.startAngle + home.endAngle) / 2f
            home.copy(slotCapacity = 1, slotAngles = listOf(angle))
        } else home
        val ring = source.copy(id = allAppsRingId(outerIndex), layer = Layer.AllApps,
            startAngle = source.endAngle, endAngle = source.startAngle,
            slotAngles = source.slotAngles.reversed())
        val slots = ordered.mapIndexed { j, slot -> slot.copy(position = SlotPosition(ring.id, j)) }
        return RingState(listOf(ring), slots, 0f, layer = Layer.AllApps, rotations = rotations,
            emptyMessage = if (slots.isEmpty()) "No apps to show" else "")
    }

    /** Stable id of the All-apps ring, so its turned position is remembered like any arc's. */
    fun allAppsRingId(arcIndex: Int): String = "all/A${arcIndex + 1}"

    fun recents(layout: RingLayout, recent: List<RingSlot>, pins: Set<AppId>): RingState {
        val arcs = layout.arcs.take(2).map { it.copy(id = "R${it.id}", layer = Layer.Recents) }
        val apps = recent.filter { (it.slot as? Slot.App)?.id?.let { id -> id !in pins } == true }
            .distinctBy { (it.slot as Slot.App).id }.iterator()
        val slots = arcs.flatMap { arc -> (0 until arc.slotCapacity).mapNotNull { i ->
            if (apps.hasNext()) apps.next().copy(position = SlotPosition(arc.id, i)) else null
        } }
        return RingState(arcs, slots, 0f, layer = Layer.Recents, title = "Recents",
            emptyMessage = if (slots.isEmpty()) "Nothing yet" else "")
    }
}

/** Eased camera progress, independent from gesture velocity. Pure functions for interruption tests. */
object RingSnap {
    fun endpoint(progress: Float): Boolean = progress >= 0.5f
    fun outgoingAlpha(progress: Float): Float = (1f - progress / Metrics.outgoingFadeEnd).coerceIn(0f, 1f)
    fun incomingAlpha(progress: Float): Float = ((progress - Metrics.incomingFadeStart) / (1f - Metrics.incomingFadeStart)).coerceIn(0f, 1f)
    fun outgoingScale(progress: Float): Float = 1f - (1f - Metrics.collapsedScale) * progress
    fun incomingScale(progress: Float): Float = Metrics.collapsedScale + (1f - Metrics.collapsedScale) * progress
}

/** Warm-up may request slot zero even for an empty scene; stale focus is also harmless. */
internal fun focusedArc(arcIndices: IntArray, focus: Int): Int =
    if (focus in arcIndices.indices) arcIndices[focus] else -1
