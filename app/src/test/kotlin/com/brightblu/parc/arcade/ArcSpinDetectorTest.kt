package com.brightblu.parc.arcade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T200: the arc-turn shortcut: three turns one way, one turn back, in 20 s, with no pause over 1.5 s. */
class ArcSpinDetectorTest {
    // Float moves add up with rounding, so the back turn in these tests is a hair over one full turn.
    private val step = 0.1f // a tenth of a turn per move
    private val every = 100L // ms between moves

    /** Feeds [turns] full turns in small moves; returns the time after the last one and whether the gesture completed. */
    private fun turn(d: ArcSpinDetector, arc: String, turns: Float, from: Long, sign: Int = 1, gap: Long = every): Pair<Long, Boolean> {
        var t = from
        var done = false
        var left = turns
        while (left > 0.0001f) {
            val s = minOf(step, left)
            t += gap
            if (d.feed(arc, sign * s, t)) return t to true // the gesture is over; later moves would be a new attempt
            left -= s
        }
        return t to done
    }

    @Test fun threeTurnsThenOneBackUnlocks() {
        val d = ArcSpinDetector()
        val (t1, a) = turn(d, "arc0", 3.1f, 1000L)
        assertFalse(a)
        val (_, b) = turn(d, "arc0", 1.05f, t1, sign = -1)
        assertTrue(b)
    }

    @Test fun theOtherDirectionWorksToo() {
        val d = ArcSpinDetector()
        val (t1, _) = turn(d, "arc0", 3.1f, 1000L, sign = -1)
        assertTrue(turn(d, "arc0", 1.05f, t1, sign = 1).second)
    }

    @Test fun fewerThanThreeTurnsThenBackDoesNot() {
        val d = ArcSpinDetector()
        val (t1, _) = turn(d, "arc0", 2.5f, 1000L)
        assertFalse(turn(d, "arc0", 1.5f, t1, sign = -1).second)
    }

    @Test fun wrongOrderOneTurnThenThreeBackDoesNot() {
        val d = ArcSpinDetector()
        val (t1, _) = turn(d, "arc0", 1.0f, 1000L)
        assertFalse(turn(d, "arc0", 3.0f, t1, sign = -1).second)
    }

    @Test fun threeTurnsAndNoTurnBackDoesNot() {
        val d = ArcSpinDetector()
        assertFalse(turn(d, "arc0", 4.0f, 1000L).second)
    }

    @Test fun smallWobbleDuringTheTurnsIsIgnored() {
        val d = ArcSpinDetector()
        var t = 1000L
        var done = false
        // Forward with a small step back after every ten moves (jitter well under a quarter turn): 3.84 turns net.
        repeat(4) {
            repeat(10) { t += every; if (d.feed("a", 0.1f, t)) done = true }
            t += every; if (d.feed("a", -0.04f, t)) done = true
        }
        assertFalse(done)
        // Then one clean turn back (a little more, to clear the 3 to 3.84 overshoot rule: the peak is 3.84 turns out).
        assertTrue(turn(d, "a", 1.1f, t, sign = -1).second)
    }

    @Test fun aPauseOverOneAndAHalfSecondsStartsOver() {
        val d = ArcSpinDetector()
        val (t1, _) = turn(d, "arc0", 3.1f, 1000L)
        // The finger lifts for 2 s, then turns back one full turn: the attempt had been forgotten.
        assertFalse(turn(d, "arc0", 1.05f, t1 + 2000L, sign = -1).second)
    }

    @Test fun aPauseUnderOneAndAHalfSecondsKeepsGoing() {
        val d = ArcSpinDetector()
        val (t1, _) = turn(d, "arc0", 3.1f, 1000L)
        assertTrue(turn(d, "arc0", 1.05f, t1 + 1400L, sign = -1).second)
    }

    @Test fun tooSlowOverTwentySecondsDoesNot() {
        val d = ArcSpinDetector()
        // 4.1 turns in 41 moves, 600 ms apart: more than 20 s in all.
        val (t1, _) = turn(d, "arc0", 3.1f, 1000L, gap = 600L)
        assertFalse(turn(d, "arc0", 1.05f, t1, sign = -1, gap = 600L).second)
    }

    @Test fun justFastEnoughDoes() {
        val d = ArcSpinDetector()
        // 41 moves, 400 ms apart: 16.4 s.
        val (t1, a) = turn(d, "arc0", 3.1f, 1000L, gap = 400L)
        assertFalse(a)
        assertTrue(turn(d, "arc0", 1.05f, t1, sign = -1, gap = 400L).second)
    }

    @Test fun switchingToAnotherArcStartsOver() {
        val d = ArcSpinDetector()
        val (t1, _) = turn(d, "arc0", 3.1f, 1000L)
        assertFalse(turn(d, "arc1", 1.0f, t1, sign = -1).second)
    }

    @Test fun nonFiniteAndZeroMovesAreIgnored() {
        val d = ArcSpinDetector()
        assertFalse(d.feed("a", Float.NaN, 100L))
        assertFalse(d.feed("a", 0f, 200L))
        assertFalse(d.feed("a", Float.POSITIVE_INFINITY, 300L))
    }

    @Test fun itCanBeRepeatedAfterSucceeding() {
        val d = ArcSpinDetector()
        val (t1, _) = turn(d, "a", 3.1f, 1000L)
        val (t2, first) = turn(d, "a", 1.05f, t1, sign = -1)
        assertTrue(first)
        val (t3, _) = turn(d, "a", 3.1f, t2 + 100L)
        assertTrue(turn(d, "a", 1.05f, t3, sign = -1).second)
    }

    @Test fun anIdleDetectorNeverFires() {
        assertEquals(false, ArcSpinDetector().feed("a", 0.01f, 10L))
    }
}
