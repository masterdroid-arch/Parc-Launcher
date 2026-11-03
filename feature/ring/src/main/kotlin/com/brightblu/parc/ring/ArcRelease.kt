package com.brightblu.parc.ring

import com.brightblu.parc.model.Springs
import kotlin.math.floor

/** DESIGN §3.2 release arithmetic in unwrapped slot coordinates, independent of arc radius. */
object ArcRelease {
    const val CarrySeconds = 0.18f
    const val VelocityCapFactor = 3.5f
    const val SettleStiffness = Springs.settleStiffness

    /** A non-positive limit disables travel; non-finite velocity means no fling. */
    fun clampVelocity(v: Float, limit: Int): Float {
        val cap = limit.coerceAtLeast(0) * VelocityCapFactor
        return if (v.isFinite()) v.coerceIn(-cap, cap) else 0f
    }

    /** Round half toward positive infinity, then bound total travel from touch-down. */
    fun target(startSlot: Int, offset: Float, velocitySlotsPerSec: Float, limit: Int): Int {
        val travel = limit.coerceAtLeast(0).toLong()
        // Widen before arithmetic so extreme finite offsets and Int endpoints cannot overflow.
        val position = if (offset.isFinite()) offset.toDouble() else startSlot.toDouble()
        val rounded = floor(position + clampVelocity(velocitySlotsPerSec, limit) * CarrySeconds + 0.5)
        val lower = (startSlot.toLong() - travel).coerceAtLeast(Int.MIN_VALUE.toLong())
        val upper = (startSlot.toLong() + travel).coerceAtMost(Int.MAX_VALUE.toLong())
        return rounded.coerceIn(lower.toDouble(), upper.toDouble()).toInt()
    }
}
