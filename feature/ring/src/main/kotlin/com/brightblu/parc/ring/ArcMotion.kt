package com.brightblu.parc.ring

import com.brightblu.parc.model.ArcFeel
import com.brightblu.parc.model.Metrics
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

enum class ArcPhase { Rest, Dragging, Settling }

/**
 * One arc's rotation, as an immutable value (T148, DESIGN §3.2). Offsets and velocities are in
 * slots and slots per second. While [Dragging] the finger moves the offset 1:1; [Settling] is the
 * damped spring toward the whole slot [target]. The spring runs in simulated seconds, which are
 * real seconds divided by [timeScale] (system animator scale times the skin's motion scale).
 */
data class ArcMotionState(
    val phase: ArcPhase = ArcPhase.Rest,
    val offset: Float = 0f,
    /** Velocity in real slots per second: the caught velocity while grabbed, the spring's while settling. */
    val velocity: Float = 0f,
    /** The whole slot at the front when the finger went down; the Spin limit counts from here. */
    val startSlot: Int = 0,
    val target: Int = 0,
    /** Spring start offset and velocity (simulated seconds), elapsed simulated time, damping and time scale. */
    val from: Float = 0f,
    val fromVelocity: Float = 0f,
    val elapsed: Float = 0f,
    val damping: Float = 0.72f,
    val timeScale: Float = 1f,
    /** The slot last reported as passing the front, for the tick haptic. */
    val tickSlot: Int = 0,
    /** True once the offset differs from the last persisted whole slot (a drag or a catch). */
    val dirty: Boolean = false,
    /** True once the finger has moved the arc since [ArcMotion.down]. */
    val dragged: Boolean = false,
)

/** A new state plus whether one slot passed the front during this step (at most one tick per step). */
data class ArcMotionStep(val state: ArcMotionState, val tick: Boolean)

/** Pure drag, release and settle rules. No Android or Compose types. */
object ArcMotion {
    fun slotOf(offset: Float): Int = floor(offset + 0.5f).toInt()

    fun rest(offset: Float): ArcMotionState = ArcMotionState(offset = offset, target = slotOf(offset),
        startSlot = slotOf(offset), tickSlot = slotOf(offset))

    /** Finger down on the arc. A settle in progress is caught at its current offset and velocity. */
    fun down(current: ArcMotionState): ArcMotionState {
        val caught = current.phase == ArcPhase.Settling
        val slot = slotOf(current.offset)
        return current.copy(phase = ArcPhase.Dragging, velocity = if (caught) current.velocity else 0f,
            startSlot = slot, target = slot, tickSlot = slot, dirty = caught || current.dirty, dragged = false)
    }

    /** The finger moves the arc by [deltaOffset] slots, 1:1. */
    fun drag(s: ArcMotionState, deltaOffset: Float): ArcMotionStep {
        if (s.phase != ArcPhase.Dragging || !deltaOffset.isFinite()) return ArcMotionStep(s, false)
        val offset = s.offset + deltaOffset
        val slot = slotOf(offset)
        val tick = slot != s.tickSlot
        return ArcMotionStep(s.copy(offset = offset, tickSlot = slot, dirty = true, dragged = true), tick)
    }

    /**
     * Finger up. [fingerVelocity] (offset slots per second) is used if the finger moved the arc;
     * a finger that only caught a moving arc hands the caught velocity back to the spring.
     * [timeScale] zero or less jumps to the target.
     */
    fun release(s: ArcMotionState, fingerVelocity: Float, feel: ArcFeel, timeScale: Float): ArcMotionStep {
        if (s.phase != ArcPhase.Dragging) return ArcMotionStep(s, false)
        val limit = feel.spinLimit.slots
        val v = ArcRelease.clampVelocity(if (s.dragged) fingerVelocity else s.velocity, limit)
        val target = ArcRelease.target(s.startSlot, s.offset, v, limit)
        val settling = s.copy(phase = ArcPhase.Settling, target = target, from = s.offset,
            velocity = v, fromVelocity = v * timeScale, elapsed = 0f, damping = feel.bounce.dampingRatio,
            timeScale = timeScale)
        if (timeScale <= 0f || !timeScale.isFinite()) return jump(settling)
        // Nothing to do: already on the whole slot and not moving.
        if (abs(s.offset - target) <= Metrics.rotateSettleEps && abs(v) <= Metrics.rotateVelocityMin) {
            return ArcMotionStep(settling.copy(phase = ArcPhase.Rest, offset = target.toFloat(), velocity = 0f), false)
        }
        return ArcMotionStep(settling, false)
    }

    /** Go straight to the target slot (animator duration scale 0). */
    fun jump(s: ArcMotionState): ArcMotionStep {
        if (s.phase == ArcPhase.Rest) return ArcMotionStep(s, false)
        val tick = s.target != s.tickSlot
        return ArcMotionStep(s.copy(phase = ArcPhase.Rest, offset = s.target.toFloat(), velocity = 0f,
            tickSlot = s.target), tick)
    }

    /** Advance a settle by [dtSeconds] of real time. Ends exactly on the target slot. */
    fun step(s: ArcMotionState, dtSeconds: Float): ArcMotionStep {
        if (s.phase != ArcPhase.Settling) return ArcMotionStep(s, false)
        val scale = if (s.timeScale > 0f) s.timeScale else 1f
        val elapsed = s.elapsed + dtSeconds.coerceAtLeast(0f) / scale
        val omega = sqrt(com.brightblu.parc.model.Springs.settleStiffness.toDouble())
        val zeta = s.damping.toDouble().coerceIn(0.05, 0.999)
        val alpha = zeta * omega
        val wd = omega * sqrt(1.0 - zeta * zeta)
        val d0 = (s.from - s.target).toDouble()
        val b = (s.fromVelocity + alpha * d0) / wd
        val e = exp(-alpha * elapsed)
        val c = cos(wd * elapsed)
        val sn = sin(wd * elapsed)
        val d = e * (d0 * c + b * sn)
        val vSim = e * (-alpha * (d0 * c + b * sn) + wd * (-d0 * sn + b * c))
        val offset = (s.target + d).toFloat()
        val velocity = (vSim / scale).toFloat()
        val slot = slotOf(offset)
        val tick = slot != s.tickSlot
        if (RingRotation.isSettled(offset, velocity, s.target.toFloat())) {
            val landed = s.target.toFloat()
            return ArcMotionStep(s.copy(phase = ArcPhase.Rest, offset = landed, velocity = 0f, elapsed = elapsed,
                tickSlot = s.target), s.target != s.tickSlot)
        }
        return ArcMotionStep(s.copy(offset = offset, velocity = velocity, elapsed = elapsed, tickSlot = slot), tick)
    }
}
