package com.brightblu.parc.model

import org.junit.Assert.*
import org.junit.Test

/** T172: readability for glass cards without making them solid. */
class GlassReadabilityTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test fun backdropOfCellsHasTheMeanAndTheDarkestAndLightestCell() {
        val b = Backdrop.of(intArrayOf(grey(0), grey(100), grey(200), grey(100)))
        assertEquals(grey(100), b.mean)
        assertEquals(grey(0), b.low)
        assertEquals(grey(200), b.high)
        assertEquals(grey(128), Backdrop.of(IntArray(0)).mean and 0xFFFFFFFF.toInt()) // empty: mid grey, never a crash
    }

    @Test fun lightCardOverABrightPictureKeepsItsDarkInkAndItsRequestedTint() {
        val ink = 0xFF101820.toInt()
        val card = GlassReadability.resolve(0xFFB8C8E8.toInt(), listOf(ink, white), 128, Backdrop.of(intArrayOf(grey(235))))
        assertEquals(ink, card.ink)
        assertEquals(128, card.alpha)
    }

    @Test fun inkFollowsTheMeanLuminanceOfWhatIsBehind() {
        val surface = 0xFFB8C8E8.toInt()
        val dark = 0xFF101820.toInt(); val light = 0xFFF4F7FB.toInt()
        val overBright = GlassReadability.resolve(surface, listOf(dark, light), 128, Backdrop(grey(235), grey(200), white))
        val overDark = GlassReadability.resolve(surface, listOf(dark, light), 128, Backdrop(grey(20), black, grey(60)))
        assertEquals(dark, overBright.ink)
        assertEquals(light, overDark.ink)
    }

    @Test fun readabilityNeverRaisesTheTintAboveSixtyPercentButNeverLowersTheUsersChoice() {
        for (mean in 0..255 step 5) for (requested in listOf(51, 77, 128, 153, 200, 255)) {
            val card = GlassReadability.resolve(0xFF1F5A3A.toInt(), listOf(0xFFF1F4EC.toInt()), requested, Backdrop(grey(mean), grey(0), grey(255)))
            assertTrue("mean $mean requested $requested alpha ${card.alpha}", card.alpha >= requested)
            assertTrue("mean $mean requested $requested alpha ${card.alpha}", card.alpha <= maxOf(requested, GlassReadability.TintCap))
        }
        assertEquals(153, GlassReadability.TintCap)
    }

    @Test fun aPhotoWithBothBrightSkyAndDarkGroundIsNotSolid() {
        // The case from the owner's phone: the old rule wanted 7:1 over the lightest and darkest pixel and came out near 100 percent.
        val photo = Backdrop(mean = grey(120), low = grey(10), high = grey(250))
        val card = GlassReadability.resolve(0xFF1F5A3A.toInt(), listOf(0xFFF1F4EC.toInt()), 128, photo)
        assertTrue("alpha ${card.alpha}", card.alpha <= GlassReadability.TintCap)
    }

    @Test fun neutralInkAlwaysReachesFourPointFiveOverTheMean() {
        // The skin's ink fails everywhere here (mid grey card over mid grey picture), so black or white takes over.
        for (mean in 0..255 step 3) for (surfaceGrey in listOf(0, 90, 128, 180, 255)) {
            val surface = grey(surfaceGrey)
            val b = Backdrop(grey(mean), grey(mean), grey(mean))
            for (requested in listOf(51, 128, 153)) {
                val card = GlassReadability.resolve(surface, listOf(grey(128)), requested, b)
                assertTrue("mean $mean surface $surfaceGrey req $requested",
                    GlassReadability.meanContrast(surface, card.ink, card.alpha, b) >= GlassReadability.MeanContrast)
            }
        }
    }

    @Test fun extremesAtThreeToOneAreWantedOnlyWhenSomeTintWithinTheCapReachesThem() {
        val surface = 0xFF1F5A3A.toInt(); val ink = 0xFFF1F4EC.toInt()
        fun extremesOk(b: Backdrop, alpha: Int) = listOf(b.low, b.high).all {
            WallpaperReadability.contrast(ink, WallpaperReadability.composite(surface, it, alpha / 255f)) >= GlassReadability.ExtremeContrast }
        // Mild picture: the tint rises until both ends are at 3:1 as well as the mean at 4.5:1.
        val mild = Backdrop(grey(60), grey(20), grey(110))
        val a = GlassReadability.resolve(surface, listOf(ink), 77, mild)
        assertEquals(ink, a.ink)
        assertTrue(extremesOk(mild, a.alpha))
        // Sky and ground: no tint within the cap lifts the bright end to 3:1; the mean alone decides, and the mean still passes.
        val photo = Backdrop(grey(150), grey(0), grey(255))
        assertTrue((77..GlassReadability.TintCap).none { extremesOk(photo, it) })
        val b = GlassReadability.resolve(surface, listOf(ink), 77, photo)
        assertTrue(GlassReadability.meanContrast(surface, b.ink, b.alpha, photo) >= GlassReadability.MeanContrast)
        assertTrue(b.alpha <= GlassReadability.TintCap)
    }

    @Test fun hazeBackdropLiesBetweenItsBgAndLine() {
        val h = Backdrop.haze(grey(20), grey(200))
        assertEquals(grey(20), h.low)
        val l = WallpaperReadability.luminance(h.mean)
        assertTrue(l > WallpaperReadability.luminance(h.low) && l < WallpaperReadability.luminance(h.high))
    }

    @Test fun opacityDefaultsRangesAndMigration() {
        assertEquals(50, HubOpacity.DefaultGlass)
        assertEquals(85, HubOpacity.Default)
        assertEquals(20, HubOpacity.stepsFor(true).first())
        assertEquals(40, HubOpacity.stepsFor(false).first())
        assertEquals(100, HubOpacity.stepsFor(true).last())
        assertEquals(20, HubOpacity.clamp(5, glass = true))
        assertEquals(40, HubOpacity.clamp(5))
        // No value of its own: each mode's default. A saved value stays (a glass-only value below 40 is lifted when flat).
        assertEquals(50, HubOpacity.resolve(null, glass = true))
        assertEquals(85, HubOpacity.resolve(null, glass = false))
        assertEquals(60, HubOpacity.resolve(60, glass = true))
        assertEquals(30, HubOpacity.resolve(30, glass = true))
        assertEquals(40, HubOpacity.resolve(30, glass = false))
        // T177: the old Panel opacity migrates only when it was moved from its default, and never over a hub's own value.
        assertEquals(HubAppearance(), HubOpacity.migratePanel(HubAppearance(), 85))
        assertEquals(HubAppearance(arcOpacity = 60, statsOpacity = 60), HubOpacity.migratePanel(HubAppearance(), 60))
        assertEquals(HubAppearance(arcOpacity = 30, statsOpacity = 60), HubOpacity.migratePanel(HubAppearance(arcOpacity = 30), 60))
        val once = HubOpacity.migratePanel(HubAppearance(), 45)
        assertEquals(once, HubOpacity.migratePanel(once, 45))
        assertEquals(50, HubAppearance().arcOpacityPercent(glass = true))
        assertEquals(50, HubAppearance().statsOpacityPercent(glass = true))
        assertEquals(85, HubAppearance().arcOpacityPercent())
    }
}
