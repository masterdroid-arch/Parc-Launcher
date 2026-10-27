package com.brightblu.parc.ring

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.effectiveMotion
import com.brightblu.parc.model.Skin
import kotlin.math.*

/** Main-thread effect clock. No draw work, coroutines, or Android dependencies. Times are uptime ms. */
internal class RingMotion(private val skin: Skin) {
    var sweep = 1f; private set
    var slots = 1f; private set
    var lineAlpha = 1f; private set
    var pulse = 0f; private set
    /** Usage-layer swap progress: 0 at the swap, 1 when the newcomer has fully faded in (T35). */
    var swap = 1f; private set
    /** One-shot search-button accent swell in `0..1`; repeats never (T35). */
    var hint = 0f; private set
    var glowX = 0f; private set
    var glowY = 0f; private set
    private var vx = 0f
    private var vy = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var wake = 0L
    private var touch = 0L
    private var previous = 0L
    private var wakeCancelled = false
    private var following = false
    private var pulseEpoch = 0L
    private var hadUnread = false
    private var swapStart = 0L
    private var hintEpoch = 0L
    private var hintArmed = false

    fun resume(now: Long) {
        wake = now; touch = now; previous = now; pulseEpoch = now
        wakeCancelled = false; following = false; hadUnread = false
        swapStart = 0L; swap = 1f
        hintEpoch = now; hintArmed = true; hint = 0f
    }

    /** Begin the usage-layer cross-fade + newcomer pulse (T35). */
    fun startSwap(now: Long) { swapStart = now; swap = 0f }
    fun touch(now: Long) { touch = now; wakeCancelled = true; sweep = 1f; slots = 1f }
    fun finger(x: Float, y: Float, down: Boolean = false) {
        targetX = x; targetY = y
        if (down) { glowX = x; glowY = y; vx = 0f; vy = 0f }
        following = true
    }
    fun release() { following = false }
    fun settle() {
        sweep = 1f; slots = 1f; lineAlpha = 1f; pulse = 0f
        swap = 1f; swapStart = 0L; hint = 0f; hintArmed = false
        glowX = targetX; glowY = targetY; vx = 0f; vy = 0f
    }

    /** Returns ms until useful work: zero = next frame, MAX_VALUE = sleep until input. */
    fun step(now: Long, systemScale: Float, unread: Boolean, hintActive: Boolean = false): Long {
        val scale = systemScale * skin.motionScale
        if (scale <= 0f) { settle(); return Long.MAX_VALUE }
        val motion = skin.effectiveMotion
        val elapsed = (now - wake).coerceAtLeast(0) / scale
        sweep = if (wakeCancelled) 1f else (elapsed / motion.wakeSweep).coerceIn(0f, 1f)
        slots = if (wakeCancelled) 1f else ((elapsed - motion.wakeSweep) / motion.wakeSlotFade).coerceIn(0f, 1f)
        val breathing = now - touch < motion.idleStop
        lineAlpha = if (breathing) 1f + skin.breatheAmount * sin(
            (now - touch) / scale * (2.0 * PI / motion.breathePeriod)).toFloat() else 1f
        val dt = ((now - previous).coerceIn(0, Metrics.springMaxStepMs) / 1000f / scale)
        previous = now
        if (following && dt > 0f) {
            // Exact underdamped spring solution; no integration instability on a delayed frame.
            val omega = sqrt(motion.glowSpringStiffness)
            val decay = motion.glowSpringDamping * omega
            val frequency = omega * sqrt(1f - motion.glowSpringDamping * motion.glowSpringDamping)
            val e = exp(-decay * dt)
            val c = cos(frequency * dt); val s = sin(frequency * dt)
            val dx = glowX - targetX; val dy = glowY - targetY
            val bx = (vx + decay * dx) / frequency
            val by = (vy + decay * dy) / frequency
            glowX = targetX + e * (dx * c + bx * s)
            glowY = targetY + e * (dy * c + by * s)
            vx = e * (-decay * (dx * c + bx * s) + frequency * (-dx * s + bx * c))
            vy = e * (-decay * (dy * c + by * s) + frequency * (-dy * s + by * c))
        }
        val spring = following && (abs(glowX - targetX) + abs(glowY - targetY) > Metrics.glowSettlePx ||
            abs(vx) + abs(vy) > Metrics.glowSettleVelocity)
        if (!spring) { glowX = targetX; glowY = targetY; vx = 0f; vy = 0f }
        if (unread && !hadUnread) pulseEpoch = now
        hadUnread = unread
        val phase = if (unread) ((now - pulseEpoch) / scale) % motion.pulseRepeat else 0f
        val swelling = unread && phase < motion.pulseDuration
        pulse = if (swelling) sin(PI * phase / motion.pulseDuration).toFloat().coerceAtLeast(0f) else 0f
        // Usage-layer swap: linear cross-fade; the accent swell is derived from it in draw.
        swap = if (swapStart == 0L) 1f else
            (((now - swapStart).coerceAtLeast(0L) / scale) / Metrics.swapFadeMs.toFloat()).coerceIn(0f, 1f)
        if (swap >= 1f) swapStart = 0L
        // Search-button hint: one swell inside the first pulse window of this launcher start.
        val hintElapsed = now - hintEpoch
        hint = if (hintArmed && hintActive && hintElapsed < motion.pulseDuration)
            sin(PI * hintElapsed / motion.pulseDuration).toFloat().coerceAtLeast(0f) else 0f
        if (hintElapsed >= motion.pulseDuration) hintArmed = false
        if (slots < 1f || breathing || spring || swelling || swap < 1f || hint > 0f) return 0L
        return if (unread) ((motion.pulseRepeat - phase) * scale).toLong().coerceAtLeast(1) else Long.MAX_VALUE
    }
}
