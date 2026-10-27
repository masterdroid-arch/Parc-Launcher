package com.brightblu.parc.ring

import com.brightblu.parc.model.Metrics

/** Ring-only presentation tokens; the frozen model stays unchanged. */
internal object RingMetrics {
    const val hubRaiseDp = 24f
    const val edgeHoldDepthDp = 18f
    const val edgeHoldAngleDeg = 12f
    const val edgeHoldDwellMs = 300L
    const val edgeHoldStepMs = 450L
}

/**
 * One hub pointer stream. Leaving the dead zone permanently disarms its long press.
 *
 * All timestamps come from the same monotonic source (the renderer passes
 * `SystemClock.uptimeMillis()`), so the tap deadline and the long-press timer cannot disagree.
 * Public because the host-level tap chain test drives this state machine directly.
 */
class HubGesture {
    var aiming = false
        private set
    var active = false
        private set
    private var started = 0L
    private var held = false

    fun down(onHub: Boolean, now: Long) {
        active = onHub; started = now; aiming = false; held = false
    }

    fun move(reachDp: Float) {
        if (active && !held && reachDp >= Metrics.aimDeadZoneDp) aiming = true
    }

    fun longPress(now: Long): Boolean {
        if (!active || aiming || held || now - started < Metrics.longPressMs) return false
        held = true
        return true
    }

    // travelDp is accumulated path length from DOWN, not just endpoint displacement.
    // A release earlier than DOWN (clock skew from mixed sources) still counts as instant.
    fun tap(reachDp: Float, now: Long, travelDp: Float = Float.POSITIVE_INFINITY): Boolean {
        val age = (now - started).coerceAtLeast(0L)
        return active && !held && age < Metrics.longPressMs &&
            (reachDp <= Metrics.aimDeadZoneDp || travelDp < Metrics.hubTapTravelDp)
    }

    fun release(reachDp: Float, now: Long, aimed: Int,
        travelDp: Float = Float.POSITIVE_INFINITY): Int {
        move(reachDp)
        val result = when {
            tap(reachDp, now, travelDp) -> TAP
            active && !held && aiming && reachDp > Metrics.aimDeadZoneDp -> aimed
            else -> -1
        }
        cancel()
        return result
    }

    fun cancel() { active = false; aiming = false }

    companion object { const val TAP = -3 }
}

/** Fake-clock friendly dwell/repeat state. Zone identity includes both the arc and direction. */
internal class EdgeHold {
    private var zone = 0
    private var next = 0L

    fun reset() { zone = 0 }

    fun step(candidate: Int, now: Long): Int {
        if (candidate == 0) { reset(); return 0 }
        if (candidate != zone) {
            zone = candidate
            next = now + RingMetrics.edgeHoldDwellMs
        }
        if (now < next) return 0
        next = now + RingMetrics.edgeHoldStepMs
        return if (zone > 0) 1 else -1
    }

    companion object {
        /** Signed (arc index + 1), zero outside a rotating arc's outer reach/end zone. */
        fun zone(arc: Int, arcCount: Int, reach: Float, angle: Float,
            first: Float, last: Float, overflowing: Boolean): Int {
            if (!overflowing || RingAim.band(arcCount, reach) != arc) return 0
            val width = (Metrics.aimBandEndDp - Metrics.aimBandStartDp) / arcCount
            val end = Metrics.aimBandStartDp + (arc + 1) * width
            if (reach < end - RingMetrics.edgeHoldDepthDp || reach > end) return 0
            val direction = if (last >= first) 1f else -1f
            val delta = (RingAim.normalizeDegrees(angle) - RingAim.normalizeDegrees(first) + 540f) % 360f - 180f
            val along = delta * direction
            val sweep = (last - first) * direction
            return when {
                along < -RingMetrics.edgeHoldAngleDeg -> -(arc + 1)
                along > sweep + RingMetrics.edgeHoldAngleDeg -> arc + 1
                else -> 0
            }
        }
    }
}
