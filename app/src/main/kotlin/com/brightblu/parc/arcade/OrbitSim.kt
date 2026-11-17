package com.brightblu.parc.arcade

import kotlin.math.abs
import kotlin.math.min

/**
 * Orbit's rules, pure and deterministic: a fixed timestep, a seeded generator, and `step(state, input) -> state`. No Android, no clock.
 *
 * The field has three concentric rings that turn at different speeds. The player is a dot riding one ring: it is carried round with
 * its ring, so on its own ring the world holds still around it. It can steer along the ring, or hop one ring in or out (it keeps its
 * place in the sky, so it lands at a different place in the new ring's frame). Lights sit on the rings; touching one on your ring
 * scores a point and a new one appears. Barriers sit on the rings too and slowly drift along them; touching one ends the run, whether
 * a barrier drifts into you, you steer into one, or you hop into one. The free stretches between barriers are the gaps you slip
 * through. Every few seconds the rings turn a little faster, and every 15 s another barrier appears (up to three per ring).
 *
 * Angles are degrees. A ring's frame angle plus its phase is the angle on screen (0 = right, increasing clockwise as drawn).
 */
internal object OrbitRules {
    const val TicksPerSecond = 60
    const val Dt = 1f / TicksPerSecond
    const val Rings = 3
    /** Degrees per second each ring turns at the start; later multiplied by [speedMultiplier]. */
    val RingSpeed = floatArrayOf(24f, -38f, 52f)
    const val SteerSpeed = 110f
    const val PlayerHalf = 4.5f
    const val LightHalf = 6f
    const val BarrierHalf = 17f
    /** Barrier drift along its ring, degrees per second at the start (sign and size chosen per barrier). */
    const val DriftMin = 6f
    const val DriftMax = 20f
    const val HopCooldownTicks = 10
    const val HopAnimationTicks = 9
    const val SpeedRampPerTick = 0.012f / TicksPerSecond
    const val SpeedMax = 2.2f
    const val BarrierEveryTicks = 900
    const val MaxBarriersPerRing = 3
    const val MaxBarriers = Rings * MaxBarriersPerRing
    const val LightSlots = 2
    /** A new barrier is never placed this close (beyond the barrier and player radii) to the player on its ring. */
    const val SpawnClearance = 40f
    /** A new light keeps this far from any barrier edge and from the player. */
    const val LightClearance = 8f
    const val LightFromPlayer = 40f

    const val EventNone = 0
    const val EventCollected = 1
    const val EventHit = 2

    /** The rings' speed multiplier after [tick] ticks: starts at 1, rises slowly, stops at [SpeedMax]. */
    fun speedMultiplier(tick: Int): Float = min(SpeedMax, 1f + tick * SpeedRampPerTick)
}

internal enum class OrbitPhase { Ready, Running, Over }

/** One tick of input. [steer] is -1, 0 or +1 (along the ring); [hop] is -1 (inward), 0 or +1 (outward). */
internal data class OrbitInput(val steer: Int = 0, val hop: Int = 0) {
    companion object { val None = OrbitInput() }
}

/**
 * The whole state of a run. Arrays are never changed after construction (a step copies what it changes), so a state can be kept
 * and compared. [event] holds the [OrbitRules] event flags of the step that produced it (for haptics only).
 */
internal class OrbitState(
    val phase: OrbitPhase,
    val tick: Int,
    val rng: Long,
    val phases: FloatArray,
    val ring: Int,
    val local: Float,
    val hopFrom: Int,
    val hopTicks: Int,
    val hopCooldown: Int,
    val score: Int,
    val barrierRing: IntArray,
    val barrierLocal: FloatArray,
    val barrierDrift: FloatArray,
    val lightRing: IntArray,
    val lightLocal: FloatArray,
    val nextBarrierTick: Int,
    val event: Int,
) {
    /** A hash of everything that defines the run, used by the tests to compare two runs exactly. */
    fun signature(): Long {
        var h = -3750763034362895579L
        fun mix(v: Long) { h = (h xor v) * 1099511628211L }
        mix(phase.ordinal.toLong()); mix(tick.toLong()); mix(rng); mix(ring.toLong()); mix(local.toRawBits().toLong())
        mix(hopFrom.toLong()); mix(hopTicks.toLong()); mix(hopCooldown.toLong()); mix(score.toLong()); mix(nextBarrierTick.toLong()); mix(event.toLong())
        phases.forEach { mix(it.toRawBits().toLong()) }
        barrierRing.forEach { mix(it.toLong()) }; barrierLocal.forEach { mix(it.toRawBits().toLong()) }; barrierDrift.forEach { mix(it.toRawBits().toLong()) }
        lightRing.forEach { mix(it.toLong()) }; lightLocal.forEach { mix(it.toRawBits().toLong()) }
        return h
    }
    val barrierCount: Int get() = barrierRing.size
    fun barriersOn(ringIndex: Int): Int = barrierRing.count { it == ringIndex }
    /** The player's angle on screen. */
    fun playerAngle(): Float = OrbitSim.wrap(phases[ring] + local)
}

/** A small splitmix generator: the whole generator is one Long, kept in the state. */
internal object OrbitRng {
    fun next(state: Long): Long {
        var z = state + -7046029254386353131L
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }
    /** A float in [0, 1) from a generator state. */
    fun unit(state: Long): Float = ((state ushr 40).toInt() and 0xFFFFFF) / 16777216f
}

internal object OrbitSim {
    fun wrap(a: Float): Float { val m = a % 360f; return if (m < 0f) m + 360f else m }
    /** Shortest angular distance, 0..180. */
    fun distance(a: Float, b: Float): Float { val d = abs(wrap(a - b)); return if (d > 180f) 360f - d else d }

    /** A run waiting for the first tap. Everything, including the first barriers and lights, comes from [seed]. */
    fun fresh(seed: Long): OrbitState {
        var rng = OrbitRng.next(seed xor 0x5DEECE66DL)
        val phases = FloatArray(OrbitRules.Rings)
        for (k in phases.indices) { rng = OrbitRng.next(rng); phases[k] = OrbitRng.unit(rng) * 360f }
        val ring = 1
        val local = 0f
        val barrierRing = IntArray(OrbitRules.Rings) { it }
        val barrierLocal = FloatArray(OrbitRules.Rings)
        val barrierDrift = FloatArray(OrbitRules.Rings)
        for (k in 0 until OrbitRules.Rings) {
            rng = OrbitRng.next(rng)
            barrierLocal[k] = if (k == ring) 90f + OrbitRng.unit(rng) * 180f else OrbitRng.unit(rng) * 360f
            rng = OrbitRng.next(rng)
            val magnitude = OrbitRules.DriftMin + OrbitRng.unit(rng) * (OrbitRules.DriftMax - OrbitRules.DriftMin)
            rng = OrbitRng.next(rng)
            barrierDrift[k] = if (OrbitRng.unit(rng) < 0.5f) -magnitude else magnitude
        }
        val lightRing = IntArray(OrbitRules.LightSlots) { -1 }
        val lightLocal = FloatArray(OrbitRules.LightSlots)
        var state = OrbitState(OrbitPhase.Ready, 0, rng, phases, ring, local, ring, 0, 0, 0, barrierRing, barrierLocal, barrierDrift,
            lightRing, lightLocal, OrbitRules.BarrierEveryTicks, OrbitRules.EventNone)
        for (slot in 0 until OrbitRules.LightSlots) state = respawnLight(state, slot)
        return state
    }

    /** The first tap: Ready becomes Running. Any other phase is returned unchanged. */
    fun begin(state: OrbitState): OrbitState =
        if (state.phase != OrbitPhase.Ready) state else copy(state, phase = OrbitPhase.Running)

    fun step(state: OrbitState, input: OrbitInput): OrbitState {
        if (state.phase != OrbitPhase.Running) return state
        val tick = state.tick + 1
        val multiplier = OrbitRules.speedMultiplier(tick)
        val phases = FloatArray(OrbitRules.Rings) { wrap(state.phases[it] + OrbitRules.RingSpeed[it] * multiplier * OrbitRules.Dt) }
        var ring = state.ring
        var local = state.local
        var hopFrom = state.hopFrom
        var hopTicks = if (state.hopTicks > 0) state.hopTicks - 1 else 0
        var cooldown = if (state.hopCooldown > 0) state.hopCooldown - 1 else 0
        if (input.steer != 0) local = wrap(local + input.steer.coerceIn(-1, 1) * OrbitRules.SteerSpeed * OrbitRules.Dt)
        val target = ring + input.hop.coerceIn(-1, 1)
        if (input.hop != 0 && cooldown == 0 && target in 0 until OrbitRules.Rings) {
            // The player keeps its place in the sky; the new ring's frame turns at another speed.
            local = wrap(phases[ring] + local - phases[target])
            hopFrom = ring; ring = target; hopTicks = OrbitRules.HopAnimationTicks; cooldown = OrbitRules.HopCooldownTicks
        }
        val barrierLocal = FloatArray(state.barrierLocal.size) { wrap(state.barrierLocal[it] + state.barrierDrift[it] * multiplier * OrbitRules.Dt) }
        var next = OrbitState(OrbitPhase.Running, tick, state.rng, phases, ring, local, hopFrom, hopTicks, cooldown, state.score,
            state.barrierRing, barrierLocal, state.barrierDrift, state.lightRing, state.lightLocal, state.nextBarrierTick, OrbitRules.EventNone)
        // A barrier on the player's ring that reaches the player ends the run.
        for (i in 0 until next.barrierCount) {
            if (next.barrierRing[i] == ring && distance(local, barrierLocal[i]) < OrbitRules.BarrierHalf + OrbitRules.PlayerHalf) {
                return copy(next, phase = OrbitPhase.Over, event = OrbitRules.EventHit)
            }
        }
        var event = OrbitRules.EventNone
        for (slot in 0 until OrbitRules.LightSlots) {
            if (next.lightRing[slot] == ring && distance(local, next.lightLocal[slot]) < OrbitRules.LightHalf + OrbitRules.PlayerHalf) {
                next = respawnLight(copy(next, score = next.score + 1), slot)
                event = event or OrbitRules.EventCollected
            }
        }
        if (tick >= next.nextBarrierTick) next = addBarrier(next)
        return if (event == OrbitRules.EventNone) next else copy(next, event = event)
    }

    private fun copy(s: OrbitState, phase: OrbitPhase = s.phase, score: Int = s.score, event: Int = s.event) = OrbitState(phase, s.tick, s.rng,
        s.phases, s.ring, s.local, s.hopFrom, s.hopTicks, s.hopCooldown, score, s.barrierRing, s.barrierLocal, s.barrierDrift,
        s.lightRing, s.lightLocal, s.nextBarrierTick, event)

    /** The free stretch between barriers: the mid angle and the size of the largest gap on [ring]. */
    internal fun largestGap(barrierRing: IntArray, barrierLocal: FloatArray, ring: Int): Pair<Float, Float> {
        val angles = ArrayList<Float>()
        for (i in barrierRing.indices) if (barrierRing[i] == ring) angles.add(barrierLocal[i])
        if (angles.isEmpty()) return 0f to 360f
        angles.sort()
        var bestMid = 0f
        var bestSize = -1f
        for (i in angles.indices) {
            val from = angles[i]
            val to = if (i == angles.lastIndex) angles[0] + 360f else angles[i + 1]
            val size = to - from
            if (size > bestSize) { bestSize = size; bestMid = wrap(from + size / 2f) }
        }
        return bestMid to bestSize
    }

    private fun clearOfBarriers(s: OrbitState, ring: Int, angle: Float, margin: Float): Boolean {
        for (i in 0 until s.barrierCount) if (s.barrierRing[i] == ring && distance(angle, s.barrierLocal[i]) < OrbitRules.BarrierHalf + margin) return false
        return true
    }

    /** Puts a new light in [slot]: a random ring and angle clear of barriers and of the player, or the middle of the largest gap. */
    private fun respawnLight(s: OrbitState, slot: Int): OrbitState {
        var rng = s.rng
        var chosenRing = -1
        var chosenAngle = 0f
        for (attempt in 0 until 12) {
            rng = OrbitRng.next(rng); val r = (OrbitRng.unit(rng) * OrbitRules.Rings).toInt().coerceIn(0, OrbitRules.Rings - 1)
            rng = OrbitRng.next(rng); val a = OrbitRng.unit(rng) * 360f
            val otherSlot = if (slot == 0) 1 else 0
            val taken = s.lightRing.getOrNull(otherSlot) == r && distance(a, s.lightLocal[otherSlot]) < OrbitRules.LightFromPlayer
            val onPlayer = r == s.ring && distance(a, s.local) < OrbitRules.LightFromPlayer
            if (!taken && !onPlayer && clearOfBarriers(s, r, a, OrbitRules.LightClearance + OrbitRules.LightHalf)) { chosenRing = r; chosenAngle = a; break }
        }
        if (chosenRing < 0) {
            // Rare: no random spot was free. Use the middle of the largest gap of the ring the player is not on.
            chosenRing = if (s.ring == 0) 1 else 0
            chosenAngle = largestGap(s.barrierRing, s.barrierLocal, chosenRing).first
        }
        val lr = s.lightRing.copyOf(); val ll = s.lightLocal.copyOf()
        lr[slot] = chosenRing; ll[slot] = chosenAngle
        return OrbitState(s.phase, s.tick, rng, s.phases, s.ring, s.local, s.hopFrom, s.hopTicks, s.hopCooldown, s.score, s.barrierRing,
            s.barrierLocal, s.barrierDrift, lr, ll, s.nextBarrierTick, s.event)
    }

    /** The next barrier goes on the ring with the fewest, in the middle of its largest gap, and waits a tick if the player is close to that spot. */
    internal fun addBarrier(s: OrbitState): OrbitState {
        if (s.barrierCount >= OrbitRules.MaxBarriers) return withNextBarrier(s, Int.MAX_VALUE)
        var ring = 0
        for (k in 1 until OrbitRules.Rings) if (s.barriersOn(k) < s.barriersOn(ring)) ring = k
        if (s.barriersOn(ring) >= OrbitRules.MaxBarriersPerRing) return withNextBarrier(s, Int.MAX_VALUE)
        val (mid, _) = largestGap(s.barrierRing, s.barrierLocal, ring)
        if (ring == s.ring && distance(mid, s.local) < OrbitRules.BarrierHalf + OrbitRules.PlayerHalf + OrbitRules.SpawnClearance) return s
        var rng = OrbitRng.next(s.rng)
        val magnitude = OrbitRules.DriftMin + OrbitRng.unit(rng) * (OrbitRules.DriftMax - OrbitRules.DriftMin)
        rng = OrbitRng.next(rng)
        val drift = if (OrbitRng.unit(rng) < 0.5f) -magnitude else magnitude
        val br = IntArray(s.barrierCount + 1) { if (it < s.barrierCount) s.barrierRing[it] else ring }
        val bl = FloatArray(s.barrierCount + 1) { if (it < s.barrierCount) s.barrierLocal[it] else mid }
        val bd = FloatArray(s.barrierCount + 1) { if (it < s.barrierCount) s.barrierDrift[it] else drift }
        var next = OrbitState(s.phase, s.tick, rng, s.phases, s.ring, s.local, s.hopFrom, s.hopTicks, s.hopCooldown, s.score, br, bl, bd,
            s.lightRing, s.lightLocal, s.tick + OrbitRules.BarrierEveryTicks, s.event)
        // A light now under the new barrier is moved.
        for (slot in 0 until OrbitRules.LightSlots) {
            if (next.lightRing[slot] == ring && !clearOfBarriers(next, ring, next.lightLocal[slot], OrbitRules.LightHalf)) next = respawnLight(next, slot)
        }
        return next
    }

    private fun withNextBarrier(s: OrbitState, tick: Int) = OrbitState(s.phase, s.tick, s.rng, s.phases, s.ring, s.local, s.hopFrom, s.hopTicks,
        s.hopCooldown, s.score, s.barrierRing, s.barrierLocal, s.barrierDrift, s.lightRing, s.lightLocal, tick, s.event)
}

/**
 * Turns a stick position into [OrbitInput]. The stick is the hub joystick (offset from the hub centre) or, with the joystick switched
 * off, a drag on the page (offset from where the finger went down). The larger axis wins: sideways steers while held, up hops outward
 * and down hops inward once per push (the stick must come back to the middle before the next hop). Positions are in dp.
 */
internal class OrbitStick(private val deadDp: Float) {
    private var hopArmed = true

    fun read(dxDp: Float, dyDp: Float, active: Boolean): OrbitInput {
        if (!active || !dxDp.isFinite() || !dyDp.isFinite()) { hopArmed = true; return OrbitInput.None }
        val ax = abs(dxDp); val ay = abs(dyDp)
        if (ax <= deadDp && ay <= deadDp) { hopArmed = true; return OrbitInput.None }
        if (ax >= ay) return OrbitInput(steer = if (dxDp > 0f) 1 else -1)
        if (!hopArmed) return OrbitInput.None
        hopArmed = false
        return OrbitInput(hop = if (dyDp < 0f) 1 else -1)
    }
    fun reset() { hopArmed = true }
}
