package com.brightblu.parc.ring

import android.os.VibrationEffect
import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeelThemeTest {
    @Test fun defaultsLeaveTodaysBehaviour() {
        val d = FeelPrefs()
        assertEquals(HapticPreset.Crisp, d.haptic)
        assertEquals(SoundTheme.Off, d.sound)
        assertEquals(FeelPrefs.DefaultVolume, d.volumePercent)
    }

    @Test fun crispIsExactlyTheOldPlatformConstants() {
        val expected = mapOf(
            FeelEvent.Tick to HapticFeedbackConstants.CLOCK_TICK,
            FeelEvent.Confirm to HapticFeedbackConstants.CONFIRM,
            FeelEvent.Menu to HapticFeedbackConstants.LONG_PRESS,
            FeelEvent.Reject to HapticFeedbackConstants.REJECT,
        )
        listOf(FeelCapabilities.Full, FeelCapabilities.Basic).forEach { caps ->
            FeelEvent.entries.forEach {
                assertEquals(HapticPlan.Platform(expected.getValue(it)), FeelTheme.hapticPlan(HapticPreset.Crisp, it, caps))
            }
        }
    }

    @Test fun offIsSilent() {
        FeelEvent.entries.forEach { assertEquals(HapticPlan.None, FeelTheme.hapticPlan(HapticPreset.Off, it, FeelCapabilities.Full)) }
    }

    @Test fun softAndSharpUsePredefinedEffects() {
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_TICK),
            FeelTheme.hapticPlan(HapticPreset.Soft, FeelEvent.Tick, FeelCapabilities.Full))
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_CLICK),
            FeelTheme.hapticPlan(HapticPreset.Soft, FeelEvent.Menu, FeelCapabilities.Full))
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_CLICK),
            FeelTheme.hapticPlan(HapticPreset.Sharp, FeelEvent.Tick, FeelCapabilities.Full))
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK),
            FeelTheme.hapticPlan(HapticPreset.Sharp, FeelEvent.Confirm, FeelCapabilities.Full))
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK),
            FeelTheme.hapticPlan(HapticPreset.Sharp, FeelEvent.Menu, FeelCapabilities.Full))
    }

    @Test fun rejectIsComposedWhenPrimitivesExist() {
        listOf(HapticPreset.Soft, HapticPreset.Sharp).forEach {
            val plan = FeelTheme.hapticPlan(it, FeelEvent.Reject, FeelCapabilities.Full)
            assertTrue(plan is HapticPlan.Composed)
            assertEquals(2, (plan as HapticPlan.Composed).steps.size)
        }
    }

    @Test fun missingPrimitivesFallBackToPredefined() {
        val noPrimitives = object : FeelCapabilities {
            override val hasVibrator = true
            override fun effectSupported(effectId: Int) = true
            override fun primitiveSupported(primitiveId: Int) = false
        }
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_TICK),
            FeelTheme.hapticPlan(HapticPreset.Soft, FeelEvent.Reject, noPrimitives))
        assertEquals(HapticPlan.Predefined(VibrationEffect.EFFECT_HEAVY_CLICK),
            FeelTheme.hapticPlan(HapticPreset.Sharp, FeelEvent.Reject, noPrimitives))
    }

    @Test fun deviceWithoutEffectsFallsBackToPlatformConstant() {
        FeelEvent.entries.forEach { e ->
            listOf(HapticPreset.Soft, HapticPreset.Sharp).forEach { p ->
                assertEquals(HapticPlan.Platform(e.platformConstant), FeelTheme.hapticPlan(p, e, FeelCapabilities.Basic))
            }
        }
    }

    @Test fun deviceWithoutVibratorDoesNothingForCustomPresets() {
        val none = object : FeelCapabilities {
            override val hasVibrator = false
            override fun effectSupported(effectId: Int) = false
            override fun primitiveSupported(primitiveId: Int) = false
        }
        assertEquals(HapticPlan.None, FeelTheme.hapticPlan(HapticPreset.Soft, FeelEvent.Tick, none))
        assertEquals(HapticPlan.None, FeelTheme.hapticPlan(HapticPreset.Sharp, FeelEvent.Tick, none))
    }

    @Test fun everyPresetAndEventResolvesToSomething() {
        FeelEvent.entries.forEach { e ->
            HapticPreset.entries.filter { it != HapticPreset.Off }.forEach { p ->
                assertFalse(FeelTheme.hapticPlan(p, e, FeelCapabilities.Basic) == HapticPlan.None)
            }
        }
    }

    @Test fun idsRoundTripAndUnknownFallsBackToDefaults() {
        HapticPreset.entries.forEach { assertEquals(it, HapticPreset.fromId(it.id)) }
        SoundTheme.entries.forEach { assertEquals(it, SoundTheme.fromId(it.id)) }
        assertEquals(HapticPreset.Crisp, HapticPreset.fromId(null))
        assertEquals(HapticPreset.Crisp, HapticPreset.fromId("loud"))
        assertEquals(SoundTheme.Off, SoundTheme.fromId(null))
        assertEquals(SoundTheme.Off, SoundTheme.fromId("loud"))
    }

    @Test fun volumeIsClamped() {
        assertEquals(1f, FeelPrefs(volumePercent = 400).volume, 0f)
        assertEquals(0f, FeelPrefs(volumePercent = -5).volume, 0f)
        assertEquals(0.6f, FeelPrefs().volume, 0.0001f)
    }

    // Mute rules.

    @Test fun neverPlaysWhenSilentOrVibrate() {
        SoundTheme.entries.forEach { theme ->
            listOf(Ringer.Silent, Ringer.Vibrate).forEach { r ->
                assertFalse(SoundRules.shouldPlay(theme, r, 100))
            }
        }
    }

    @Test fun offThemeAndZeroVolumeNeverPlay() {
        assertFalse(SoundRules.shouldPlay(SoundTheme.Off, Ringer.Normal, 100))
        assertFalse(SoundRules.shouldPlay(SoundTheme.Synth, Ringer.Normal, 0))
    }

    @Test fun playsOnNormalRingerWithVolume() {
        SoundTheme.entries.filter { it != SoundTheme.Off }.forEach {
            assertTrue(SoundRules.shouldPlay(it, Ringer.Normal, 1))
        }
    }

    @Test fun ringerMappingTreatsUnknownAsSilent() {
        assertEquals(Ringer.Normal, Ringer.fromAndroid(2))
        assertEquals(Ringer.Vibrate, Ringer.fromAndroid(1))
        assertEquals(Ringer.Silent, Ringer.fromAndroid(0))
        assertEquals(Ringer.Silent, Ringer.fromAndroid(99))
        assertEquals(Ringer.Silent, Ringer.fromAndroid(-1))
    }

    // Synth.

    @Test fun offThemeHasNoSamples() {
        FeelEvent.entries.forEach { assertEquals(0, FeelSynth.generate(SoundTheme.Off, it).size) }
    }

    @Test fun themesStayUnderBudgetAndAreNotEmpty() {
        SoundTheme.entries.filter { it != SoundTheme.Off }.forEach { theme ->
            assertTrue("$theme too big", FeelSynth.bytesFor(theme) < FeelSynth.maxBytesPerTheme)
            FeelEvent.entries.forEach { e ->
                val pcm = FeelSynth.generate(theme, e)
                assertTrue("$theme $e empty", pcm.size > 100)
                assertTrue("$theme $e silent", pcm.any { it.toInt() != 0 })
            }
        }
    }

    @Test fun generationIsDeterministic() {
        SoundTheme.entries.forEach { theme ->
            FeelEvent.entries.forEach { e ->
                assertTrue(FeelSynth.generate(theme, e).contentEquals(FeelSynth.generate(theme, e)))
            }
        }
    }

    @Test fun themesSoundDifferent() {
        val a = FeelSynth.generate(SoundTheme.SoftTicks, FeelEvent.Tick)
        val b = FeelSynth.generate(SoundTheme.Mechanical, FeelEvent.Tick)
        val c = FeelSynth.generate(SoundTheme.Synth, FeelEvent.Tick)
        assertFalse(a.contentEquals(b))
        assertFalse(b.contentEquals(c))
    }
}
