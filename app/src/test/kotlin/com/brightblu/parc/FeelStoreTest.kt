package com.brightblu.parc

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

    @Test fun entryAndSummary() {
        assertEquals("feel", FeelSettingsEntry.Default.id)
        assertEquals("Feel", FeelSettingsEntry.Default.title)
        assertEquals("Crisp, no sound", FeelSettingsEntry.summary(FeelPrefs()))
        assertEquals("Sharp, synth", FeelSettingsEntry.summary(FeelPrefs(HapticPreset.Sharp, SoundTheme.Synth)))
    }

    @Test fun volumeStepsAreOrderedAndContainDefault() {
        assertEquals(FeelUi.VolumeSteps.sorted(), FeelUi.VolumeSteps)
        assert(FeelPrefs.DefaultVolume in FeelUi.VolumeSteps)
    }
}
