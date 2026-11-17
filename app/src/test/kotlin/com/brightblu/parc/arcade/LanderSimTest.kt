package com.brightblu.parc.arcade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T211: Lander's pure rules: landing limit, fuel, crash, seeded levels and deterministic fixed-step runs. */
class LanderSimTest {
    private fun running(state: LanderState): LanderState = state.copy(phase = LanderPhase.Running, event = LanderRules.EventNone)

    private fun abovePad(impact: Float, fuel: Float = 50f): LanderState = LanderState(
        phase = LanderPhase.Running, tick = 0, level = 1, seed = 1L, rng = 1L,
        x = 0.5f, y = LanderRules.PadY + LanderRules.ShipRadius + 0.001f, vx = 0f, vy = -impact,
        fuel = fuel, tilt = 0, padCentre = 0.5f, padWidth = LanderRules.PadWidthStart, padY = LanderRules.PadY,
        wind = 0f, terrainAmplitude = 0.01f, terrainFrequency = 1.5f, terrainPhase = 0f,
        landingSpeed = 0f, score = 0, event = LanderRules.EventNone,
    )

    @Test fun aSlowTouchdownLandsAndScoresFuelPlusSoftness() {
        val landed = LanderSim.step(abovePad(impact = 0.10f), LanderInput.None)
        assertEquals(LanderPhase.Landed, landed.phase)
        assertEquals(LanderRules.EventLanded, landed.event)
        assertTrue(landed.landingSpeed <= LanderRules.MaxLandingSpeed)
        assertEquals(LanderRules.score(50f, landed.landingSpeed), landed.score)
        assertTrue(landed.score > 50)
        assertEquals(LanderRules.PadY + LanderRules.ShipRadius, landed.y, 0.0001f)
    }

    @Test fun touchingDownAtOrUnderTheLimitLandsAndJustOverItCrashes() {
        val gentle = LanderSim.step(abovePad(impact = LanderRules.MaxLandingSpeed - 0.02f), LanderInput.None)
        assertEquals(LanderPhase.Landed, gentle.phase)
        assertTrue(gentle.landingSpeed <= LanderRules.MaxLandingSpeed)

        val tooFast = LanderSim.step(abovePad(impact = LanderRules.MaxLandingSpeed + 0.02f), LanderInput.None)
        assertEquals(LanderPhase.Crashed, tooFast.phase)
        assertEquals(LanderRules.EventCrashed, tooFast.event)
        assertTrue(tooFast.landingSpeed > LanderRules.MaxLandingSpeed)
    }

    @Test fun touchingTheGroundAwayFromThePadIsACrash() {
        val low = LanderState(
            phase = LanderPhase.Running, tick = 0, level = 1, seed = 2L, rng = 2L,
            x = 0.02f, y = 0.08f, vx = 0f, vy = -0.10f, fuel = 50f, tilt = 0,
            padCentre = 0.50f, padWidth = LanderRules.PadWidthStart, padY = LanderRules.PadY,
            wind = 0f, terrainAmplitude = 0.01f, terrainFrequency = 1.5f, terrainPhase = 0f,
            landingSpeed = 0f, score = 0, event = LanderRules.EventNone,
        )
        val ground = LanderSim.groundHeight(low, low.x)
        val crashed = LanderSim.step(low.copy(y = ground + LanderRules.ShipRadius + 0.001f), LanderInput.None)
        assertEquals(LanderPhase.Crashed, crashed.phase)
        assertEquals(LanderRules.EventCrashed, crashed.event)
    }

    @Test fun fuelBurnsOnlyWhileThrustingAndAnEmptyTankGivesNoLift() {
        val before = running(LanderSim.fresh(1L)).copy(x = 0.5f, y = 0.5f, vx = 0f, vy = 0f)
        val thrusting = LanderSim.step(before, LanderInput(thrust = true))
        assertEquals(LanderRules.MaxFuel - LanderRules.FuelBurnPerSecond * LanderRules.Dt, thrusting.fuel, 0.0001f)
        val coasting = LanderSim.step(before, LanderInput.None)
        assertEquals(LanderRules.MaxFuel, coasting.fuel, 0f)
        // A thrust tick is slower to fall than a coasting tick.
        assertTrue(thrusting.vy > coasting.vy)

        val empty = LanderSim.step(before.copy(fuel = 0f), LanderInput(thrust = true))
        assertEquals(0f, empty.fuel, 0f)
        assertEquals(coasting.vy, empty.vy, 0.000001f)
    }

    @Test fun theWallsStopTheShipInsteadOfLettingItLeaveTheField() {
        val before = running(LanderSim.fresh(3L)).copy(x = 0.02f, y = 0.5f, vx = -0.8f, vy = 0f)
        val next = LanderSim.step(before, LanderInput.None)
        assertTrue(next.x >= LanderRules.ShipRadius)
        assertEquals(0f, next.vx, 0f)
    }

    @Test fun theSameSeedAndInputsGiveTheSameRun() {
        val a = LanderSim.fresh(77L, 2)
        val b = LanderSim.fresh(77L, 2)
        assertEquals(a.signature(), b.signature())
        var sa = a
        var sb = b
        repeat(900) { i ->
            val input = LanderInput(tilt = when ((i / 40) % 3) { 1 -> 1; 2 -> -1; else -> 0 }, thrust = i % 11 == 0)
            sa = LanderSim.step(sa, input)
            sb = LanderSim.step(sb, input)
            assertEquals(sa.signature(), sb.signature())
        }
        var other = LanderSim.fresh(78L, 2)
        repeat(900) { i ->
            other = LanderSim.step(other, LanderInput(tilt = when ((i / 40) % 3) { 1 -> 1; 2 -> -1; else -> 0 }, thrust = i % 11 == 0))
        }
        assertNotEquals(sa.signature(), other.signature())
    }

    @Test fun seedsAndLevelsChangeTheTerrainTheWindAndTheStartPlace() {
        val centres = (0L..5L).map { LanderSim.fresh(it).padCentre }.toSet()
        val winds = (0L..5L).map { LanderSim.fresh(it).wind }.toSet()
        val waves = (0L..5L).map { LanderSim.fresh(it).terrainPhase }.toSet()
        assertTrue(centres.size > 1)
        assertTrue(winds.size > 1)
        assertTrue(waves.size > 1)
    }

    @Test fun levelsNarrowThePadAndRaiseTheWindAndTerrainUpToTheirCaps() {
        assertTrue(LanderRules.padWidth(2) < LanderRules.padWidth(1))
        assertEquals(LanderRules.PadWidthMin, LanderRules.padWidth(100), 0f)
        assertTrue(LanderRules.windMagnitude(2) > LanderRules.windMagnitude(1))
        assertEquals(LanderRules.WindMax, LanderRules.windMagnitude(100), 0f)
        assertTrue(LanderRules.terrainAmplitude(2) >= LanderRules.terrainAmplitude(1))
        assertEquals(LanderRules.TerrainAmplitudeMax, LanderRules.terrainAmplitude(100), 0f)
        assertTrue(LanderRules.terrainFrequency(2) > LanderRules.terrainFrequency(1))
    }

    @Test fun nextLevelStartsAFreshLevelWithMoreDifficultyFromTheSameRun() {
        val landed = abovePad(impact = 0.05f).copy(phase = LanderPhase.Landed, score = 120)
        val next = LanderSim.nextLevel(landed)
        assertEquals(2, next.level)
        assertEquals(LanderPhase.Ready, next.phase)
        assertEquals(LanderRules.MaxFuel, next.fuel, 0f)
        assertEquals(0, next.score)
        val running = running(LanderSim.fresh(9L))
        assertSame(running, LanderSim.nextLevel(running))
    }

    @Test fun theStickTiltsAndThrustsTogetherAndIgnoresTheDeadZone() {
        val stick = LanderStick(deadDp = 4f)
        assertEquals(LanderInput(0, false), stick.read(3f, -3f, active = true))
        assertEquals(LanderInput(1, true), stick.read(10f, -10f, active = true))
        assertEquals(LanderInput(-1, false), stick.read(-10f, 1f, active = true))
        assertEquals(LanderInput(0, true), stick.read(0f, -10f, active = true))
        assertEquals(LanderInput.None, stick.read(10f, -10f, active = false))
        assertEquals(LanderInput.None, stick.read(Float.NaN, 0f, active = true))
    }
}
