package com.brightblu.parc.ring

import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.SlotPosition
import kotlin.math.*

/** Immutable, non-negative system insets in dp. Safe bounds use max(inset, token), not their sum. */
data class RingInsets(val left: Float = 0f, val top: Float = 0f,
    val right: Float = 0f, val bottom: Float = 0f) {
    init { require(listOf(left, top, right, bottom).all { it.isFinite() && it >= 0f }) }
}

/**
 * Widest edge per side across the sources the window reports (system bars, display cutout,
 * system gestures, mandatory system gestures). Absent sources stay at 0, and [RingLayout]
 * still floors every keep-out at its Metrics token, so a device that reports no gesture
 * insets (3-button navigation) keeps the original geometry.
 */
fun widestInsets(vararg sources: RingInsets): RingInsets {
    if (sources.isEmpty()) return RingInsets()
    return RingInsets(
        left = sources.maxOf { it.left },
        top = sources.maxOf { it.top },
        right = sources.maxOf { it.right },
        bottom = sources.maxOf { it.bottom },
    )
}

/** Immutable layout options. Aligned mode uses A1's pitch on every arc, reducing capacity. */
data class RingLayoutOptions(val mirrored: Boolean = false, val alignAcrossArcs: Boolean = false,
    val fineSpokes: Boolean = false, val arcsShown: Int = 4, val iconsPerArc: Int = 0)

/**
 * Pure Kotlin home geometry. Build on size/inset/option changes, outside drawing.
 * Published arcs are read-only snapshots. Queries return flat slot indices, or -1 if absent,
 * and allocate nothing. All coordinates and query arguments are dp, all angles degrees.
 */
class RingLayout(
    val widthDp: Float,
    val heightDp: Float,
    val insets: RingInsets = RingInsets(),
    val options: RingLayoutOptions = RingLayoutOptions(),
) {
    val originXDp: Float
    val originYDp: Float
    val arcs: List<ArcSpec>
    val count: Int
    private val xs: FloatArray
    private val ys: FloatArray
    private val arcIndices: IntArray
    private val slotIndices: IntArray

    init {
        require(widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f)
        // Mirror the geometry including system insets before solving in the left/up quadrant.
        val nearInset = if (options.mirrored) insets.left else insets.right
        val farInset = if (options.mirrored) insets.right else insets.left
        val near = max(Metrics.keepOutRightDp, nearInset)
        val far = max(Metrics.keepOutLeftDp, farInset) + Metrics.touchTargetDp / 2f
        val top = max(Metrics.keepOutTopDp, insets.top)
        val bottom = max(Metrics.keepOutBottomDp, insets.bottom)
        val canonicalX = widthDp - near
        originXDp = if (options.mirrored) near else canonicalX
        originYDp = heightDp - bottom - RingMetrics.hubRaiseDp
        require(canonicalX >= far && originYDp >= top) { "Screen/insets leave no ring field" }
        val radii = listOf(Metrics.homeRadius1Dp, Metrics.homeRadius2Dp,
            Metrics.homeRadius3Dp, Metrics.homeRadius4Dp)
        val sharedStep = (PI / 2 / floor((PI / 2 * radii.first()) / Metrics.minSlotPitchDp)).toFloat()
        arcs = radii.take(if (options.arcsShown == 3) 3 else 4).mapIndexed { index, radius ->
            val low = acos(((canonicalX - far) / radius).coerceIn(0f, 1f))
            val high = asin(((originYDp - top) / radius).coerceIn(0f, 1f))
            val angles = mutableListOf<Float>()
            if (low <= high) {
                if (options.fineSpokes) {
                    val grid = Math.toRadians(Metrics.fineSpokeDegrees.toDouble()).toFloat()
                    val step = ceil(Metrics.minSlotPitchDp / radius / grid) * grid
                    var angle = ceil(low / grid) * grid
                    while (angle <= high + 0.000001f) { angles.add(angle); angle += step }
                } else if (options.alignAcrossArcs) {
                    for (spoke in 0..floor((PI / 2) / sharedStep + 0.00001).toInt()) {
                        val angle = spoke * sharedStep
                        if (angle >= low && angle <= high + 0.000001f) angles.add(angle)
                    }
                } else {
                    val intervals = floor(((high - low) * radius / Metrics.minSlotPitchDp).toDouble()).toInt()
                    if (intervals == 0) angles.add((low + high) / 2f)
                    else for (slot in 0..intervals) angles.add(low + slot * (high - low) / intervals)
                }
            }
            // Preserve the four-slot floor when the shared spoke grid clips the outer arc.
            if (angles.size < 4 && (high - low) * radius >= 3 * Metrics.minSlotPitchDp) {
                angles.clear()
                for (slot in 0..3) angles.add(low + slot * (high - low) / 3f)
            }
            val cap = com.brightblu.parc.model.LayoutCounts(icons = options.iconsPerArc).capacity(angles.size)
            if (cap < angles.size) {
                angles.clear()
                for (slot in 0 until cap) angles.add(if (cap == 1) (low + high) / 2f else low + slot * (high - low) / (cap - 1))
            }
            fun degrees(angle: Float): Float {
                val value = 180f + Math.toDegrees(angle.toDouble()).toFloat()
                return if (options.mirrored) 540f - value else value
            }
            ArcSpec("A${index + 1}", index, radius, degrees(low), degrees(max(low, high)),
                angles.size, Layer.Home, originXDp = originXDp, originYDp = originYDp,
                slotAngles = angles.map(::degrees))
        }
        count = arcs.sumOf { it.slotCapacity }
        xs = FloatArray(count)
        ys = FloatArray(count)
        arcIndices = IntArray(count)
        slotIndices = IntArray(count)
        var flat = 0
        for (arc in arcs) for (slot in 0 until arc.slotCapacity) {
            val radians = Math.toRadians(arc.slotAngle(slot).toDouble())
            xs[flat] = originXDp + cos(radians).toFloat() * arc.radiusDp
            ys[flat] = originYDp + sin(radians).toFloat() * arc.radiusDp
            arcIndices[flat] = arc.index
            slotIndices[flat++] = slot
        }
    }

    /** Tightest centre-to-centre distance between adjacent slots, in dp; the icon-size clamp uses it. */
    fun minimumPitchDp(): Float {
        var min = Float.POSITIVE_INFINITY
        for (arc in arcs) {
            if (arc.slotCapacity <= 1) continue
            var degrees = abs(arc.slotAngle(1) - arc.slotAngle(0))
            if (degrees > 180f) degrees = 360f - degrees
            min = minOf(min, arc.radiusDp * Math.toRadians(degrees.toDouble()).toFloat())
        }
        return if (min.isFinite()) min else Metrics.minSlotPitchDp
    }

    fun slotXDp(index: Int): Float = xs[index]
    fun slotYDp(index: Int): Float = ys[index]
    fun arcIndex(index: Int): Int = arcIndices[index]
    fun slotIndex(index: Int): Int = slotIndices[index]

    /** Nearest centre by Euclidean distance; ties prefer the earlier arc/slot. */
    fun nearestSlot(xDp: Float, yDp: Float): Int {
        if (!xDp.isFinite() || !yDp.isFinite()) return -1
        var best = -1
        var distance = Float.POSITIVE_INFINITY
        for (i in 0 until count) {
            val dx = xDp - xs[i]
            val dy = yDp - ys[i]
            val candidate = dx * dx + dy * dy
            if (candidate < distance) { best = i; distance = candidate }
        }
        return best
    }

    /** Polar radial-band rejection, followed by an exact circular target check. */
    fun hitTest(xDp: Float, yDp: Float): Int {
        if (!xDp.isFinite() || !yDp.isFinite()) return -1
        val radius = hypot(xDp - originXDp, yDp - originYDp)
        val target = Metrics.touchTargetDp / 2f
        var best = -1
        var distance = Float.POSITIVE_INFINITY
        for (i in 0 until count) {
            if (abs(radius - arcs[arcIndices[i]].radiusDp) > target) continue
            val dx = xDp - xs[i]
            val dy = yDp - ys[i]
            val candidate = dx * dx + dy * dy
            if (candidate <= target * target && candidate < distance) {
                best = i
                distance = candidate
            }
        }
        return best
    }

    /**
     * Aim a hub joystick drag at a slot. `angleDeg` is the raw finger angle (atan2 degrees,
     * 180 = left, 270 = up), `reachDp` is the finger's distance from the hub centre, and
     * `hysteresis` is the previously aimed position (null disables). Reach under the dead
     * zone selects nothing; beyond it the reach window is split into one band per arc, and
     * the angle chooses the nearest slot on that band's arc. When the previous selection is
     * on the same arc and the new angle is within 15 % of a pitch of it, it is retained to
     * suppress flicker. Returns the aimed position or null.
     */
    fun aim(angleDeg: Float, reachDp: Float, hysteresis: SlotPosition? = null): SlotPosition? {
        val band = RingAim.band(arcs.size, reachDp)
        if (band < 0) return null
        val arc = arcs[band]
        if (arc.slotCapacity == 0) return null
        val angle = RingAim.normalizeDegrees(angleDeg)
        var best = -1
        var bestDistance = Float.POSITIVE_INFINITY
        var bestAngle = angle
        for (i in 0 until arc.slotCapacity) {
            val a = RingAim.normalizeDegrees(arc.slotAngle(i))
            val d = RingAim.distance(angle, a, arc.radiusDp)
            if (d < bestDistance) { best = i; bestDistance = d; bestAngle = a }
        }
        if (hysteresis != null && hysteresis.arcId == arc.id && hysteresis.index in 0 until arc.slotCapacity) {
            val previousAngle = RingAim.normalizeDegrees(arc.slotAngle(hysteresis.index))
            val pitch = RingAim.pitchDegrees(arc)
            if (RingAim.circularDistance(angle, previousAngle) <=
                RingAim.circularDistance(angle, bestAngle) + Metrics.aimHysteresisFraction * pitch) {
                best = hysteresis.index
            }
        }
        return if (best < 0) null else SlotPosition(arc.id, best)
    }
}
