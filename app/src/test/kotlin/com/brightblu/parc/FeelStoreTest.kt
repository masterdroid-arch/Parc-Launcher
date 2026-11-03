package com.brightblu.parc

import com.brightblu.parc.model.ArcFeel
import com.brightblu.parc.model.Bounce
import com.brightblu.parc.model.Magnify
import com.brightblu.parc.model.SpinLimit
import com.brightblu.parc.ring.FeelPrefs
import com.brightblu.parc.ring.HapticPreset
import com.brightblu.parc.ring.SoundTheme
import org.junit.Assert.assertEquals
import org.junit.Test

class FeelStoreTest {
    @Test fun missingValuesDecodeToDefaults() {
        assertEquals(FeelPrefs(), FeelPrefsCodec.decode(null, null, null))
    }

    @Test fun storedValuesDecode() {
        assertEquals(FeelPrefs(HapticPreset.Sharp, SoundTheme.Synth, 30), FeelPrefsCodec.decode("sharp", "synth", 30))
    }

    @Test fun garbageFallsBackAndVolumeIsClamped() {
        assertEquals(FeelPrefs(HapticPreset.Crisp, SoundTheme.Off, 100), FeelPrefsCodec.decode("x", "y", 900))
        assertEquals(0, FeelPrefsCodec.decode(null, null, -3).volumePercent)
    }


    @Test fun volumeStepsAreOrderedAndContainDefault() {
        assertEquals(FeelUi.VolumeSteps.sorted(), FeelUi.VolumeSteps)
        assert(FeelPrefs.DefaultVolume in FeelUi.VolumeSteps)
    }

    @Test fun arcDefaultsAreBalancedBalancedSubtleAndFourHomeArcs() {
        assertEquals(ArcFeel(SpinLimit.Balanced, Bounce.Balanced, Magnify.Subtle), FeelPrefsCodec.decodeArc(null, null, null))
        assertEquals(ArcFeel(), FeelPrefsCodec.decodeArc(null, null, null))
        assertEquals(HomeArcs.Four, HomeArcs.fromId(null))
        assertEquals(4, HomeArcs.Four.count)
        assertEquals(2, HomeArcs.Two.count)
    }

    @Test fun arcStoredIdsRoundTripForEveryValue() {
        for (s in SpinLimit.entries) for (b in Bounce.entries) for (m in Magnify.entries) {
            assertEquals(ArcFeel(s, b, m), FeelPrefsCodec.decodeArc(s.id, b.id, m.id))
        }
        HomeArcs.entries.forEach { assertEquals(it, HomeArcs.fromId(it.id)) }
    }

    @Test fun arcUnknownOrBlankValuesGiveTheDefaultPerField() {
        assertEquals(ArcFeel(SpinLimit.Free, Bounce.Balanced, Magnify.Subtle), FeelPrefsCodec.decodeArc("free", "wobbly", ""))
        assertEquals(ArcFeel(SpinLimit.Balanced, Bounce.Playful, Magnify.DockLike), FeelPrefsCodec.decodeArc("TIGHT ", "playful", "dock"))
        assertEquals(HomeArcs.Four, HomeArcs.fromId("three"))
        assertEquals(HomeArcs.Four, HomeArcs.fromId(""))
    }

    @Test fun arcStorageKeysAreTheCardNames() {
        assertEquals("arc_spin_limit", FeelPrefsCodec.SpinLimitKey)
        assertEquals("arc_bounce", FeelPrefsCodec.BounceKey)
        assertEquals("arc_magnify", FeelPrefsCodec.MagnifyKey)
        assertEquals("arc_home_arcs", FeelPrefsCodec.HomeArcsKey)
    }



}
