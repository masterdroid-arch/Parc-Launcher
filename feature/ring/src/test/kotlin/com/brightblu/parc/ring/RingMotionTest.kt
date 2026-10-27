package com.brightblu.parc.ring

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import org.junit.Assert.*
import org.junit.Test

class RingMotionTest {
    private val skin = Skin.Midnight
    @Test fun wakeIsSequentialAndTouchImmediatelyFinishesIt() {
        val m = RingMotion(skin); m.resume(1000)
        m.step(1200, 1f, false)
        assertEquals(.5f, m.sweep, .001f); assertEquals(0f, m.slots, 0f)
        m.step(1480, 1f, false)
        assertEquals(1f, m.sweep, 0f); assertEquals(.5f, m.slots, .001f)
        m.resume(2000); m.touch(2010); m.step(2010, 1f, false)
        assertEquals(1f, m.sweep, 0f); assertEquals(1f, m.slots, 0f)
    }
    @Test fun idleHasNoScheduledFramesAndInputRestartsBreathing() {
        val m = RingMotion(skin); m.resume(0)
        assertEquals(0L, m.step(9999, 1f, false))
        assertEquals(Long.MAX_VALUE, m.step(10000, 1f, false))
        assertEquals(1f, m.lineAlpha, 0f)
        m.touch(20000)
        assertEquals(0L, m.step(20001, 1f, false))
        assertEquals(Long.MAX_VALUE, m.step(30000, 1f, false))
    }
    @Test fun zeroScaleSettlesAndNonzeroScaleChangesDurations() {
        val m = RingMotion(skin); m.resume(0)
        m.step(400, 2f, false); assertEquals(.5f, m.sweep, .001f)
        assertEquals(Long.MAX_VALUE, m.step(401, 0f, true))
        assertEquals(1f, m.slots, 0f); assertEquals(0f, m.pulse, 0f)
    }
    @Test fun pulseSwellsOnceThenSleepsAndRemovalStopsIt() {
        val m = RingMotion(skin); m.resume(0); m.step(0, 1f, true)
        m.step(800, 1f, true); assertEquals(1f, m.pulse, .001f)
        assertEquals(2000L, m.step(10000, 1f, true)); assertEquals(0f, m.pulse, 0f)
        assertEquals(0L, m.step(12000, 1f, true))
        assertEquals(Long.MAX_VALUE, m.step(12001, 1f, false))
    }
    @Test fun swapFadesInAndDrivesFramesUntilItLands() {
        val m = RingMotion(skin); m.resume(0); m.startSwap(11000)
        assertEquals(0f, m.swap, 0.001f)
        assertEquals(0L, m.step(11010, 1f, false))
        m.step(11000L + Metrics.swapFadeMs / 2, 1f, false)
        assertEquals(.5f, m.swap, .01f)
        m.step(11000L + Metrics.swapFadeMs, 1f, false)
        assertEquals(1f, m.swap, 0f)
    }

    @Test fun searchHintSwellsOncePerStartThenDisarms() {
        val m = RingMotion(skin); m.resume(0)
        assertEquals(0f, m.hint, 0f)
        m.step(800, 1f, false, true); assertTrue(m.hint > .9f)
        assertEquals(0L, m.step(801, 1f, false, true))
        m.step(1600, 1f, false, true); assertEquals(0f, m.hint, .001f)
        assertEquals(Long.MAX_VALUE, m.step(11000, 1f, false, true))
        // Without the hint active, no swell ever plays.
        val off = RingMotion(skin); off.resume(0)
        off.step(800, 1f, false, false); assertEquals(0f, off.hint, 0f)
    }

    @Test fun springConvergesAndResumeResetsOldActivity() {
        val m = RingMotion(skin); m.resume(0); m.finger(0f, 0f, true); m.finger(100f, 200f)
        m.step(16, 1f, false)
        assertTrue(m.glowX > 0f && m.glowX < 100f)
        for (time in 32L..2000L step 16) m.step(time, 1f, false)
        assertEquals(100f, m.glowX, .1f); assertEquals(200f, m.glowY, .1f)
        m.resume(50000); m.step(50000, 1f, false)
        assertEquals(0f, m.sweep, 0f)
    }
}
