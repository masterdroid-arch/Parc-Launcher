package com.brightblu.parc.ring.module

import com.brightblu.parc.model.Springs
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class OpenMotionTest {
    private val frame = 1f / 60f

    private fun OpenMotion.frame() = step(frame, Springs.open, 1f, 1f)

    @Test fun delayTable() {
        val delays = (0 until 5).map { Springs.openDelayMs(it, 5) }
        assertEquals(listOf(0, 25, 50, 75, 100), delays)
        assertEquals(0, Springs.openDelayMs(0, 1))
        assertEquals(0, Springs.openDelayMs(3, 0))
    }

    @Test fun openSpringPeaksAndSettles() {
        val m = OpenMotion(); m.begin(5, true)
        var peak = 0f; var settledAt = -1; var masterAt = -1
        for (f in 1..120) {
            m.frame()
            peak = maxOf(peak, m.master.value, m.growth[4])
            if (masterAt < 0 && m.master.atRest) masterAt = f
            if (settledAt < 0 && m.settled) settledAt = f
        }
        assertTrue("peak $peak", peak in 1.05f..1.12f)
        assertTrue("spring at rest at frame $masterAt", masterAt in 1..42) // under 700 ms
        assertTrue("with the 100 ms spread, frame $settledAt", settledAt in 1..48)
        assertEquals(1f, m.master.value, 0f)
    }

    @Test fun pressSpringStaysUnderThreePercent() {
        val p = PressMotion(); p.press(2)
        var peak = 0f
        for (f in 1..120) { p.step(frame, Springs.press, 1f, 1f); peak = maxOf(peak, p.value) }
        assertTrue("peak $peak", peak < 1.03f)
        assertEquals(1f, p.value, 0f)
        assertEquals(0.92f, p.scale(0.92f), 1e-6f)
        p.release()
        for (f in 1..120) p.step(frame, Springs.press, 1f, 1f)
        assertEquals(1f, p.scale(0.92f), 0f)
        assertEquals(PressMotion.NONE, p.subject)
    }

    @Test fun closeIsTheMirrorOfOpen() {
        val open = OpenMotion(); open.begin(4, true)
        val close = OpenMotion(); close.begin(4, false)
        for (f in 1..60) {
            open.frame(); close.frame()
            // The last slot to open is the first to fold, so compare slot i with slot n-1-i.
            for (i in 0 until 4) assertEquals(open.growth[i], 1f - close.growth[3 - i], 1e-4f)
            assertEquals(open.master.value, close.master.value, 1e-6f)
        }
    }

    @Test fun slotsStartInTheirDelayOrder() {
        val m = OpenMotion(); m.begin(5, true)
        for (f in 1..3) m.frame() // 50 ms
        assertTrue(m.growth[0] > m.growth[1] && m.growth[1] > m.growth[2])
        assertEquals(0f, m.growth[4], 0f)
    }

    @Test fun catchAndReverseContinueWithoutAJump() {
        val m = OpenMotion(); m.begin(5, true)
        var biggest = 0f
        val trace = ArrayList<Float>()
        for (f in 1..9) { m.frame(); trace.add(m.growth[2]) }
        for (i in 1 until trace.size) biggest = maxOf(biggest, abs(trace[i] - trace[i - 1]))
        val before = m.growth.copyOf(); val folderBefore = 1f - m.master.value
        val speedBefore = m.master.velocity
        assertTrue(before[2] > 0f && before[2] < 1f)
        m.catch()
        m.frame() // frozen: nothing moves
        assertArrayEquals(before, m.growth, 0f)
        m.reverse(5, flip = true)
        assertFalse(m.frozen)
        // What is on screen (towards the folder) is the same; the speed points the other way.
        assertEquals(folderBefore, m.master.value, 1e-6f)
        assertEquals(-speedBefore, m.master.velocity, 1e-6f)
        for (i in 0 until 5) assertEquals(before[i], m.growth[i], 0f)
        m.frame()
        for (i in 0 until 5) assertTrue(abs(m.growth[i] - before[i]) <= biggest + 1e-4f)
        for (f in 1..120) m.frame()
        assertTrue(m.settled)
        for (i in 0 until 5) assertEquals(0f, m.growth[i], 0f)
    }

    @Test fun animatorScaleZeroJumps() {
        val m = OpenMotion(); m.begin(3, true)
        m.step(frame, Springs.open, 1f, 0f)
        assertEquals(0f, m.growth[0], 0f)
        m.jumpToEnd()
        assertTrue(m.settled)
        for (i in 0 until 3) assertEquals(1f, m.growth[i], 0f)
        val p = PressMotion(); p.press(1)
        p.step(frame, Springs.press, 1f, 0f)
        assertEquals(0f, p.value, 0f)
        assertFalse(p.active)
    }

    @Test fun slowerAnimatorScaleSlowsTheSpring() {
        val fast = OpenMotion(); fast.begin(1, true)
        val slow = OpenMotion(); slow.begin(1, true)
        repeat(6) { fast.step(frame, Springs.open, 1f, 1f); slow.step(frame, Springs.open, 1f, 2f) }
        assertTrue(slow.master.value < fast.master.value)
    }

    @Test fun emptyAndSingleSlotNeverCrash() {
        val m = OpenMotion(); m.begin(0, true)
        repeat(60) { m.frame() }
        assertTrue(m.settled)
        m.begin(1, false)
        repeat(60) { m.frame() }
        assertEquals(0f, m.growth[0], 0f)
    }
}
