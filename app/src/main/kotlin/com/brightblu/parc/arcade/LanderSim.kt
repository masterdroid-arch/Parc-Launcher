package com.brightblu.parc.arcade

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * T211: Lander's rules, pure and deterministic. A fixed timestep, a seeded generator for each level's terrain and wind, and
 * `step(state, input) -> state`. No Android, no clock. The field is normalised: x runs left to right, y runs up from the
 * bottom, both 0..1. The ship falls under gravity, spends a limited fuel when it thrusts, and only scores when it lands on
 * the pad slowly enough. A successful landing scores the fuel left plus a softness bonus.
 */
internal object LanderRules {
    const val TicksPerSecond = 60
    const val Dt = 1f / TicksPerSecond

    /** Downward pull, in field heights per second squared. */
    const val Gravity = 0.20f
    /** Upward acceleration while thrusting. */
    const val ThrustAccel = 0.46f
    /** Sideways acceleration at full tilt. */
    const val SideAccel = 0.10f

    const val MaxFuel = 100f
    const val FuelBurnPerSecond = 20f

    /** The fastest total speed (field widths per second) at which the pad accepts the ship. */
    const val MaxLandingSpeed = 0.16f
    const val ShipRadius = 0.012f

    const val StartY = 0.75f
    const val PadY = 0.10f
    const val PadWidthStart = 0.26f
    const val PadWidthMin = 0.12f
    const val PadWidthShrink = 0.02f

    /** Ground wave below the pad; it never reaches the pad height. */
    const val GroundBase = 0.025f
    const val TerrainAmplitudeStart = 0.012f
    const val TerrainAmplitudeStep = 0.003f
    const val TerrainAmplitudeMax = 0.032f
    const val TerrainFrequencyStart = 1.6f
    const val TerrainFrequencyStep = 0.25f

    const val WindStart = 0.020f
    const val WindStep = 0.006f
    const val WindMax = 0.060f

    const val EventNone = 0
    const val EventLanded = 1
    const val EventCrashed = 2

    /** The pad narrows as levels rise, down to [PadWidthMin]. */
    fun padWidth(level: Int): Float =
        (PadWidthStart - (level.coerceAtLeast(1) - 1) * PadWidthShrink).coerceAtLeast(PadWidthMin)

    /** The strongest wind a level can have; rises with the level and stops at [WindMax]. */
    fun windMagnitude(level: Int): Float =
        (WindStart + (level.coerceAtLeast(1) - 1) * WindStep).coerceAtMost(WindMax)

    fun terrainAmplitude(level: Int): Float =
        (TerrainAmplitudeStart + (level.coerceAtLeast(1) - 1) * TerrainAmplitudeStep).coerceAtMost(TerrainAmplitudeMax)

    fun terrainFrequency(level: Int): Float = TerrainFrequencyStart + (level.coerceAtLeast(1) - 1) * TerrainFrequencyStep

    /** Fuel left plus softness: 0..100 fuel points plus 0..100 for touching down gently. */
    fun score(fuel: Float, impactSpeed: Float): Int {
        val fuelPoints = fuel.coerceIn(0f, MaxFuel).roundToInt()
        val softness = ((1f - (impactSpeed / MaxLandingSpeed).coerceIn(0f, 1f)) * 100f).roundToInt()
        return fuelPoints + softness
    }
}

internal enum class LanderPhase { Ready, Running, Landed, Crashed }

/** One tick of input: [tilt] -1 (left), 0 or +1 (right); [thrust] spends fuel to push up. */
internal data class LanderInput(val tilt: Int = 0, val thrust: Boolean = false) {
    companion object { val None = LanderInput() }
}

/**
 * The whole state of one level. Everything that shapes the level comes from [seed] and [level], and nothing in [step] uses
 * a clock or a random source, so two states with the same seed and the same inputs stay identical. [event] holds the
 * [LanderRules] flag of the step that ended the run.
 */
internal data class LanderState(
    val phase: LanderPhase,
    val tick: Int,
    val level: Int,
    val seed: Long,
    val rng: Long,
    val x: Float,
    val y: Float,
    val vx: Float,
    val vy: Float,
    val fuel: Float,
    val tilt: Int,
    val padCentre: Float,
    val padWidth: Float,
    val padY: Float,
    val wind: Float,
    val terrainAmplitude: Float,
    val terrainFrequency: Float,
    val terrainPhase: Float,
    val landingSpeed: Float,
    val score: Int,
    val event: Int,
) {
    /** Total speed in field widths per second. */
    val speed: Float get() = sqrt(vx * vx + vy * vy)

    /** A hash of everything that defines the state, used by the tests to compare two runs exactly. */
    fun signature(): Long {
        var h = -3750763034362895579L
        fun mix(v: Long) { h = (h xor v) * 1099511628211L }
        mix(phase.ordinal.toLong()); mix(tick.toLong()); mix(level.toLong()); mix(seed); mix(rng)
        mix(x.toRawBits().toLong()); mix(y.toRawBits().toLong()); mix(vx.toRawBits().toLong()); mix(vy.toRawBits().toLong())
        mix(fuel.toRawBits().toLong()); mix(tilt.toLong()); mix(padCentre.toRawBits().toLong()); mix(padWidth.toRawBits().toLong())
        mix(padY.toRawBits().toLong()); mix(wind.toRawBits().toLong()); mix(terrainAmplitude.toRawBits().toLong())
        mix(terrainFrequency.toRawBits().toLong()); mix(terrainPhase.toRawBits().toLong()); mix(landingSpeed.toRawBits().toLong())
        mix(score.toLong()); mix(event.toLong())
        return h
    }
}

/** A small splitmix generator: the whole generator state is one Long, kept in each level's state. */
internal object LanderRng {
    fun next(state: Long): Long {
        var z = state + -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }
    fun unit(state: Long): Float = ((state ushr 40).toInt() and 0xFFFFFF) / 16777216f
}

internal object LanderSim {
    /** A level waiting for the first tap. Terrain, wind, pad and start place all come from [seed] and [level]. */
    fun fresh(seed: Long, level: Int = 1): LanderState {
        val l = level.coerceAtLeast(1)
        var rng = LanderRng.next(seed xor (l.toLong() * 0x632BE59BD9B4E019L))
        fun unit(): Float { rng = LanderRng.next(rng); return LanderRng.unit(rng) }
        val padWidth = LanderRules.padWidth(l)
        val padCentre = 0.5f + (unit() - 0.5f) * 0.44f
        val wind = (unit() * 2f - 1f) * LanderRules.windMagnitude(l)
        val amplitude = LanderRules.terrainAmplitude(l)
        val frequency = LanderRules.terrainFrequency(l)
        val phase = unit()
        unit() // one more draw keeps the level's start place independent of the terrain wave
        val startX = (padCentre + (unit() - 0.5f) * 0.50f).coerceIn(LanderRules.ShipRadius * 2f, 1f - LanderRules.ShipRadius * 2f)
        return LanderState(LanderPhase.Ready, 0, l, seed, rng, startX, LanderRules.StartY, 0f, -0.015f, LanderRules.MaxFuel, 0,
            padCentre, padWidth, LanderRules.PadY, wind, amplitude, frequency, phase, 0f, 0, LanderRules.EventNone)
    }

    /** The first tap: Ready becomes Running. Any other phase is returned unchanged. */
    fun begin(state: LanderState): LanderState =
        if (state.phase != LanderPhase.Ready) state else state.copy(phase = LanderPhase.Running, event = LanderRules.EventNone)

    /** The next level after a landing, or the same state when the run has not landed. */
    fun nextLevel(state: LanderState): LanderState {
        if (state.phase != LanderPhase.Landed) return state
        val nextSeed = LanderRng.next(state.seed + state.level)
        return fresh(nextSeed, state.level + 1)
    }

    /** The ground height at [x] for this level's terrain. Always below the pad. */
    fun groundHeight(state: LanderState, x: Float): Float {
        val wave = 0.5f + 0.5f * sin((2f * PI.toFloat()) * (state.terrainFrequency * x + state.terrainPhase))
        return (LanderRules.GroundBase + state.terrainAmplitude * wave).coerceAtMost(LanderRules.PadY - 0.02f)
    }

    fun step(state: LanderState, input: LanderInput): LanderState {
        if (state.phase != LanderPhase.Running) return state
        val tilt = input.tilt.coerceIn(-1, 1)
        val thrusting = input.thrust && state.fuel > 0f
        val fuel = if (thrusting) (state.fuel - LanderRules.FuelBurnPerSecond * LanderRules.Dt).coerceAtLeast(0f) else state.fuel
        val ax = tilt * LanderRules.SideAccel + state.wind
        val ay = -LanderRules.Gravity + if (thrusting) LanderRules.ThrustAccel else 0f
        var vx = state.vx + ax * LanderRules.Dt
        var vy = state.vy + ay * LanderRules.Dt
        var x = state.x + vx * LanderRules.Dt
        var y = state.y + vy * LanderRules.Dt
        if (x < LanderRules.ShipRadius) { x = LanderRules.ShipRadius; if (vx < 0f) vx = 0f }
        if (x > 1f - LanderRules.ShipRadius) { x = 1f - LanderRules.ShipRadius; if (vx > 0f) vx = 0f }
        if (y > 1f - LanderRules.ShipRadius) { y = 1f - LanderRules.ShipRadius; if (vy > 0f) vy = 0f }
        val next = state.copy(tick = state.tick + 1, x = x, y = y, vx = vx, vy = vy, fuel = fuel, tilt = tilt, event = LanderRules.EventNone)
        val withinPad = abs(x - state.padCentre) <= state.padWidth / 2f
        if (withinPad && y <= state.padY + LanderRules.ShipRadius) {
            val impact = sqrt(vx * vx + vy * vy)
            return if (vy <= 0f && impact <= LanderRules.MaxLandingSpeed) {
                next.copy(phase = LanderPhase.Landed, y = state.padY + LanderRules.ShipRadius, vx = 0f, vy = 0f,
                    landingSpeed = impact, score = LanderRules.score(fuel, impact), event = LanderRules.EventLanded)
            } else {
                next.copy(phase = LanderPhase.Crashed, y = state.padY + LanderRules.ShipRadius, landingSpeed = impact,
                    event = LanderRules.EventCrashed)
            }
        }
        val ground = groundHeight(state, x)
        if (y <= ground + LanderRules.ShipRadius) {
            return next.copy(phase = LanderPhase.Crashed, y = ground + LanderRules.ShipRadius, landingSpeed = next.speed,
                event = LanderRules.EventCrashed)
        }
        return next
    }
}

/**
 * Turns a stick position into [LanderInput]. The stick is the hub joystick (offset from the hub centre) or, with the
 * joystick switched off, a drag on the page (offset from where the finger went down). Left and right tilt; pushing up
 * thrusts. They work at the same time, so a diagonal push tilts and thrusts. Positions are in dp.
 */
internal class LanderStick(private val deadDp: Float) {
    fun read(dxDp: Float, dyDp: Float, active: Boolean): LanderInput {
        if (!active || !dxDp.isFinite() || !dyDp.isFinite()) return LanderInput.None
        val tilt = if (abs(dxDp) > deadDp) (if (dxDp > 0f) 1 else -1) else 0
        val thrust = dyDp < -deadDp
        return LanderInput(tilt, thrust)
    }
}

/** The labels the game shows outside the field, kept here so tests can read them. */
internal object LanderPresentation {
    fun speed(state: LanderState): Int = (state.speed * 100f).roundToInt()
    fun wind(state: LanderState): String = when {
        abs(state.wind) < 0.001f -> "calm"
        state.wind > 0f -> "${(state.wind * 100f).roundToInt()} →"
        else -> "${abs((state.wind * 100f).roundToInt())} ←"
    }
    fun landingLimit(): String = "under ${(LanderRules.MaxLandingSpeed * 100f).roundToInt()}"
}
