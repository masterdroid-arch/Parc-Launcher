package com.brightblu.parc

import com.brightblu.parc.model.LookRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPUS1: the pure rules behind the settings samples. */
class SettingsSampleRulesTest {
    @Test fun iconSampleGrowsWithTheIconSize() {
        assertEquals(0.5f, SettingsSampleRules.iconFraction(LookRules.MinIconSizeDp), 0.001f)
        assertEquals(1f, SettingsSampleRules.iconFraction(LookRules.MaxIconSizeDp), 0.001f)
        val steps = LookRules.IconSizeSteps.map(SettingsSampleRules::iconFraction)
        assertTrue(steps.zipWithNext().all { (a, b) -> b > a })
        assertEquals(0.5f, SettingsSampleRules.iconFraction(0), 0.001f)
        assertEquals(1f, SettingsSampleRules.iconFraction(200), 0.001f)
    }

    @Test fun dimDarkensThePhoneColourAndKeepsItOpaque() {
        val colour = 0xFF8040C0.toInt()
        assertEquals(0xFF8040C0.toInt(), SettingsSampleRules.dimmed(colour, 0))
        assertEquals(0xFF000000.toInt(), SettingsSampleRules.dimmed(colour, 100))
        assertEquals(0xFF402060.toInt(), SettingsSampleRules.dimmed(colour, 50))
        assertEquals(0xFF000000.toInt(), SettingsSampleRules.dimmed(0x00FFFFFF, 100))
    }
}
