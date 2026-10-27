package com.brightblu.parc.ring

import com.brightblu.parc.model.*
import kotlin.math.*

/** Throwaway reference geometry for M0, not the T06 responsive layout engine. */
class SpikeGeometry(widthDp: Float = 384f, heightDp: Float = 853f) {
    val originX = widthDp - Metrics.keepOutRightDp
    val originY = floor(heightDp) - Metrics.keepOutBottomDp
    val arcs = listOf(
        ArcSpec("A1", 0, 120f, 180f, 270f, 4, Layer.Home),
        ArcSpec("A2", 1, 190f, 180f, 270f, 5, Layer.Home),
        ArcSpec("A3", 2, 255f, 180f, 270f, 7, Layer.Home),
        ArcSpec("A4", 3, 315f, 202.1f, 270f, 7, Layer.Home),
    )
    val count = arcs.sumOf { it.slotCapacity }
    val x = FloatArray(count)
    val y = FloatArray(count)
    val arcIndices = IntArray(count)
    val slotIndices = IntArray(count)
    init {
        var index = 0
        for (arc in arcs) {
            for (slot in 0 until arc.slotCapacity) {
                val angle = Math.toRadians(angle(arc, slot).toDouble())
                x[index] = originX + cos(angle).toFloat() * arc.radiusDp
                y[index] = originY + sin(angle).toFloat() * arc.radiusDp
                arcIndices[index] = arc.index
                slotIndices[index] = slot
                index++
            }
        }
    }
    fun angle(arc: ArcSpec, slot: Int): Float =
        arc.startAngle + slot * (arc.endAngle - arc.startAngle) / (arc.slotCapacity - 1)

    /** Polar nearest candidate followed by circular target check. Returns -1 for no hit. */
    fun hit(xDp: Float, yDp: Float): Int {
        val dx = xDp - originX
        val dy = yDp - originY
        val radius = hypot(dx, dy)
        var degrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (degrees < 0f) degrees += 360f
        var offset = 0
        for (arc in arcs) {
            val pitchAngle = (arc.endAngle - arc.startAngle) / (arc.slotCapacity - 1)
            val slot = ((degrees - arc.startAngle) / pitchAngle).roundToInt()
            if (abs(radius - arc.radiusDp) <= Metrics.touchTargetDp / 2f &&
                slot in 0 until arc.slotCapacity) {
                val candidate = offset + slot
                if (hypot(xDp - x[candidate], yDp - y[candidate]) <= Metrics.touchTargetDp / 2f) {
                    return candidate
                }
            }
            offset += arc.slotCapacity
        }
        return -1
    }
    fun hubHit(x: Float, y: Float): Boolean =
        hypot(x - originX, y - originY) <= Metrics.hubTouchDp / 2f
}

/** Debug workload/control constants, deliberately separate from production layout tokens. */
object SpikeConfig {
    const val bitmapPx = 112
    const val loopSnaps = 10
    const val gapMs = 600L
    const val collapsedScale = 0.45f
    const val glyphFraction = 0.48f
    const val allocationWarmup = 120
}
