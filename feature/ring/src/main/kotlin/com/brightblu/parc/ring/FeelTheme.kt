package com.brightblu.parc.ring

import android.os.VibrationEffect
import android.view.HapticFeedbackConstants

/**
 * T80 pure haptic and sound model. Nothing here touches a service: the mapping from a
 * (preset, event) pair to a [HapticPlan] and the mute rules are plain functions, so they are
 * unit-testable; [FeelPlayer] performs the plans.
 */

/** The existing haptic moments of the launcher, one value per kind of feedback. */
enum class FeelEvent {
    /** Focus moves, ring slider steps, search-pull threshold (today: CLOCK_TICK). */
    Tick,
    /** A pinned app launches (today: CONFIRM). */
    Confirm,
    /** The long-press menu opens (today: LONG_PRESS). */
    Menu,
    /** A rejected drop (today: REJECT). */
    Reject;

    /** The platform constant used by today's behaviour. */
    internal val platformConstant: Int
        get() = when (this) {
            Tick -> HapticFeedbackConstants.CLOCK_TICK
            Confirm -> HapticFeedbackConstants.CONFIRM
            Menu -> HapticFeedbackConstants.LONG_PRESS
            Reject -> HapticFeedbackConstants.REJECT
        }
}

enum class HapticPreset(val id: String, val label: String, val detail: String) {
    Off("off", "Off", "No vibration from Parc"),
    Soft("soft", "Soft", "Light ticks"),
    /** Default: exactly the platform haptics Parc has always used. */
    Crisp("crisp", "Crisp", "Standard system haptics"),
    Sharp("sharp", "Sharp", "Strong clicks");

    companion object {
        fun fromId(id: String?): HapticPreset = entries.firstOrNull { it.id == id } ?: Crisp
    }
}

enum class SoundTheme(val id: String, val label: String, val detail: String) {
    Off("off", "Off", "Silent"),
    SoftTicks("soft", "Soft ticks", "Quiet short ticks"),
    Mechanical("mechanical", "Mechanical", "Dry key clicks"),
    Synth("synth", "Synth", "Short electronic blips");

    companion object {
        fun fromId(id: String?): SoundTheme = entries.firstOrNull { it.id == id } ?: Off
    }
}

/** The stored Feel settings. Defaults leave today's behaviour unchanged. */
data class FeelPrefs(
    val haptic: HapticPreset = HapticPreset.Crisp,
    val sound: SoundTheme = SoundTheme.Off,
    /** Sound volume, 0..100. Ignored while [sound] is Off. */
    val volumePercent: Int = DefaultVolume,
) {
    val volume: Float get() = volumePercent.coerceIn(0, 100) / 100f

    companion object {
        const val DefaultVolume = 60
    }
}

/** What the device can do; queried once per player. Pure so tests can fake it. */
interface FeelCapabilities {
    val hasVibrator: Boolean
    fun effectSupported(effectId: Int): Boolean
    fun primitiveSupported(primitiveId: Int): Boolean

    /** Everything supported (used by tests and as a neutral default). */
    object Full : FeelCapabilities {
        override val hasVibrator = true
        override fun effectSupported(effectId: Int) = true
        override fun primitiveSupported(primitiveId: Int) = true
    }

    /** A device with a basic motor and no predefined effects or primitives. */
    object Basic : FeelCapabilities {
        override val hasVibrator = true
        override fun effectSupported(effectId: Int) = false
        override fun primitiveSupported(primitiveId: Int) = false
    }
}

/** One step of a composed effect. */
data class PrimitiveStep(val primitive: Int, val scale: Float, val delayMs: Int = 0)

/** What the player should do for a haptic event. */
sealed interface HapticPlan {
    /** Nothing at all. */
    data object None : HapticPlan
    /** `View.performHapticFeedback(constant)`: honours the system touch-feedback switch. */
    data class Platform(val constant: Int) : HapticPlan
    /** `VibrationEffect.createPredefined(effectId)`. */
    data class Predefined(val effectId: Int) : HapticPlan
    /** `VibrationEffect.startComposition()` with these primitives. */
    data class Composed(val steps: List<PrimitiveStep>) : HapticPlan
}

object FeelTheme {
    // VibrationEffect constants are compile-time values, so they are safe in plain JVM tests.
    private const val EFFECT_TICK = VibrationEffect.EFFECT_TICK
    private const val EFFECT_CLICK = VibrationEffect.EFFECT_CLICK
    private const val EFFECT_HEAVY_CLICK = VibrationEffect.EFFECT_HEAVY_CLICK
    private const val PRIMITIVE_TICK = VibrationEffect.Composition.PRIMITIVE_TICK
    private const val PRIMITIVE_CLICK = VibrationEffect.Composition.PRIMITIVE_CLICK

    /** The ordered candidates for a preset and event, best first. The last one always works. */
    internal fun candidates(preset: HapticPreset, event: FeelEvent): List<HapticPlan> = when (preset) {
        HapticPreset.Off -> emptyList()
        HapticPreset.Crisp -> listOf(HapticPlan.Platform(event.platformConstant))
        HapticPreset.Soft -> when (event) {
            FeelEvent.Tick, FeelEvent.Confirm -> listOf(HapticPlan.Predefined(EFFECT_TICK))
            FeelEvent.Menu -> listOf(HapticPlan.Predefined(EFFECT_CLICK))
            FeelEvent.Reject -> listOf(
                HapticPlan.Composed(listOf(PrimitiveStep(PRIMITIVE_TICK, 0.6f), PrimitiveStep(PRIMITIVE_TICK, 0.6f, 70))),
                HapticPlan.Predefined(EFFECT_TICK),
            )
        }
        HapticPreset.Sharp -> when (event) {
            FeelEvent.Tick -> listOf(HapticPlan.Predefined(EFFECT_CLICK))
            FeelEvent.Confirm, FeelEvent.Menu -> listOf(HapticPlan.Predefined(EFFECT_HEAVY_CLICK))
            FeelEvent.Reject -> listOf(
                HapticPlan.Composed(listOf(PrimitiveStep(PRIMITIVE_CLICK, 1f), PrimitiveStep(PRIMITIVE_CLICK, 1f, 80))),
                HapticPlan.Predefined(EFFECT_HEAVY_CLICK),
            )
        }
    }

    /**
     * The plan for [event]: the first candidate the device supports, else the platform constant
     * (always available through the view), so a device lacking an effect still gets feedback.
     * Crisp never touches the vibrator, so it behaves exactly as before on every device.
     */
    fun hapticPlan(preset: HapticPreset, event: FeelEvent, caps: FeelCapabilities): HapticPlan {
        if (preset == HapticPreset.Off) return HapticPlan.None
        if (preset == HapticPreset.Crisp) return HapticPlan.Platform(event.platformConstant)
        if (!caps.hasVibrator) return HapticPlan.None
        val supported = candidates(preset, event).firstOrNull { plan ->
            when (plan) {
                is HapticPlan.Predefined -> caps.effectSupported(plan.effectId)
                is HapticPlan.Composed -> plan.steps.all { caps.primitiveSupported(it.primitive) }
                else -> true
            }
        }
        return supported ?: HapticPlan.Platform(event.platformConstant)
    }
}

/** Ringer state as the sound rules see it, independent of `AudioManager` constants. */
enum class Ringer {
    Silent, Vibrate, Normal;

    companion object {
        /** `AudioManager.RINGER_MODE_*`: 0 silent, 1 vibrate, 2 normal. Unknown values count as silent. */
        fun fromAndroid(mode: Int): Ringer = when (mode) {
            2 -> Normal
            1 -> Vibrate
            else -> Silent
        }
    }
}

object SoundRules {
    /** A sound plays only for an on theme, a normal ringer and a non-zero volume. Never when silent. */
    fun shouldPlay(theme: SoundTheme, ringer: Ringer, volumePercent: Int): Boolean =
        theme != SoundTheme.Off && ringer == Ringer.Normal && volumePercent > 0
}
