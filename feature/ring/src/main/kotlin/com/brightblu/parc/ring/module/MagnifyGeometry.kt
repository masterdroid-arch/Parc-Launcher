package com.brightblu.parc.ring.module

import com.brightblu.parc.model.Magnify
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.SpringSpec
import com.brightblu.parc.model.Springs
import kotlin.math.cos
import kotlin.math.sin

/**
 * DESIGN 3.3, Magnify under thumb. Pure: the slot under the thumb grows and is pushed along its radius, away from
 * the hub; neighbours do not move. Angles are degrees in the screen frame (the hub is the origin, 270 = up).
 * [progress] is the press spring, 0 at rest and 1 fully magnified.
 */
object MagnifyGeometry {
    fun scale(setting: Magnify): Float = when (setting) {
        Magnify.Off -> 1f
        Magnify.Subtle -> Metrics.magnifySubtleScale
        Magnify.DockLike -> Metrics.magnifyDockScale
    }

    fun pushDp(setting: Magnify): Float = when (setting) {
        Magnify.Off -> 0f
        Magnify.Subtle -> Metrics.magnifySubtlePushDp
        Magnify.DockLike -> Metrics.magnifyDockPushDp
    }

    fun scaleAt(setting: Magnify, progress: Float): Float = 1f + (scale(setting) - 1f) * progress

    /** Radial push in dp, x part, for a slot at [angleDeg] from the hub. */
    fun pushX(setting: Magnify, angleDeg: Float, progress: Float = 1f): Float =
        cos(Math.toRadians(angleDeg.toDouble())).toFloat() * pushDp(setting) * progress

    /** Radial push in dp, y part. */
    fun pushY(setting: Magnify, angleDeg: Float, progress: Float = 1f): Float =
        sin(Math.toRadians(angleDeg.toDouble())).toFloat() * pushDp(setting) * progress

    /**
     * Where the magnified centre goes: the radial push, but never closer than [marginDp] to a card edge (slots near
     * the card edge have less room than the push). Full push when it fits; otherwise it slides along the edge.
     */
    fun pushedX(setting: Magnify, card: CardRect?, slotXDp: Float, angleDeg: Float, progress: Float = 1f,
        marginDp: Float = Metrics.magnifyCardMarginDp): Float {
        val full = slotXDp + pushX(setting, angleDeg)
        val end = if (card == null || setting == Magnify.Off) full else full.coerceIn(card.left + marginDp, card.right - marginDp)
        return slotXDp + (end - slotXDp) * progress
    }

    fun pushedY(setting: Magnify, card: CardRect?, slotYDp: Float, angleDeg: Float, progress: Float = 1f,
        marginDp: Float = Metrics.magnifyCardMarginDp): Float {
        val full = slotYDp + pushY(setting, angleDeg)
        val end = if (card == null || setting == Magnify.Off) full else full.coerceIn(card.top + marginDp, card.bottom - marginDp)
        return slotYDp + (end - slotYDp) * progress
    }
}

/**
 * The magnify spring (`springPress`) for the slot under the thumb. One channel grows the current subject; a second
 * channel lets the slot just left shrink back, so moving across slots never pops. A catch keeps value and velocity.
 */
class MagnifyMotion {
    private val now = SpringChannel()
    private val leaving = SpringChannel()
    /** Slot index of the magnified slot, or [NONE]. */
    var subject = NONE; private set
    var leavingSubject = NONE; private set
    val value: Float get() = now.value
    val leavingValue: Float get() = leaving.value
    val active: Boolean get() = !now.atRest || !leaving.atRest

    /** Progress of slot [index]: 0 for every slot that is not magnified or shrinking back. */
    fun progress(index: Int): Float =
        if (index == subject) now.value else if (index == leavingSubject) leaving.value else 0f

    /** Magnify [on] (or [NONE] to let go). The previous subject, if any, shrinks back with its own spring. */
    fun focus(on: Int) {
        if (on == subject) return
        if (on != NONE && on == leavingSubject) {
            // Back to the slot that was shrinking: swap, so each keeps its value and velocity.
            val v = now.value; val s = now.velocity
            now.set(leaving.value, leaving.velocity); leaving.set(v, s)
            val other = subject; subject = leavingSubject; leavingSubject = other
        } else {
            if (subject != NONE) {
                leaving.set(now.value, now.velocity)
                leaving.retarget(0f)
                leavingSubject = subject
            }
            now.set(0f, 0f)
            subject = on
        }
        now.retarget(if (subject == NONE) 0f else 1f)
        leaving.retarget(0f)
    }

    /** Animator scale 0 and scene changes: land on the end values at once. */
    fun jumpToEnd() {
        now.jump(if (subject == NONE) 0f else 1f)
        leaving.jump(0f); leavingSubject = NONE
        if (subject == NONE) now.jump(0f)
    }

    fun clear() { now.jump(0f); leaving.jump(0f); subject = NONE; leavingSubject = NONE }

    fun step(dtSec: Float, spec: SpringSpec, stiffnessScale: Float, timeScale: Float) {
        if (timeScale <= 0f) { jumpToEnd(); return }
        val dt = dtSec / timeScale
        val k = Springs.scaledStiffness(spec.stiffness, stiffnessScale)
        now.step(dt, spec.damping, k)
        leaving.step(dt, spec.damping, k)
        if (leaving.atRest) leavingSubject = NONE
        if (subject == NONE && now.atRest) now.jump(0f)
    }

    companion object { const val NONE = -1 }
}
