package com.brightblu.parc.ring

import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Metrics
import kotlin.math.*

/**
 * Pure dial-rotation maths (T29, cyclic window T37). An arc is a ring of `count` items shown
 * through a window of `capacity` visible slot positions. `offset` (slot units, any real) maps item
 * `i` to the continuous window position `i - offset`, folded cyclically: a settled integer offset
 * places items `offset..offset + capacity - 1` (mod `count`) onto the fixed slot angles, and every
 * slot holds exactly one item while `count ≥ capacity`. No Android types, no allocation in the hot
 * path; all angles are degrees in the screen frame (180 = left, 270 = up), matching [ArcSpec].
 */
object RingRotation {
    /** An arc only rotates when it holds more items than it can show at once. */
    fun rotates(count: Int, capacity: Int): Boolean = capacity > 0 && count > capacity

    /** Canonical offset in `[0, count)`. A non-positive count yields 0. */
    fun wrap(offset: Float, count: Int): Float {
        if (count <= 0 || !offset.isFinite()) return 0f
        val m = offset % count
        return if (m < 0f) m + count else m
    }

    /**
     * OPUS4: the offset that brings item [itemIndex] to the front (window slot 0, where the ring
     * starts its list) with the shortest turn from [current]. Offsets stay unwrapped, so the ring
     * turns the short way round and keeps wrapping normally afterwards.
     */
    fun nearestOffsetFor(itemIndex: Int, current: Float, count: Int): Float {
        if (count <= 0) return 0f
        val base = round(if (current.isFinite()) current else 0f)
        var delta = Math.floorMod(itemIndex - base.toInt(), count)
        if (delta > count / 2) delta -= count
        return base + delta
    }

    /** OPUS4: the item at the front (window slot 0) for an offset. */
    fun frontItem(offset: Float, count: Int): Int =
        if (count <= 0) -1 else Math.floorMod(round(if (offset.isFinite()) offset else 0f).toInt(), count)

    /** The nearest integer slot offset. */
    fun snap(offset: Float): Float = round(offset)

    /**
     * Continuous window position of an item under the current offset. A non-rotating arc (count ≤
     * capacity) is the identity: the stored index is the window slot. A rotating arc folds
     * cyclically into `[0, count)`, the canonical ring residue, so the fold is consistent for any
     * offset and for an even `count`, and reports the single position sitting just below the window
     * bottom as a negative value, so an item slides out of the bottom before the ring wraps it back
     * to the top (T37).
     */
    fun windowPosition(itemIndex: Int, offset: Float, count: Int, capacity: Int): Float {
        if (count <= capacity) return itemIndex.toFloat()
        val period = count.toFloat()
        val p = itemIndex - offset
        val folded = p - floor(p / period) * period
        return if (folded > period - 1f) folded - period else folded
    }

    /** Per-item visibility factor in `[0,1]`: 1 inside the window, fading over the slot either end. */
    fun edgeAlpha(p: Float, capacity: Int): Float {
        if (capacity <= 0) return 0f
        return when {
            p < -1f -> 0f
            p < 0f -> 1f + p
            p <= (capacity - 1) -> 1f
            p <= capacity.toFloat() -> capacity - p
            else -> 0f
        }
    }

    /** Per-item scale: full inside the window, shrinking to [Metrics.rotateEdgeScaleMin] at the ends. */
    fun edgeScale(p: Float, capacity: Int): Float =
        Metrics.rotateEdgeScaleMin + (1f - Metrics.rotateEdgeScaleMin) * edgeAlpha(p, capacity)

    /** Signed angular pitch between adjacent window slots, from the arc's stored slot angles. */
    fun pitchDeg(arc: ArcSpec): Float =
        if (arc.slotCapacity > 1) arc.slotAngle(1) - arc.slotAngle(0) else 0f

    /**
     * The screen-frame angle (degrees) of the window slot that item `itemIndex` occupies (T37).
     * The item is placed at its folded window position, so while `count ≥ capacity` every slot of
     * the arc holds exactly one item and no item is ever drawn outside the arc as the ring
     * rotates. A non-rotating arc keeps the stored window index.
     */
    fun windowAngleDeg(arc: ArcSpec, itemIndex: Int, offset: Float, count: Int): Float {
        val capacity = arc.slotCapacity
        if (capacity <= 0) return 0f
        return windowAngleDeg(arc.slotAngle(0), pitchDeg(arc), itemIndex, offset, count, capacity)
    }

    /**
     * Allocation-free form for callers holding the cached slot-0 angle and pitch (the renderer's
     * draw path); [windowAngleDeg] delegates here so the tested rule is the drawn rule.
     */
    fun windowAngleDeg(angle0Deg: Float, pitchDeg: Float, itemIndex: Int, offset: Float,
        count: Int, capacity: Int): Float =
        if (capacity <= 1) angle0Deg
        else angle0Deg + windowPosition(itemIndex, offset, count, capacity) * pitchDeg

    /** Centre (dx, dy in dp relative to the origin) of `itemIndex` inside its window slot. */
    fun slotPoint(arc: ArcSpec, itemIndex: Int, offset: Float, count: Int): Pair<Float, Float> {
        val angle = Math.toRadians(windowAngleDeg(arc, itemIndex, offset, count).toDouble())
        return cos(angle).toFloat() * arc.radiusDp to sin(angle).toFloat() * arc.radiusDp
    }

    /** Item indices occupying the window slots for a settled integer offset, cyclic when wrapped. */
    fun visibleRange(offset: Int, count: Int, capacity: Int): List<Int> {
        if (count <= 0 || capacity <= 0) return emptyList()
        if (count <= capacity) return (0 until count).toList()
        val o = Math.floorMod(offset, count)
        return (0 until capacity).map { Math.floorMod(o + it, count) }
    }

    /** A spring has landed when both the offset error and velocity are inside their tolerances. */
    fun isSettled(offset: Float, velocity: Float, target: Float): Boolean =
        abs(offset - target) <= Metrics.rotateSettleEps && abs(velocity) <= Metrics.rotateVelocityMin

    /**
     * Polar hit-test over rotated positions. `arcIndexOf`/`itemIndexOf` are flat per-slot arrays
     * aligned to the scene slots, `offsets` and `counts` are aligned to `arcs`. Returns the flat
     * slot index whose rotated centre is nearest within the touch target, or -1. Mirrors the
     * renderer's pixel path, including the T37 folded window position.
     */
    fun nearest(xDp: Float, yDp: Float, arcs: List<ArcSpec>, arcIndexOf: IntArray,
        itemIndexOf: IntArray, offsets: FloatArray, counts: IntArray): Int {
        if (!xDp.isFinite() || !yDp.isFinite() || arcIndexOf.isEmpty()) return -1
        val ox = arcs[0].originXDp
        val oy = arcs[0].originYDp
        val radius = hypot(xDp - ox, yDp - oy)
        val target = Metrics.touchTargetDp / 2f
        var best = -1
        var bestDistance = target * target
        for (i in arcIndexOf.indices) {
            val a = arcIndexOf[i]
            val arc = arcs[a]
            if (abs(radius - arc.radiusDp) > target) continue
            val (rx, ry) = slotPoint(arc, itemIndexOf[i], offsets[a], counts[a])
            val dx = xDp - ox - rx
            val dy = yDp - oy - ry
            val candidate = dx * dx + dy * dy
            if (candidate <= bestDistance) { best = i; bestDistance = candidate }
        }
        return best
    }
}

/** Dense presentation order for overflowing sparse assignments; persisted positions remain intact. */
internal fun ringItemIndex(slot: com.brightblu.parc.model.RingSlot,
    slots: List<com.brightblu.parc.model.RingSlot>, capacity: Int): Int {
    val count = slots.count { it.position.arcId == slot.position.arcId }
    return if (RingRotation.rotates(count, capacity)) slots.count {
        it.position.arcId == slot.position.arcId && it.position.index < slot.position.index
    } else slot.position.index
}
