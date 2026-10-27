package com.brightblu.parc.ring

import com.brightblu.parc.model.ArcSpec
import com.brightblu.parc.model.Metrics
import kotlin.math.*

/**
 * Cached geometry for the circular search button (T35). It sits bottom-left, in the hub's style,
 * and is lifted just enough that its 44 dp touch target clears every slot's touch target. The
 * clear-of-slots search replaces the T09 draw only; the maths is pure and unit-tested.
 */
internal class RingSearchButtonPlacement(arcs: List<ArcSpec>) {
    /** Left edge of the 44 dp touch box, in dp. */
    val left = Metrics.searchButtonLeftDp

    /** Touch radius in dp (22). */
    val radius = Metrics.searchButtonTouchDp / 2f

    /** Touch-centre x in dp. */
    val centerX = left + radius

    /** Touch-centre y in dp, as low as it can go while staying clear of every slot target. */
    val centerY: Float

    init {
        var y = (arcs.firstOrNull()?.originYDp ?: 0f) + Metrics.keepOutBottomDp - Metrics.searchButtonBottomOffsetDp
        val clearance = radius + Metrics.touchTargetDp / 2f
        fun clear(): Boolean {
            for (arc in arcs) for (i in 0 until arc.slotCapacity) {
                val angle = Math.toRadians(arc.slotAngle(i).toDouble())
                val sx = arc.originXDp + cos(angle).toFloat() * arc.radiusDp
                val sy = arc.originYDp + sin(angle).toFloat() * arc.radiusDp
                val dx = sx - centerX
                val dy = sy - y
                if (dx * dx + dy * dy < clearance * clearance) return false
            }
            return true
        }
        while (y > Metrics.keepOutTopDp + radius && !clear()) y -= Metrics.gridDp
        centerY = y
    }
}

/** OPUS4: top edge (dp) of the search button's touch box for these arcs, for layouts that must keep clear of it. */
fun searchButtonTopDp(arcs: List<com.brightblu.parc.model.ArcSpec>): Float =
    RingSearchButtonPlacement(arcs).let { it.centerY - it.radius }
