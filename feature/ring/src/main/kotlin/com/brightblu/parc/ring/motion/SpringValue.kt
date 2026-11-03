package com.brightblu.parc.ring.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import com.brightblu.parc.model.SpringSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A captured position and velocity (units/second in spring time). */
data class SpringSnapshot(val value: Float, val velocity: Float)

/**
 * Main-thread animation state, read in draw scope. Use [rememberSpringValue] in UI code.
 * Call suspending operations from the UI coroutine scope; a replacement cancels the previous
 * child, preserving the last frame's velocity even though Animatable.stop resets its velocity.
 * Compose applies [durationScale] to spring time; we apply it only to the stagger delay.
 */
class SpringValue(
    initialValue: Float,
    private val durationScale: MotionDurationScale = object : MotionDurationScale {
        override val scaleFactor = 1f
    },
) {
    private val animation = Animatable(initialValue)
    private var running: Job? = null
    private var carriedVelocity = 0f
    val value: Float get() = animation.value
    val velocity: Float get() = carriedVelocity

    suspend fun animateTo(target: Float, spec: SpringSpec, delayMs: Int = 0) = coroutineScope {
        running?.cancelAndJoin()
        val next = launch(durationScale) {
            if (durationScale.scaleFactor == 0f) {
                animation.snapTo(target)
                carriedVelocity = 0f
                return@launch
            }
            var remaining = delayMs.coerceAtLeast(0).toDouble() * 1_000_000
            if (remaining > 0) {
                var previous = withFrameNanos { it }
                while (remaining > 0 && durationScale.scaleFactor > 0f) {
                    val now = withFrameNanos { it }
                    val scale = durationScale.scaleFactor
                    if (scale > 0f) remaining -= (now - previous) / scale.toDouble()
                    previous = now
                }
            }
            if (durationScale.scaleFactor == 0f) {
                animation.snapTo(target)
            } else {
                animation.animateTo(
                    target,
                    spring(dampingRatio = spec.damping, stiffness = spec.stiffness),
                    initialVelocity = carriedVelocity,
                ) { carriedVelocity = velocity }
            }
            carriedVelocity = 0f
        }
        running = next
        next.join()
    }

    suspend fun `catch`(): SpringSnapshot {
        running?.cancelAndJoin()
        running = null
        return SpringSnapshot(value, carriedVelocity)
    }

    suspend fun jumpTo(target: Float) {
        running?.cancelAndJoin()
        running = null
        animation.snapTo(target)
        carriedVelocity = 0f
    }
}
