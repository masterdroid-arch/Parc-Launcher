package com.brightblu.parc.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T324: the Dim slider reads 0 to 100 percent, and every value stored before T324 still loads. */
class T324DimTest {
    @Test fun dimStepsRunFromZeroToFullDarknessInTenPercentSteps() {
        assertEquals((0..100 step 10).toList(), LookRules.DimSteps)
        assertEquals(0, LookRules.DimSteps.first())
        assertEquals(100, LookRules.DimSteps.last())
        assertEquals(11, LookRules.DimSteps.size)
    }

    @Test fun aFullDimIsALegalSetting() {
        val look = LookSettings(wallpaper = WallpaperStyle.Image, wallpaperDimPercent = 100)
        assertEquals(100, look.wallpaperDimPercent)
        assertEquals(look, LookJson.decode(LookJson.encode(look)))
        assertEquals(0, LookSettings(wallpaperDimPercent = 0).wallpaperDimPercent)
    }

    @Test fun anyValueStoredBeforeT324StillLoads() {
        (0..80 step 10).forEach { old ->
            val decoded = LookJson.decode("{\"wallpaper\":\"System\",\"wallpaperDimPercent\":$old}")
            assertEquals(old, decoded.wallpaperDimPercent)
        }
        assertEquals(100, LookJson.decode("{\"wallpaperDimPercent\":100}").wallpaperDimPercent)
    }

    @Test fun valuesOutsideTheRangeOrBetweenStepsAreRejected() {
        assertTrue(runCatching { LookJson.decode("{\"wallpaperDimPercent\":110}") }.isFailure)
        assertTrue(runCatching { LookJson.decode("{\"wallpaperDimPercent\":45}") }.isFailure)
        assertTrue(runCatching { LookJson.decode("{\"wallpaperDimPercent\":-10}") }.isFailure)
        assertFalse(LookRules.DimSteps.contains(45))
    }
}
