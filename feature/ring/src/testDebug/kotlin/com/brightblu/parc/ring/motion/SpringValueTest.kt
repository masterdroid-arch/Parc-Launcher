package com.brightblu.parc.ring.motion

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.MotionDurationScale
import com.brightblu.parc.model.Springs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpringValueTest {
    private class Clock : MonotonicFrameClock {
        private val frames = Channel<Long>(Channel.UNLIMITED)
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
        fun tick() { frames.trySend(time); time += 16_666_667L }
    }
    private class Scale(override var scaleFactor: Float) : MotionDurationScale

    @Test fun openOvershootsAndSettlesWithin700ms() = runTest {
        val clock = Clock()
        val value = SpringValue(0f)
        val job = launch(clock) { value.animateTo(1f, Springs.open) }
        runCurrent()
        var peak = 0f
        var frames = 0
        while (!job.isCompleted && frames < 42) {
            clock.tick(); runCurrent(); frames++
            peak = maxOf(peak, value.value)
        }
        assertTrue("peak=$peak", peak in 1.05f..1.12f)
        assertTrue("frames=$frames", job.isCompleted)
        assertEquals(1f, value.value, 0f)
    }

    @Test fun catchAndRetargetPreservePositionAndVelocity() = runTest {
        val clock = Clock()
        val value = SpringValue(0f)
        launch(clock) { value.animateTo(1f, Springs.open) }
        runCurrent()
        repeat(5) { clock.tick(); runCurrent() }
        val caught = value.`catch`()
        assertTrue(caught.value > 0f && caught.value < 1f)
        assertTrue(caught.velocity > 0f)
        val next = launch(clock) { value.animateTo(0f, Springs.open) }
        runCurrent()
        assertEquals(caught.value, value.value, 0f)
        clock.tick(); runCurrent()
        assertEquals(caught.value, value.value, 0.00001f)
        assertEquals(caught.velocity, value.velocity, 0.00001f)
        value.jumpTo(0f); next.join()
        assertEquals(0f, value.velocity, 0f)
    }

    @Test fun directRetargetAlsoCarriesVelocity() = runTest {
        val clock = Clock()
        val value = SpringValue(0f)
        launch(clock) { value.animateTo(1f, Springs.open) }
        runCurrent()
        repeat(5) { clock.tick(); runCurrent() }
        val position = value.value
        val velocity = value.velocity
        launch(clock) { value.animateTo(0f, Springs.open) }
        runCurrent(); clock.tick(); runCurrent()
        assertEquals(position, value.value, 0.00001f)
        assertEquals(velocity, value.velocity, 0.00001f)
        value.jumpTo(0f)
    }

    @Test fun zeroScaleSkipsFramesAndDelay() = runTest {
        val value = SpringValue(0f, Scale(0f))
        value.animateTo(1f, Springs.open, 100)
        assertEquals(1f, value.value, 0f)
        assertEquals(0f, value.velocity, 0f)
    }

    @Test fun composeScalesSpringTimeAndHelperScalesDelay() = runTest {
        suspend fun sample(scale: Float, frames: Int, delay: Int = 0): Float {
            val clock = Clock()
            val value = SpringValue(0f, Scale(scale))
            launch(clock) { value.animateTo(1f, Springs.open, delay) }
            runCurrent()
            repeat(frames) { clock.tick(); runCurrent() }
            val result = value.value
            value.`catch`()
            return result
        }
        assertEquals(sample(1f, 7), sample(5f, 31), 0.0001f)
        assertEquals(sample(1f, 7), sample(0.5f, 4), 0.0001f)
        assertEquals(0f, sample(5f, 20, 100), 0f)
        assertTrue(sample(1f, 20, 100) > 0f)
    }

    @Test fun disablingDuringDelayJumpsOnNextFrame() = runTest {
        val clock = Clock()
        val scale = Scale(1f)
        val value = SpringValue(0f, scale)
        val job = launch(clock) { value.animateTo(1f, Springs.open, 100) }
        runCurrent(); clock.tick(); runCurrent()
        scale.scaleFactor = 0f
        clock.tick(); runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(1f, value.value, 0f)
    }
}
