package com.brightblu.parc.ring.module

import com.brightblu.parc.model.SpringSpec
import com.brightblu.parc.model.Springs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One spring on a float, stepped with the exact solution so a late frame cannot make it unstable.
 * Pure Kotlin; stepping allocates nothing. Unit mass, so [Springs] values apply as they are.
 */
class SpringChannel(start: Float = 0f) {
    var value = start; private set
    var velocity = 0f; private set
    var target = start; private set

    val atRest: Boolean get() = value == target && velocity == 0f

    fun retarget(to: Float) { target = to }
    fun jump(to: Float) { value = to; target = to; velocity = 0f }
    /** Keeps the position, replaces the velocity (used when the direction of travel flips). */
    fun set(position: Float, speed: Float) { value = position; velocity = speed }

    fun step(dtSec: Float, damping: Float, stiffness: Float) {
        if (dtSec <= 0f) return
        val x0 = value - target
        val w0 = sqrt(stiffness)
        val z = damping.coerceIn(0f, 0.9999f)
        val e = exp(-z * w0 * dtSec)
        val wd = w0 * sqrt(1f - z * z)
        val c = cos(wd * dtSec)
        val s = sin(wd * dtSec)
        val b = (velocity + z * w0 * x0) / wd
        val x = e * (x0 * c + b * s)
        velocity = e * (velocity * c - ((z * w0 * velocity + w0 * w0 * x0) / wd) * s)
        value = target + x
        if (abs(x) < REST_DISTANCE && abs(velocity) < REST_SPEED) { value = target; velocity = 0f }
    }

    companion object {
        const val REST_DISTANCE = 0.001f
        const val REST_SPEED = 0.02f
    }
}

/**
 * Open and close of a ring (DESIGN 2.6): a master channel for the whole scene change, in
 * "progress towards the scene now showing", plus one channel per slot in "how far the slot has
 * grown out of the hub" (0 folded, 1 out). Slot i starts after `Springs.openDelayMs`; closing is
 * the time reverse (the last slot to open folds in first). A catch keeps every value and velocity,
 * so the next motion continues from there.
 */
class OpenMotion {
    var master = SpringChannel(1f); private set
    private var slots = arrayOfNulls<SpringChannel>(0)
    private var delays = FloatArray(0)
    var count = 0; private set
    /** Slot growth, read by the draw loop. Index beyond [count] reads as fully out. */
    var growth = FloatArray(0); private set
    var opening = true; private set
    var frozen = false; private set

    val active: Boolean get() {
        if (!master.atRest) return true
        for (i in 0 until count) if (!slots[i]!!.atRest) return true
        return false
    }
    val settled: Boolean get() {
        if (active) return false
        for (i in 0 until count) if (delays[i] > 0f) return false
        return true
    }

    private fun ensure(n: Int) {
        if (n > slots.size) {
            slots = slots.copyOf(n); delays = delays.copyOf(n); growth = growth.copyOf(n)
        }
        for (i in 0 until n) if (slots[i] == null) slots[i] = SpringChannel()
        count = n
    }

    private fun aim(open: Boolean) {
        opening = open
        for (i in 0 until count) {
            slots[i]!!.retarget(if (open) 1f else 0f)
            delays[i] = Springs.openDelayMs(if (open) i else count - 1 - i, count).toFloat()
        }
    }

    /** A fresh scene change: master 0 to 1, slots folded (opening) or out (closing). */
    fun begin(slotCount: Int, open: Boolean) {
        ensure(slotCount.coerceAtLeast(0))
        frozen = false
        master.jump(0f); master.retarget(1f)
        for (i in 0 until count) slots[i]!!.jump(if (open) 0f else 1f)
        aim(open)
        publish()
    }

    /**
     * The scene change was reversed before it finished: continue from where it is. [flip] swaps
     * folding and unfolding (a folder); otherwise the slots keep unfolding.
     */
    fun reverse(slotCount: Int, flip: Boolean) {
        ensure(slotCount.coerceAtLeast(0))
        frozen = false
        master.set(1f - master.value, -master.velocity)
        master.retarget(1f)
        aim(if (flip) !opening else true)
        publish()
    }

    /** A touch stops the motion; values and velocities stay. */
    fun catch() { frozen = true }
    fun resume() { frozen = false }

    /** Animator duration scale 0: everything lands on its target at once. */
    fun jumpToEnd() {
        frozen = false
        master.jump(1f)
        for (i in 0 until count) { slots[i]!!.jump(if (opening) 1f else 0f); delays[i] = 0f }
        publish()
    }

    /** [stiffnessScale] is 1 for the plain token; [timeScale] stretches time (animator scale). */
    fun step(dtSec: Float, spec: SpringSpec, stiffnessScale: Float, timeScale: Float) {
        if (frozen || timeScale <= 0f) return
        val dt = dtSec / timeScale
        val k = Springs.scaledStiffness(spec.stiffness, stiffnessScale)
        master.step(dt, spec.damping, k)
        for (i in 0 until count) {
            if (delays[i] > 0f) {
                delays[i] -= dt * 1000f
                if (delays[i] > 0f) continue
                val rest = -delays[i] / 1000f
                delays[i] = 0f
                slots[i]!!.step(rest, spec.damping, k)
            } else slots[i]!!.step(dt, spec.damping, k)
        }
        publish()
    }

    private fun publish() { for (i in 0 until count) growth[i] = slots[i]!!.value }
}

/** Press scale of the touched slot or hub: one spring between rest (0) and pressed (1). */
class PressMotion {
    private val channel = SpringChannel()
    /** Slot index, [HUB], or [NONE]. */
    var subject = NONE; private set
    val value: Float get() = channel.value
    val active: Boolean get() = !channel.atRest

    fun press(on: Int) {
        if (on != subject) channel.jump(0f)
        subject = on
        channel.retarget(1f)
    }
    fun release() { channel.retarget(0f) }
    fun jumpToEnd() { channel.jump(0f); subject = NONE }

    fun step(dtSec: Float, spec: SpringSpec, stiffnessScale: Float, timeScale: Float) {
        if (timeScale <= 0f) { jumpToEnd(); return }
        channel.step(dtSec / timeScale, spec.damping, Springs.scaledStiffness(spec.stiffness, stiffnessScale))
        if (channel.atRest && channel.target == 0f) subject = NONE
    }

    /** Scale the subject is drawn at; 1 at rest. */
    fun scale(pressed: Float): Float = 1f - (1f - pressed) * channel.value

    companion object { const val NONE = -1; const val HUB = -2 }
}
