package com.brightblu.parc.ring.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** T157: the search sheet and the menus share one spring (`Springs.sheet`, 0.88 / 260). */
class SheetMotionTest {
    private val frame = 1f / 60f

    private fun SheetMotion.frames(n: Int, scale: Float = 1f) { repeat(n) { step(frame, 1f, scale) } }

    @Test fun opensWithoutVisibleWobbleAndSettles() {
        val m = SheetMotion(); m.open()
        var peak = 0f; var restAt = -1
        for (f in 1..120) {
            m.step(frame)
            peak = maxOf(peak, m.progress)
            if (restAt < 0 && m.atRest) restAt = f
        }
        assertTrue("peak $peak", peak < 1.01f) // no visible wobble: under one percent past the end
        assertTrue("at rest at frame $restAt", restAt in 1..60)
        assertEquals(1f, m.progress, 0f)
    }

    @Test fun closeGoesBackToZero() {
        val m = SheetMotion(1f); m.close()
        var low = 0f
        for (f in 1..120) { m.step(frame); low = minOf(low, m.progress) }
        assertTrue("low $low", low > -0.01f)
        assertEquals(0f, m.progress, 0f)
        assertTrue(m.atRest)
    }

    @Test fun catchMidOpenKeepsValueAndVelocityThenContinuesFromThere() {
        val m = SheetMotion(); m.open(); m.frames(6)
        val at = m.progress; val speed = m.velocity
        assertTrue("mid motion $at", at in 0.05f..0.95f)
        assertTrue(speed > 0f)
        m.catchIt()
        assertTrue(m.caught)
        m.frames(10)
        assertEquals("frozen while caught", at, m.progress, 0f)
        assertEquals(speed, m.velocity, 0f)
        // The user drags it down: close continues from that position and speed, with no jump.
        m.close()
        assertFalse(m.caught)
        var previous = m.progress
        var biggest = 0f
        for (f in 1..120) {
            m.step(frame)
            biggest = maxOf(biggest, abs(m.progress - previous))
            previous = m.progress
        }
        // The first frame keeps travelling up for a moment (its speed is kept), then it turns round; never a step bigger than free flight.
        assertTrue("largest frame step $biggest", biggest < 0.12f)
        assertEquals(0f, m.progress, 0f)
    }

    @Test fun firstFrameAfterCatchContinuesTheTravelDirectionAndSpeed() {
        val m = SheetMotion(); m.open(); m.frames(4)
        val at = m.progress; val speed = m.velocity
        m.catchIt(); m.close()
        m.step(frame)
        // Same position, same velocity as before the catch: one small step on in the old direction (value goes on up a little).
        assertTrue("moved ${m.progress - at}", abs(m.progress - at) < speed * frame * 1.05f + 0.01f)
    }

    @Test fun resumeCarriesOnToTheSameTarget() {
        val m = SheetMotion(); m.open(); m.frames(5)
        m.catchIt(); m.frames(5); m.resume()
        m.frames(120)
        assertEquals(1f, m.progress, 0f)
    }

    @Test fun catchAtRestDoesNothing() {
        val m = SheetMotion(1f)
        m.catchIt()
        assertFalse(m.caught)
    }

    @Test fun animatorScaleZeroJumpsToTheTarget() {
        val open = SheetMotion(); open.open(); open.step(frame, 1f, 0f)
        assertEquals(1f, open.progress, 0f); assertEquals(0f, open.velocity, 0f); assertTrue(open.atRest)
        val close = SheetMotion(1f); close.close(); close.step(frame, 1f, 0f)
        assertEquals(0f, close.progress, 0f); assertTrue(close.atRest)
        val caught = SheetMotion(); caught.open(); caught.frames(3); caught.catchIt(); caught.jumpToEnd()
        assertEquals(1f, caught.progress, 0f); assertFalse(caught.caught)
    }

    @Test fun slowerAnimatorScaleSlowsTheSheet() {
        val normal = SheetMotion(); normal.open(); normal.frames(10)
        val slow = SheetMotion(); slow.open(); slow.frames(10, scale = 2f)
        assertTrue("normal ${normal.progress}, slow ${slow.progress}", slow.progress < normal.progress)
    }

    @Test fun stiffnessScaleSpeedsTheSheetUp() {
        val plain = SheetMotion(); plain.open(); repeat(10) { plain.step(frame, 1f, 1f) }
        val stiff = SheetMotion(); stiff.open(); repeat(10) { stiff.step(frame, 1.5f, 1f) }
        assertTrue(stiff.progress > plain.progress)
    }

    @Test fun reopenWhileClosingContinuesFromTheCurrentValue() {
        val m = SheetMotion(1f); m.close(); m.frames(5)
        val at = m.progress
        m.open(); m.step(frame)
        assertTrue("jumped from $at to ${m.progress}", abs(m.progress - at) < 0.2f)
        m.frames(120)
        assertEquals(1f, m.progress, 0f)
    }
}
