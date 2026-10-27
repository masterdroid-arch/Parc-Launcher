package com.brightblu.parc.core.data.demo

import com.brightblu.parc.model.AppId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T130: the demo-only icon tables and gate. No Android runtime is needed. */
class DemoIconsTest {

    @Test
    fun paletteHasExactlyEightDistinctColours() {
        val colors = DemoIconPalette.colors
        assertEquals(8, colors.size)
        assertEquals(colors.size, colors.toSet().size)
        assertTrue(colors.all { it ushr 24 == 0xFFL })
    }

    @Test
    fun everySampleKeyHasAStableRecipe() {
        assertEquals(DemoIconCatalog.sampleKeys.size, DemoIconCatalog.sampleKeys.toSet().size)
        DemoIconCatalog.sampleKeys.forEach { key ->
            val spec = DemoIconCatalog.specFor(AppId("demo/$key"))
            assertNotNull("No icon recipe for $key", spec)
            assertTrue(spec!!.colorIndex in DemoIconPalette.colors.indices)
        }
    }

    @Test
    fun theTableCoversEveryFixtureKeyAndNothingElse() {
        // The fixture key list is the source of truth for the demo home in :app.
        val unknown = AppId("demo/not-in-the-fixture")
        assertNull(DemoIconCatalog.specFor(unknown))
        assertNull(DemoIconCatalog.specFor(AppId("com.android.chrome")))
        assertNull(DemoIconCatalog.specFor(AppId("demo/")))
    }

    @Test
    fun allThreeSilhouettesAreUsed() {
        val shapes = DemoIconCatalog.sampleKeys.mapNotNull { DemoIconCatalog.specFor(AppId("demo/$it"))?.shape }.toSet()
        assertEquals(DemoIconShape.entries.toSet(), shapes)
    }

    @Test
    fun theKnownIconsKeepTheirRecipes() {
        assertEquals(DemoIconSpec(DemoIconShape.Squircle, 6, DemoGlyph.Lines),
            DemoIconCatalog.specFor(AppId("demo/notes")))
        assertEquals(DemoIconSpec(DemoIconShape.Circle, 1, DemoGlyph.Pin),
            DemoIconCatalog.specFor(AppId("demo/maps")))
        assertEquals(DemoIconSpec(DemoIconShape.RoundedSquare, 3, DemoGlyph.Camera),
            DemoIconCatalog.specFor(AppId("demo/camera")))
    }

    @Test
    fun demoGateIsClosedWhenDemoIsOff() {
        assertFalse(DemoIcons.shouldDraw(demo = false, id = AppId("demo/notes")))
        assertNull(DemoIcons.iconFor(demo = false, id = AppId("demo/notes"), sizePx = 144))
    }

    @Test
    fun demoGateNeverAcceptsARealAppId() {
        assertFalse(DemoIcons.shouldDraw(demo = true, id = AppId("com.android.chrome")))
        assertFalse(DemoIcons.shouldDraw(demo = true, id = AppId("org.telegram.messenger")))
        assertNull(DemoIcons.iconFor(demo = true, id = AppId("com.android.chrome"), sizePx = 144))
    }

    @Test
    fun demoGateAcceptsEveryFixtureAppOnlyWhenOn() {
        DemoIconCatalog.sampleKeys.forEach { key ->
            assertTrue(DemoIcons.shouldDraw(demo = true, id = AppId("demo/$key")))
            assertFalse(DemoIcons.shouldDraw(demo = false, id = AppId("demo/$key")))
        }
    }

    @Test
    fun colourLookupIsAlwaysInRange() {
        DemoIconCatalog.sampleKeys.forEach { key ->
            val spec = DemoIconCatalog.specFor(AppId("demo/$key"))!!
            assertTrue(DemoIconCatalog.colorOf(spec) in DemoIconPalette.colors)
        }
    }
}
