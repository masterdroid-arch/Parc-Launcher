package com.brightblu.parc.feature.settings

import com.brightblu.parc.feature.settings.ui.SettingsLayoutRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPUS1: short values sit beside a row's title, long ones under it, empty ones nowhere. */
class SettingsLayoutRulesTest {
    @Test fun shortValuesSitBeside() {
        listOf("On", "85 %", "44 dp", "Midnight", "4 arcs", "Hidden").forEach { assertTrue(it, SettingsLayoutRules.valueBeside(it)) }
    }

    @Test fun longValuesGoUnderTheTitle() {
        listOf("My phone’s wallpaper", "Hub double-tap: On", "3 extra sources enabled").forEach {
            assertFalse(it, SettingsLayoutRules.valueBeside(it))
        }
    }

    @Test fun blankValuesAreNotShown() {
        assertFalse(SettingsLayoutRules.valueBeside(""))
        assertFalse(SettingsLayoutRules.valueBeside("  "))
    }

    @Test fun aBuildStampRowMayKeepItsLongValueBesideTheTitle() {
        val builtOn = "2026-11-21 17:23"
        assertFalse(SettingsLayoutRules.valueBeside(builtOn))
        assertTrue(SettingsLayoutRules.valueBeside(builtOn, allowLong = true))
        assertTrue(SettingsLayoutRules.valueBeside("1.5.8", allowLong = true))
        assertFalse(SettingsLayoutRules.valueBeside("", allowLong = true))
    }
}
