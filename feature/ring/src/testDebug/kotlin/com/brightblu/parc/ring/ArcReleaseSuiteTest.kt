package com.brightblu.parc.ring

import com.brightblu.parc.model.Bounce
import com.brightblu.parc.model.SpinLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Full suite for [ArcRelease]: the spin limit is never exceeded and the arc always lands on a slot. */
class ArcReleaseSuiteTest {
    private val limits = listOf(2, 4, 8)

    @Test fun gridNeverExceedsLimitIsRepeatableAndMonotonicInVelocity() {
        var combinations = 0L
        for (limit in limits) {
            for (start in -50..50) {
                for (oi in 0..96) {
                    val offset = start - 12f + oi * 0.25f
                    var previous = Int.MIN_VALUE
                    for (vi in 0..800) {
                        val velocity = -200f + vi * 0.5f
                        val target = ArcRelease.target(start, offset, velocity, limit)
                        assertTrue(
                            "limit=$limit start=$start offset=$offset v=$velocity target=$target",
                            abs(target - start) <= limit,
                        )
                        assertEquals(target, ArcRelease.target(start, offset, velocity, limit))
                        assertTrue(
                            "not monotonic: limit=$limit start=$start offset=$offset v=$velocity",
                            target >= previous,
                        )
                        previous = target
                        combinations++
                    }
                }
            }
        }
        assertEquals(3L * 101 * 97 * 801, combinations)
        assertTrue(combinations >= 100_000)
    }

    @Test fun clampVelocityKeepsSignAndNeverExceedsCap() {
        for (limit in limits) {
            val cap = limit * 3.5f
            for (vi in 0..800) {
                val v = -200f + vi * 0.5f
                val c = ArcRelease.clampVelocity(v, limit)
                assertTrue("v=$v limit=$limit c=$c", abs(c) <= cap)
                assertEquals(sign(v), sign(c))
                if (abs(v) <= cap) assertEquals(v, c, 0f)
            }
        }
    }

    private fun sign(x: Float): Int = if (x > 0f) 1 else if (x < 0f) -1 else 0

    @Test fun zeroVelocityGivesNearestSlotHalfUpClamped() {
        for (limit in limits) {
            for (start in listOf(-7, 0, 13)) {
                for (oi in 0..96) {
                    val offset = start - 12f + oi * 0.25f
                    val nearest = Math.floor(offset + 0.5).toInt()
                    val expected = nearest.coerceIn(start - limit, start + limit)
                    assertEquals(
                        "limit=$limit start=$start offset=$offset",
                        expected,
                        ArcRelease.target(start, offset, 0f, limit),
                    )
                }
            }
        }
        assertEquals(3, ArcRelease.target(0, 2.5f, 0f, 8))
        assertEquals(-2, ArcRelease.target(0, -2.5f, 0f, 8))
    }

    @Test fun maximumVelocityTableAtRest() {
        val table = listOf(SpinLimit.Tight to 1, SpinLimit.Balanced to 3, SpinLimit.Free to 5)
        for ((spin, slots) in table) {
            for (start in listOf(-20, 0, 37)) {
                assertEquals(start + slots, ArcRelease.target(start, start.toFloat(), 1e9f, spin.slots))
                assertEquals(start - slots, ArcRelease.target(start, start.toFloat(), -1e9f, spin.slots))
            }
        }
    }

    @Test fun nonFiniteInputsStayInsideLimitAndDoNotThrow() {
        val bad = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val normal = listOf(0f, 1.5f, -3f, 1e9f, -1e9f)
        for (limit in limits) {
            for (start in listOf(-50, 0, 50)) {
                for (o in bad + normal) {
                    for (v in bad + normal) {
                        val target = ArcRelease.target(start, o, v, limit)
                        assertTrue(
                            "limit=$limit start=$start o=$o v=$v target=$target",
                            abs(target - start) <= limit,
                        )
                    }
                }
            }
        }
    }

    /** Closed-form underdamped step response toward 1 (unit mass, zero starting velocity). */
    private fun position(zeta: Double, tSeconds: Double): Double {
        val omega = sqrt(ArcRelease.SettleStiffness.toDouble())
        val omegaD = omega * sqrt(1 - zeta * zeta)
        val decay = exp(-zeta * omega * tSeconds)
        return 1 - decay * (cos(omegaD * tSeconds) + zeta * omega / omegaD * sin(omegaD * tSeconds))
    }

    /** Returns (settle time in ms after which the error stays under 0.001, peak overshoot fraction). */
    private fun measure(zeta: Double): Pair<Int, Double> {
        var settleMs = 0
        var peak = 0.0
        for (ms in 0..3000) {
            val x = position(zeta, ms / 1000.0)
            if (abs(1 - x) >= 0.001) settleMs = ms + 1
            if (x - 1 > peak) peak = x - 1
        }
        return settleMs to peak
    }

    @Test fun settleTimeAndOvershootMatchTheBounceFeels() {
        val expected = mapOf(
            Bounce.Calm to Triple(600, 0.0015, 0.01),
            Bounce.Balanced to Triple(600, 0.038, 0.01),
            Bounce.Playful to Triple(750, 0.126, 0.01),
        )
        for ((bounce, e) in expected) {
            val (settleMs, peak) = measure(bounce.dampingRatio.toDouble())
            assertTrue("$bounce settled at $settleMs ms, limit ${e.first}", settleMs <= e.first)
            assertEquals("$bounce overshoot", e.second, peak, e.third)
            // Cross-check the analytic overshoot formula exp(-pi*zeta/sqrt(1-zeta^2)).
            val z = bounce.dampingRatio.toDouble()
            assertEquals(exp(-PI * z / sqrt(1 - z * z)), peak, 0.0005)
        }
    }
}
