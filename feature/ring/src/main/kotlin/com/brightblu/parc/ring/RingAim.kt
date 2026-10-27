package com.brightblu.parc.ring

import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Metrics
import kotlin.math.*

/**
 * Pure hub-joystick aiming maths, shared by [RingLayout.aim] and the canvas renderer.
 * All angles are degrees in the raw screen frame (atan2, 180 = left, 270 = up); reach is
 * the finger's distance from the hub centre in dp. No allocation, no Android types.
 */
internal object RingAim {
    const val BACK = -2

    fun isBack(dx: Float, dy: Float): Boolean =
        dx.isFinite() && dy.isFinite() && hypot(dx, dy) >= Metrics.aimDeadZoneDp && (dx > 0f || dy > 0f)

    /** Distance from the direction projected onto the arc to an actual slot centre. */
    fun distance(angle: Float, slotAngle: Float, radius: Float): Float {
        val a = Math.toRadians(angle.toDouble())
        val b = Math.toRadians(slotAngle.toDouble())
        return hypot((cos(a) - cos(b)) * radius, (sin(a) - sin(b)) * radius).toFloat()
    }

    /**
     * Arc band (0 = A1, the innermost) for a reach distance, or -1 inside the dead zone.
     * The reach window [aimBandStartDp, aimBandEndDp] is split into `arcCount` equal bands.
     */
    fun band(arcCount: Int, reachDp: Float): Int {
        if (arcCount <= 0 || !reachDp.isFinite() || reachDp < Metrics.aimDeadZoneDp) return -1
        val width = (Metrics.aimBandEndDp - Metrics.aimBandStartDp) / arcCount
        return ((reachDp - Metrics.aimBandStartDp) / width).toInt().coerceIn(0, arcCount - 1)
    }

    /** Angle normalised to [0, 360). */
    fun normalizeDegrees(angle: Float): Float {
        var a = angle % 360f
        if (a < 0f) a += 360f
        return a
    }

    /** Minimal circular angular distance in degrees, in [0, 180]. */
    fun circularDistance(a: Float, b: Float): Float {
        val d = abs(normalizeDegrees(a) - normalizeDegrees(b))
        return if (d > 180f) 360f - d else d
    }

    /** Angular pitch between adjacent slots, or +Infinity with fewer than two slots. */
    fun pitchDegrees(arc: ArcSpec): Float =
        if (arc.slotCapacity <= 1) Float.POSITIVE_INFINITY
        else circularDistance(arc.slotAngle(0), arc.slotAngle(1))
}
