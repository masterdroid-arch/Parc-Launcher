package com.brightblu.parc

import com.brightblu.parc.model.ClockStyle
import com.brightblu.parc.model.IconShape
import com.brightblu.parc.model.LookRules
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.WallpaperStyle
import com.brightblu.parc.ring.RingInsets
import com.brightblu.parc.ring.RingLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** T71: the Look row and the geometry guarantee behind the icon-size slider. */
class T71Test {
    @Test fun theDefaultsLeaveTodaysLookUnchanged() {
        val look = LookSettings()
        assertEquals(IconShape.Circle, look.iconShape)
        assertEquals(Metrics.iconDp.toInt(), look.iconSizeDp)
        assertEquals(ClockStyle.Thin, look.clockStyle)
        assertEquals(WallpaperStyle.Black, look.wallpaper)
    }


    @Test fun referenceLayoutsKeepAPitchOfAtLeastSixtyAndAcceptEverySliderSize() {
        val screens = listOf(384f to 853f, 360f to 800f, 411f to 914f)
        for ((width, height) in screens) {
            val layout = RingLayout(width, height, RingInsets())
            val pitch = layout.minimumPitchDp()
            assertTrue("$width x $height pitch $pitch", pitch >= Metrics.minSlotPitchDp)
            LookRules.IconSizeSteps.forEach { requested ->
                val effective = LookRules.effectiveIconSizeDp(requested, pitch)
                assertEquals(requested.toFloat(), effective, 0f)
            }
            assertEquals(52f, LookRules.effectiveIconSizeDp(52, pitch), 0f)
        }
    }
}
