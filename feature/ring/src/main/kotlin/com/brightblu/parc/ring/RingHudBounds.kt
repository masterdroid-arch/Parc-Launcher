package com.brightblu.parc.ring

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.RingState
import com.brightblu.parc.model.Slot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Preparation-only bound for the live arc sweep. T83 reads the scene the renderer actually draws:
 * a slot counts only when its rotation window position is visible, so empty outer positions on a
 * sparse or rotated arc no longer reserve space for an element that is not on screen. Stored
 * indices of items hidden by the window and unrotated indices of overflowing arcs are never used.
 * When the scene carries no drawable slot the complete arc sweep is the conservative fallback, and
 * a scene with no arcs at all leaves the whole height available.
 */
object RingHudBounds {
    /**
     * @param scene the published scene (Home is the one behind the HUD)
     * @param captionHeightDp raster height of a folder caption, including its padding
     * @param iconSizeDp the configured icon size (T71 clamp already applied)
     * @param rotations live settled offsets per arc id; defaults to the scene snapshot
     */
    fun bottomDp(scene: RingState, captionHeightDp: Float,
        iconSizeDp: Float = Metrics.iconDp,
        rotations: Map<String, Float> = scene.rotations): Float {
        var top = Float.POSITIVE_INFINITY
        val counts = IntArray(scene.arcs.size)
        for (slot in scene.slots) {
            val arc = arcPosition(scene, slot.position.arcId)
            if (arc >= 0) counts[arc]++
        }
        for (slot in scene.slots) {
            val arcIndex = arcPosition(scene, slot.position.arcId)
            if (arcIndex < 0) continue
            val arc = scene.arcs[arcIndex]
            if (arc.slotCapacity <= 0) continue
            var item = 0
            if (counts[arcIndex] > arc.slotCapacity) {
                // The renderer maps overflowing arcs through a dense presentation order.
                for (other in scene.slots) {
                    if (other.position.arcId == arc.id && other.position.index < slot.position.index) item++
                }
            } else item = slot.position.index
            val offset = rotations[arc.id] ?: 0f
            val position = RingRotation.windowPosition(item, offset, counts[arcIndex], arc.slotCapacity)
            if (RingRotation.edgeAlpha(position, arc.slotCapacity) <= 0f) continue
            val angle = RingRotation.windowAngleDeg(arc.slotAngle(0), RingRotation.pitchDeg(arc),
                item, offset, counts[arcIndex], arc.slotCapacity)
            val y = arc.originYDp + arc.radiusDp * sin(Math.toRadians(angle.toDouble())).toFloat()
            top = min(top, y - elementExtentDp(slot.slot, captionHeightDp, iconSizeDp))
        }
        if (!top.isFinite()) top = sweepTopDp(scene)
        return (top - Metrics.hubArcMarginDp).coerceAtLeast(0f)
    }

    /** Height of the clipped tile viewport between the hub header top and the arc reserve. */
    fun heightDp(topDp: Float, bottomDp: Float): Float = (bottomDp - topDp).coerceAtLeast(0f)

    /**
     * Distance from a slot centre to the top of its drawn element. Folder captions sit below the
     * icon unless the bottom keep-out flips them, so only the icon disc (and the touch target for
     * tappable items) grows upwards.
     */
    private fun elementExtentDp(slot: Slot, captionHeightDp: Float, iconSizeDp: Float): Float =
        if (slot is Slot.Folder) iconSizeDp / 2f + Metrics.labelGapDp + captionHeightDp
        else max(Metrics.touchTargetDp / 2f, iconSizeDp / 2f)

    private fun arcPosition(scene: RingState, arcId: String): Int {
        for (i in scene.arcs.indices) if (scene.arcs[i].id == arcId) return i
        return -1
    }

    /** The old complete-sweep rule, used only when the scene has no visible slot. */
    private fun sweepTopDp(scene: RingState): Float {
        if (scene.arcs.isEmpty()) return 0f
        var top = Float.POSITIVE_INFINITY
        for (arc in scene.arcs) {
            if (arc.slotCapacity == 0) continue
            val low = min(arc.startAngle, arc.endAngle)
            val high = max(arc.startAngle, arc.endAngle)
            val y = if (270f in low..high) arc.originYDp - arc.radiusDp else
                arc.originYDp + arc.radiusDp * min(sin(Math.toRadians(low.toDouble())),
                    sin(Math.toRadians(high.toDouble()))).toFloat()
            top = min(top, y - Metrics.touchTargetDp / 2f)
        }
        return if (top.isFinite()) top else 0f
    }
}
