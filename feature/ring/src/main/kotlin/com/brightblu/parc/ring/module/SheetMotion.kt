package com.brightblu.parc.ring.module

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.SpringSpec
import com.brightblu.parc.model.Springs
import com.brightblu.parc.ring.motion.rememberAnimatorScale

/**
 * Progress of a sheet or a menu from 0 (away) to 1 (shown) on one spring (`Springs.sheet`, DESIGN 2.6).
 * Pure Kotlin. A catch (a touch during the motion) keeps the value and the velocity; the next [open] or [close]
 * continues from there, so nothing jumps to an end. Animator duration scale 0 lands on the target at once.
 */
class SheetMotion(start: Float = 0f, private val spec: SpringSpec = Springs.sheet) {
    private val channel = SpringChannel(start)

    val progress: Float get() = channel.value
    val velocity: Float get() = channel.velocity
    val target: Float get() = channel.target
    val atRest: Boolean get() = channel.atRest
    var caught = false; private set

    fun open() { caught = false; channel.retarget(1f) }
    fun close() { caught = false; channel.retarget(0f) }

    /** A touch stops a motion that is under way; nothing happens when the sheet is at rest. */
    fun catchIt() { if (!channel.atRest) caught = true }
    /** The touch ended without a decision: carry on towards the same target. */
    fun resume() { caught = false }

    fun jumpToEnd() { caught = false; channel.jump(channel.target) }

    /** [stiffnessScale] is 1 for the plain token; [timeScale] stretches time (animator scale, 0 jumps). */
    fun step(dtSec: Float, stiffnessScale: Float = 1f, timeScale: Float = 1f) {
        if (caught || channel.atRest) return
        if (timeScale <= 0f) { jumpToEnd(); return }
        channel.step(dtSec / timeScale, spec.damping, Springs.scaledStiffness(spec.stiffness, stiffnessScale))
    }
}

/**
 * [SheetMotion] driven by the frame clock. [progress] is a snapshot state read in draw or layer lambdas only
 * (the host never recomposes per frame). The sheet is "shown" while [open] is set or the value has not reached 0.
 */
class SheetProgress internal constructor() {
    internal val motion = SheetMotion()
    private val state = mutableFloatStateOf(0f)
    val progress: Float get() = state.floatValue
    val caught: Boolean get() = motion.caught
    val moving: Boolean get() = !motion.atRest

    fun catchIt() = motion.catchIt()
    fun resume() = motion.resume()

    internal fun publish() { state.floatValue = motion.progress }

    internal suspend fun run(scale: () -> Float, stiffnessScale: Float) {
        if (scale() <= 0f) { motion.jumpToEnd(); publish(); return }
        var last = withFrameNanos { it }
        while (!motion.atRest) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceAtMost(Metrics.springMaxStepMs / 1000f)
            last = now
            val timeScale = scale()
            motion.step(dt, stiffnessScale, timeScale)
            publish()
        }
        publish()
    }
}

/**
 * Retargets the sheet whenever [open] flips, continuing from the current value and velocity.
 * [stiffnessScale] follows the skin (`1 / motionScale`, like the ring springs).
 */
@Composable
fun rememberSheetProgress(open: Boolean, stiffnessScale: Float = 1f): SheetProgress {
    val animatorScale = rememberAnimatorScale()
    val sheet = remember { SheetProgress() }
    LaunchedEffect(open, stiffnessScale) {
        if (open) sheet.motion.open() else sheet.motion.close()
        sheet.run({ animatorScale.value }, stiffnessScale)
    }
    return sheet
}
