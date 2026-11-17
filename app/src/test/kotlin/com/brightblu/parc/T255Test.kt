package com.brightblu.parc

import com.brightblu.parc.core.data.library.ReaderFamily
import com.brightblu.parc.core.data.library.ReaderSettings
import com.brightblu.parc.core.data.library.ReaderSettingsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T255: the reader's comfort choices are kept as one set for every reader. */
class T255Test {
    @Test
    fun brightnessFarLeftMeansThePhoneAndTheRestIsClamped() {
        val s = ReaderSettings()
        assertEquals(0f, s.withBrightness(0.01f).brightness, 0f)
        assertEquals(0f, s.withBrightness(Float.NaN).brightness, 0f)
        assertEquals(0.4f, s.withBrightness(0.4f).brightness, 0f)
        assertEquals(1f, s.withBrightness(7f).brightness, 0f)
    }

    @Test
    fun comfortChoicesSurviveSaveAndOldDocumentsReadAsDefaults() {
        val custom = ReaderSettings(family = ReaderFamily.Figtree).withBrightness(0.3f)
            .copy(night = true, volumeKeys = true)
        assertEquals(custom, ReaderSettingsJson.decode(ReaderSettingsJson.encode(custom)))
        assertTrue(ReaderSettingsJson.encode(custom).length <= ReaderSettingsJson.MaxChars)
        val old = ReaderSettingsJson.decode("""{"fontSp":20,"family":"Sans"}""")
        assertFalse(old.night)
        assertFalse(old.volumeKeys)
        assertEquals(0f, old.brightness, 0f)
        assertEquals(0f, ReaderSettingsJson.decode("""{"brightness":-3}""").brightness, 0f)
        assertEquals(1f, ReaderSettingsJson.decode("""{"brightness":9}""").brightness, 0f)
    }
}
