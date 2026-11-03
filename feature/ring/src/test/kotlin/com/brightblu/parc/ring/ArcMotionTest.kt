package com.brightblu.parc.ring

import com.brightblu.parc.model.ArcFeel
import com.brightblu.parc.model.Bounce
import com.brightblu.parc.model.SpinLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ArcMotionTest {
    private val frame = 1f / 60f
    private val balanced = ArcFeel()

    private fun grabbed(offset: Float = 0f) = ArcMotion.down(ArcMotion.rest(offset))

    private fun released(velocity: Float, feel: ArcFeel = balanced, scale: Float = 1f, moved: Float = 0.2f) =
        ArcMotion.release(ArcMotion.drag(grabbed(), moved).state, velocity, feel, scale).state

    @Test fun dragFollowsTheFingerOneToOne() {
        var s = grabbed(2f)
        for (d in listOf(0.1f, -0.3f, 1.7f)) s = ArcMotion.drag(s, d).state
        assertEquals(2f + 0.1f - 0.3f + 1.7f, s.offset, 1e-5f)
    }

    @Test fun settleAcrossThreeSlotsGivesThreeTicksAndAtMostOnePerFrame() {
        for (bounce in Bounce.entries) {
            val feel = ArcFeel(bounce = bounce)
            var s = ArcMotion.release(ArcMotion.drag(grabbed(), 2.6f).state, 0f, feel, 1f).state
            // Drag ended on slot 3 (2.6 rounds to 3), so move the start back to 0 for a clean 3 slot settle.
            s = s.copy(offset = 0f, from = 0f, tickSlot = 0, target = 3, startSlot = 0)
            var ticks = 0
            var frames = 0
            while (s.phase == ArcPhase.Settling && frames < 600) {
                val step = ArcMotion.step(s, frame)
                if (step.tick) ticks++
                s = step.state
                frames++
            }
            assertEquals("bounce $bounce", ArcPhase.Rest, s.phase)
            assertEquals("bounce $bounce", 3, ticks)
            assertEquals(3f, s.offset, 0f)
        }
    }

    @Test fun settleLandsOnAWholeSlotWithinOneSecond() {
        for (bounce in Bounce.entries) {
            var s = released(14f, ArcFeel(bounce = bounce))
            var t = 0f
            while (s.phase == ArcPhase.Settling && t < 3f) { s = ArcMotion.step(s, frame).state; t += frame }
            assertEquals(ArcPhase.Rest, s.phase)
            assertEquals(s.offset, Math.rint(s.offset.toDouble()).toFloat(), 0f)
            assertTrue("settled in $t s", t < 1.2f)
        }
    }

    @Test fun finalFrameBeforeTheSnapIsWithinTolerance() {
        var s = released(10f)
        var previous = s
        while (s.phase == ArcPhase.Settling) { previous = s; s = ArcMotion.step(s, 0.0005f).state }
        assertEquals(s.target.toFloat(), previous.offset, 0.002f)
    }

    @Test fun catchKeepsOffsetAndVelocityThenReleaseCarriesOn() {
        var s = released(12f)
        repeat(6) { s = ArcMotion.step(s, frame).state }
        assertEquals(ArcPhase.Settling, s.phase)
        val caught = ArcMotion.down(s)
        assertEquals(s.offset, caught.offset, 0f)
        assertEquals(s.velocity, caught.velocity, 0f)
        assertEquals(ArcPhase.Dragging, caught.phase)
        // Lifting without moving hands the caught velocity back; the arc keeps its limit.
        val again = ArcMotion.release(caught, 99f, balanced, 1f).state
        assertEquals(caught.velocity, again.velocity, 1e-4f)
        assertTrue(abs(again.target - caught.startSlot) <= SpinLimit.Balanced.slots)
    }

    @Test fun animatorScaleZeroJumpsToTheTarget() {
        val s = released(14f, scale = 0f)
        assertEquals(ArcPhase.Rest, s.phase)
        assertEquals(s.target.toFloat(), s.offset, 0f)
    }

    @Test fun maximumFlickTravelsExactlyTheArcReleaseTable() {
        val expected = mapOf(SpinLimit.Tight to 1, SpinLimit.Balanced to 3, SpinLimit.Free to 5)
        for ((limit, slots) in expected) {
            val s = released(1000f, ArcFeel(spinLimit = limit), moved = 0f)
            assertEquals("$limit", slots, s.target - s.startSlot)
        }
    }

    @Test fun anIdleTouchOnRestingArcDoesNotMarkItDirty() {
        val s = ArcMotion.release(grabbed(3f), 0f, balanced, 1f).state
        assertEquals(ArcPhase.Rest, s.phase)
        assertEquals(false, s.dirty)
        assertEquals(3f, s.offset, 0f)
    }
}
