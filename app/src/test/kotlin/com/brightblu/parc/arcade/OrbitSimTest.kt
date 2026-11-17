package com.brightblu.parc.arcade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T200: the deterministic Orbit simulation: collisions, hops, scoring, the difficulty ramp and repeatable runs. */
class OrbitSimTest {
    private fun running(seed: Long = 7L) = OrbitSim.begin(OrbitSim.fresh(seed))

    /** A copy of [s] with some fields replaced, for building exact situations. */
    private fun with(s: OrbitState, ring: Int = s.ring, local: Float = s.local, barrierRing: IntArray = s.barrierRing,
        barrierLocal: FloatArray = s.barrierLocal, barrierDrift: FloatArray = s.barrierDrift, lightRing: IntArray = s.lightRing,
        lightLocal: FloatArray = s.lightLocal, phases: FloatArray = s.phases, tick: Int = s.tick, cooldown: Int = s.hopCooldown,
        nextBarrierTick: Int = s.nextBarrierTick) =
        OrbitState(s.phase, tick, s.rng, phases, ring, local, s.hopFrom, s.hopTicks, cooldown, s.score, barrierRing, barrierLocal, barrierDrift,
            lightRing, lightLocal, nextBarrierTick, OrbitRules.EventNone)

    /** A quiet board: no barriers, lights far from the player. Barriers can be added per test. */
    private fun board(ring: Int = 1, local: Float = 0f): OrbitState = with(running(), ring = ring, local = local,
        barrierRing = IntArray(0), barrierLocal = FloatArray(0), barrierDrift = FloatArray(0),
        lightRing = intArrayOf(0, 0), lightLocal = floatArrayOf(180f, 270f), nextBarrierTick = Int.MAX_VALUE)

    @Test fun aFreshRunWaitsForTheTapAndDoesNothingUntilThen() {
        val fresh = OrbitSim.fresh(1L)
        assertEquals(OrbitPhase.Ready, fresh.phase)
        assertTrue(OrbitSim.step(fresh, OrbitInput(steer = 1)) === fresh)
        assertEquals(OrbitPhase.Running, OrbitSim.begin(fresh).phase)
        assertTrue(OrbitSim.begin(OrbitSim.begin(fresh)).tick == 0)
    }

    @Test fun theStartingBoardIsFairAndHasLights() {
        for (seed in 0L..200L) {
            val s = OrbitSim.fresh(seed)
            assertEquals(OrbitRules.Rings, s.barrierCount)
            for (i in 0 until s.barrierCount) {
                if (s.barrierRing[i] == s.ring) assertTrue("seed $seed", OrbitSim.distance(s.local, s.barrierLocal[i]) > 60f)
            }
            for (slot in 0 until OrbitRules.LightSlots) {
                assertTrue(s.lightRing[slot] in 0 until OrbitRules.Rings)
                for (i in 0 until s.barrierCount) if (s.barrierRing[i] == s.lightRing[slot])
                    assertTrue(OrbitSim.distance(s.lightLocal[slot], s.barrierLocal[i]) > OrbitRules.BarrierHalf)
            }
        }
    }

    @Test fun steeringMovesAlongTheRingAndTheRingCarriesThePlayer() {
        val s = board()
        val one = OrbitSim.step(s, OrbitInput(steer = 1))
        assertEquals(OrbitSim.wrap(s.local + OrbitRules.SteerSpeed * OrbitRules.Dt), one.local, 1e-4f)
        val back = OrbitSim.step(s, OrbitInput(steer = -1))
        assertEquals(OrbitSim.wrap(s.local - OrbitRules.SteerSpeed * OrbitRules.Dt), back.local, 1e-4f)
        // Without steering the player keeps its place in the ring's frame and turns with the ring on screen.
        val idle = OrbitSim.step(s, OrbitInput.None)
        assertEquals(s.local, idle.local, 0f)
        val expected = OrbitSim.wrap(s.phases[1] + OrbitRules.RingSpeed[1] * OrbitRules.Dt)
        assertEquals(OrbitSim.wrap(expected + s.local), idle.playerAngle(), 1e-3f)
    }

    @Test fun hoppingMovesOneRingKeepsThePlaceInTheSkyAndHasACooldown() {
        val s = board(ring = 1, local = 40f)
        val hopped = OrbitSim.step(s, OrbitInput(hop = 1))
        assertEquals(2, hopped.ring)
        assertEquals(1, hopped.hopFrom)
        // The angle on screen did not jump (apart from the one tick the rings turned).
        val before = OrbitSim.wrap(hopped.phases[1] + 40f)
        assertEquals(before, hopped.playerAngle(), 1e-3f)
        // A second hop at once is refused until the cooldown ends.
        val again = OrbitSim.step(hopped, OrbitInput(hop = -1))
        assertEquals(2, again.ring)
        var t = hopped
        repeat(OrbitRules.HopCooldownTicks) { t = OrbitSim.step(t, OrbitInput.None) }
        assertEquals(1, OrbitSim.step(t, OrbitInput(hop = -1)).ring)
    }

    @Test fun thereIsNoRingBeyondTheEnds() {
        assertEquals(2, OrbitSim.step(board(ring = 2), OrbitInput(hop = 1)).ring)
        assertEquals(0, OrbitSim.step(board(ring = 0), OrbitInput(hop = -1)).ring)
    }

    @Test fun steeringIntoABarrierEndsTheRun() {
        // A barrier 40 degrees ahead on the player's ring; steer into it.
        var s = with(board(local = 0f), barrierRing = intArrayOf(1), barrierLocal = floatArrayOf(40f), barrierDrift = floatArrayOf(0f))
        var ended = false
        repeat(60) { if (!ended) { s = OrbitSim.step(s, OrbitInput(steer = 1)); ended = s.phase == OrbitPhase.Over } }
        assertTrue(ended)
        assertEquals(OrbitRules.EventHit, s.event)
        // Over is final: nothing moves any more.
        assertTrue(OrbitSim.step(s, OrbitInput(steer = 1)) === s)
    }

    @Test fun aBarrierThatDriftsIntoAnIdlePlayerEndsTheRun() {
        var s = with(board(local = 0f), barrierRing = intArrayOf(1), barrierLocal = floatArrayOf(60f), barrierDrift = floatArrayOf(-20f))
        var ticks = 0
        while (s.phase == OrbitPhase.Running && ticks < 600) { s = OrbitSim.step(s, OrbitInput.None); ticks++ }
        assertEquals(OrbitPhase.Over, s.phase)
        assertTrue(ticks in 60..200)
    }

    @Test fun aBarrierOnAnotherRingIsSafeUntilYouHopIntoIt() {
        val s = with(board(ring = 1, local = 0f), barrierRing = intArrayOf(2), barrierLocal = floatArrayOf(5f), barrierDrift = floatArrayOf(0f),
            phases = floatArrayOf(0f, 0f, 0f))
        // Idle on ring 1: nothing happens.
        var idle = s
        repeat(30) { idle = OrbitSim.step(idle, OrbitInput.None) }
        assertEquals(OrbitPhase.Running, idle.phase)
        // Hop into ring 2 where the barrier sits near the player's place in the sky: the run ends on arrival.
        assertEquals(OrbitPhase.Over, OrbitSim.step(s, OrbitInput(hop = 1)).phase)
        // Hop into a gap instead: fine.
        val gap = with(s, barrierLocal = floatArrayOf(180f))
        assertEquals(OrbitPhase.Running, OrbitSim.step(gap, OrbitInput(hop = 1)).phase)
    }

    @Test fun collectingALightScoresOnceAndPlacesANewOne() {
        val s = with(board(ring = 1, local = 100f), lightRing = intArrayOf(1, 0), lightLocal = floatArrayOf(100f, 200f))
        val n = OrbitSim.step(s, OrbitInput.None)
        assertEquals(1, n.score)
        assertEquals(OrbitRules.EventCollected, n.event)
        // The collected light moved elsewhere, not back under the player.
        assertTrue(n.lightRing[0] != 1 || OrbitSim.distance(n.lightLocal[0], n.local) >= OrbitRules.LightFromPlayer)
        // The next tick does not score again.
        assertEquals(1, OrbitSim.step(n, OrbitInput.None).score)
    }

    @Test fun aLightOnAnotherRingIsNotCollected() {
        val s = with(board(ring = 1, local = 100f), lightRing = intArrayOf(0, 2), lightLocal = floatArrayOf(100f, 100f))
        assertEquals(0, OrbitSim.step(s, OrbitInput.None).score)
    }

    @Test fun newLightsNeverAppearInsideABarrier() {
        for (seed in 0L..300L) {
            val base = OrbitSim.fresh(seed)
            // The player sits on its own light (ring 1): collecting it places a new one among the three starting barriers.
            val s = with(OrbitSim.begin(base), lightRing = intArrayOf(base.ring, 0), lightLocal = floatArrayOf(base.local, base.lightLocal[1]))
            val n = OrbitSim.step(s, OrbitInput.None)
            if (n.phase != OrbitPhase.Running || n.score != 1) continue
            for (i in 0 until n.barrierCount) if (n.barrierRing[i] == n.lightRing[0])
                assertTrue("seed $seed", OrbitSim.distance(n.lightLocal[0], n.barrierLocal[i]) > OrbitRules.BarrierHalf)
        }
    }

    @Test fun theRingsSpeedUpSlowlyAndStopAtTheCap() {
        assertEquals(1f, OrbitRules.speedMultiplier(0), 0f)
        var previous = 0f
        for (tick in 0..20_000 step 100) {
            val m = OrbitRules.speedMultiplier(tick)
            assertTrue(m >= previous); previous = m
            assertTrue(m <= OrbitRules.SpeedMax)
        }
        assertEquals(OrbitRules.SpeedMax, OrbitRules.speedMultiplier(1_000_000), 0f)
        // Slow: after ten seconds under 15 percent faster; after a minute under 80 percent.
        assertTrue(OrbitRules.speedMultiplier(600) < 1.15f)
        assertTrue(OrbitRules.speedMultiplier(3600) < 1.8f)
        // And the real step turns a ring further per tick later in a run.
        val early = board()
        val late = with(early, tick = 6000)
        fun turned(s: OrbitState): Float = OrbitSim.wrap(OrbitSim.step(s, OrbitInput.None).phases[2] - s.phases[2])
        assertTrue(turned(late) > turned(early))
    }

    @Test fun barriersMultiplyOverTimeUpToTheCap() {
        // Drive the spawner directly. When it waits for the player to leave the spot, the player moves half a turn round.
        var s = with(board(ring = 1, local = 90f), nextBarrierTick = 1)
        s = with(OrbitSim.step(s, OrbitInput.None), nextBarrierTick = 1)
        var counts = ArrayList<Int>()
        var rounds = 0
        while (s.barrierCount < OrbitRules.MaxBarriers && rounds < 60) {
            val grown = OrbitSim.addBarrier(with(s, nextBarrierTick = 1))
            s = if (grown.barrierCount == s.barrierCount) with(grown, local = OrbitSim.wrap(grown.local + 180f)) else grown
            counts.add(s.barrierCount)
            rounds++
        }
        assertEquals(OrbitRules.MaxBarriers, s.barrierCount)
        assertEquals(counts, counts.sorted())
        for (k in 0 until OrbitRules.Rings) assertEquals(OrbitRules.MaxBarriersPerRing, s.barriersOn(k))
        // At the cap the spawner switches itself off.
        assertEquals(Int.MAX_VALUE, OrbitSim.addBarrier(s).nextBarrierTick)
    }

    @Test fun theSpawnerRunsOnTheClockInARealRun() {
        // The first barrier after the three starting ones arrives at the 15 s mark (a player far from the spot, no drift needed).
        var s = with(board(ring = 1, local = 90f), nextBarrierTick = OrbitRules.BarrierEveryTicks,
            barrierRing = intArrayOf(0, 1, 2), barrierLocal = floatArrayOf(0f, 270f, 0f), barrierDrift = floatArrayOf(0f, 0f, 0f))
        var ticks = 0
        while (s.barrierCount == 3 && ticks < OrbitRules.BarrierEveryTicks + 5) { s = OrbitSim.step(s, OrbitInput.None); ticks++ }
        assertEquals(OrbitRules.BarrierEveryTicks, ticks)
        assertEquals(4, s.barrierCount)
    }

    @Test fun aNewBarrierWaitsWhileThePlayerIsRightWhereItWouldGo() {
        // One ring (the player's) with a single barrier: the largest gap's middle is opposite it; park the player there.
        val base = with(board(ring = 1, local = 180f), barrierRing = intArrayOf(0, 1, 2, 2), barrierLocal = floatArrayOf(10f, 0f, 10f, 190f),
            barrierDrift = floatArrayOf(0f, 0f, 0f, 0f), nextBarrierTick = 1)
        val n = OrbitSim.step(base, OrbitInput.None)
        // Ring 0 has the fewest barriers (one) and its gap is far from the player (ring 1): the barrier is placed on ring 0.
        assertEquals(5, n.barrierCount)
        // Now make ring 1 the one with the fewest and the player sit in its gap's middle.
        val parked = with(board(ring = 1, local = 180f), barrierRing = intArrayOf(0, 0, 1, 2, 2), barrierLocal = floatArrayOf(10f, 190f, 0f, 10f, 190f),
            barrierDrift = FloatArray(5), nextBarrierTick = 1)
        val waited = OrbitSim.step(parked, OrbitInput.None)
        assertEquals(5, waited.barrierCount)
        assertEquals(1, waited.nextBarrierTick) // not placed yet: it tries again next tick
    }

    @Test fun theSameSeedAndInputsGiveTheSameRun() {
        fun play(seed: Long): List<Long> {
            var s = running(seed)
            val out = ArrayList<Long>()
            for (t in 0 until 1500) {
                val input = OrbitInput(steer = when ((t / 37) % 3) { 0 -> 1; 1 -> 0; else -> -1 }, hop = if (t % 211 == 0) (if ((t / 211) % 2 == 0) 1 else -1) else 0)
                s = OrbitSim.step(s, input)
                out.add(s.signature())
                if (s.phase == OrbitPhase.Over) break
            }
            return out
        }
        assertEquals(play(42L), play(42L))
        assertNotEquals(play(42L), play(43L))
        assertEquals(OrbitSim.fresh(5L).signature(), OrbitSim.fresh(5L).signature())
        assertNotEquals(OrbitSim.fresh(5L).signature(), OrbitSim.fresh(6L).signature())
    }

    @Test fun aStepNeverChangesTheStateItWasGiven() {
        val s = board()
        val before = s.signature()
        OrbitSim.step(s, OrbitInput(steer = 1, hop = 1))
        assertEquals(before, s.signature())
    }

    @Test fun aBotThatCollectsLightsScoresAndRunsAreFiniteAndSafe() {
        // A simple pilot: stay on one ring, steer toward the nearest light on it. Many seeds: no exception, angles stay in range.
        var scored = 0
        for (seed in 0L..40L) {
            var s = running(seed)
            var ticks = 0
            while (s.phase == OrbitPhase.Running && ticks < 3600) {
                var steer = 0
                var best = 361f
                for (slot in 0 until OrbitRules.LightSlots) if (s.lightRing[slot] == s.ring) {
                    val d = OrbitSim.distance(s.local, s.lightLocal[slot])
                    if (d < best) { best = d; steer = if (OrbitSim.wrap(s.lightLocal[slot] - s.local) < 180f) 1 else -1 }
                }
                s = OrbitSim.step(s, OrbitInput(steer = steer))
                assertTrue(s.local in 0f..360f && s.phases.all { it in 0f..360f })
                ticks++
            }
            scored += s.score
        }
        assertTrue(scored > 0)
    }

    @Test fun theStickReadsSteeringAndOneHopPerPush() {
        val stick = OrbitStick(20f)
        assertEquals(OrbitInput.None, stick.read(5f, 5f, true))
        assertEquals(OrbitInput(steer = 1), stick.read(40f, 10f, true))
        assertEquals(OrbitInput(steer = -1), stick.read(-40f, -10f, true))
        // Up hops outward once; holding it does not hop again until the stick returns to the middle.
        assertEquals(OrbitInput(hop = 1), stick.read(0f, -40f, true))
        assertEquals(OrbitInput.None, stick.read(0f, -40f, true))
        assertEquals(OrbitInput.None, stick.read(0f, 0f, true))
        assertEquals(OrbitInput(hop = -1), stick.read(0f, 40f, true))
        // A lifted finger and bad numbers read as nothing.
        assertEquals(OrbitInput.None, stick.read(40f, 0f, false))
        assertEquals(OrbitInput.None, stick.read(Float.NaN, 0f, true))
        assertEquals(OrbitInput(hop = 1), stick.read(0f, -40f, true))
        assertFalse(stick.read(30f, 30f, true).hop != 0) // a diagonal is a steer (the larger axis wins; a tie steers)
    }
}
