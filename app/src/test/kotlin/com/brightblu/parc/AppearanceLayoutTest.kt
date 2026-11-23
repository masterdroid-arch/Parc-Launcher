package com.brightblu.parc

import com.brightblu.parc.model.HubOpacity
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T374: the pure rules behind the Appearance page, the skin page, the Home sample and the Arcs controls. */
class AppearanceLayoutTest {
    private val home = 384f to 853f

    // ---- Home sample crops ----
    @Test fun arcsCropSitsAtTheOriginCornerAndStaysInsideHome() {
        val right = HomeSampleRules.crop(HomeCrop.Arcs, home.first, home.second, 60f, 340f, 245f, 70f)
        assertEquals(home.first - right.width, right.left, 0.01f)
        val left = HomeSampleRules.crop(HomeCrop.Arcs, home.first, home.second, 60f, 40f, 245f, 70f)
        assertEquals(0f, left.left, 0.01f)
        listOf(right, left).forEach { c ->
            assertTrue(c.left >= 0f && c.top >= 0f)
            assertTrue(c.left + c.width <= home.first + 0.01f && c.top + c.height <= home.second + 0.01f)
        }
    }

    @Test fun aCropHasThePanesShape() {
        val c = HomeSampleRules.crop(HomeCrop.Arcs, home.first, home.second, 60f, 340f, 200f, 100f)
        assertEquals(200f / 100f, c.width / c.height, 0.01f)
    }

    @Test fun clockCropStartsUnderTheStatsCardTabStrip() {
        val c = HomeSampleRules.crop(HomeCrop.Clock, home.first, home.second, 60f, 340f, 245f, 90f)
        assertEquals(Metrics.panelGutterDp, c.left, 0.01f)
        assertEquals(60f + Metrics.settingsSampleClockTopDp, c.top, 0.01f)
    }

    @Test fun aTinyHomeStillGivesACropInsideIt() {
        val c = HomeSampleRules.crop(HomeCrop.Clock, 200f, 300f, 280f, 190f, 100f, 100f)
        assertTrue(c.left + c.width <= 200.01f && c.top + c.height <= 300.01f && c.top >= 0f)
    }

    @Test fun twoCropsGetATallerSampleAndTheThumbnailKeepsItsShape() {
        assertEquals(Metrics.settingsSampleHeightDp, HomeSampleRules.heightDp(HomeSampleFocus.Arcs), 0f)
        assertEquals(Metrics.settingsSampleTwoCropHeightDp, HomeSampleRules.heightDp(HomeSampleFocus.ClockAndArcs), 0f)
        val oneRatio = Metrics.settingsSampleThumbWidthDp / Metrics.settingsSampleHeightDp
        assertEquals(oneRatio, HomeSampleRules.thumbWidthDp(Metrics.settingsSampleTwoCropHeightDp) / Metrics.settingsSampleTwoCropHeightDp, 0.001f)
        assertEquals(listOf(HomeCrop.Clock, HomeCrop.Arcs), HomeSampleFocus.ClockAndArcs.crops)
    }

    // ---- Skin page: preview and apply are separate ----
    @Test fun applyNamesThePendingSkin() {
        val dusk = Skin.Midnight.copy(id = "dusk", name = "Dusk")
        val state = SkinPickerRules.apply(dusk, Skin.Midnight, readable = true)
        assertEquals("Apply Dusk", state.label); assertTrue(state.enabled); assertNull(state.note)
    }

    @Test fun theSkinInUseCannotBeAppliedAgain() {
        val state = SkinPickerRules.apply(Skin.Midnight, Skin.Midnight, readable = true)
        assertEquals("Midnight is in use", state.label); assertFalse(state.enabled)
    }

    @Test fun anUnreadableSkinCanBePreviewedButNotApplied() {
        val bad = Skin.Midnight.copy(id = "bad", name = "Murk")
        val state = SkinPickerRules.apply(bad, Skin.Midnight, readable = false)
        assertEquals("Apply Murk", state.label); assertFalse(state.enabled)
        assertEquals("Murk: text would be too hard to read", state.note)
    }

    @Test fun cancelShowsOnlyWhileAnotherSkinIsPending() {
        val dusk = Skin.Midnight.copy(id = "dusk", name = "Dusk")
        assertTrue(SkinPickerRules.showCancel(dusk, Skin.Midnight))
        assertFalse(SkinPickerRules.showCancel(Skin.Midnight, Skin.Midnight))
    }

    // ---- Registry choices: segments, stepper ----
    @Test fun shortChoicesAreSegmentsAndLongOnesAreNot() {
        assertTrue(RegistryChoiceRules.asSegments(listOf("3", "4")))
        assertTrue(RegistryChoiceRules.asSegments(listOf("Circle", "Squircle", "Rounded square", "None")))
        assertFalse(RegistryChoiceRules.asSegments(listOf("Thin", "Bold", "Mono", "Digital", "Hidden")))
        assertFalse(RegistryChoiceRules.asSegments(listOf("A", "B", "C", "A label that is far too long to fit")))
        assertFalse(RegistryChoiceRules.asSegments(emptyList()))
    }

    @Test fun theRegistryChoicesSplitAsTheDesignAsks() {
        val s = SettingsSnapshot()
        fun segments(id: String) = RegistryChoiceRules.asSegments(SettingsRegistry.byId.getValue(id).options(s))
        assertTrue(segments("ArcsShown")); assertTrue(segments("ArcLayout")); assertTrue(segments("IconShape"))
        assertTrue(segments("HubSize"))
        assertFalse(segments("ClockStyle"))
    }

    @Test fun iconsPerArcIsTheStepperAndItsOptionsAreAutoThenEachCount() {
        val def = SettingsRegistry.byId.getValue(RegistryChoiceRules.StepperId)
        val options = def.options(SettingsSnapshot(maxIconsPerArc = 6))
        assertEquals(listOf("Auto", "3", "4", "5", "6"), options)
    }

    @Test fun theStepperStopsAtBothEnds() {
        assertEquals(0, RegistryChoiceRules.step(0, 5, -1))
        assertEquals(1, RegistryChoiceRules.step(0, 5, 1))
        assertEquals(4, RegistryChoiceRules.step(4, 5, 1))
        assertEquals(3, RegistryChoiceRules.step(4, 5, -1))
        assertEquals(0, RegistryChoiceRules.step(0, 1, 1))
        assertEquals(0, RegistryChoiceRules.step(3, 0, 1))
    }

    @Test fun oneStepFromAutoWritesTheFirstCountThroughTheRegistry() {
        val def = SettingsRegistry.byId.getValue(RegistryChoiceRules.StepperId)
        val s = SettingsSnapshot(maxIconsPerArc = 6)
        val next = def.write!!(s, RegistryChoiceRules.step(def.read!!(s), def.options(s).size, 1))
        assertEquals(3, next.counts.icons)
        assertEquals(0, def.write!!(next, RegistryChoiceRules.step(def.read!!(next), def.options(next).size, -1)).counts.icons)
    }

    @Test fun oneTapOnTheArcCountWritesThatCount() {
        val def = SettingsRegistry.byId.getValue("ArcsShown")
        val s = SettingsSnapshot()
        assertEquals(listOf("3", "4"), def.options(s))
        assertEquals(3, def.write!!(s, 0).counts.arcs)
        assertEquals(4, def.write!!(s, 1).counts.arcs)
    }

    // ---- Card appearance: each hub's opacity is its own control ----
    @Test fun theStatsHubIsOfferedOnlyWhileItIsShown() {
        assertEquals(listOf(HubCardTarget.Arc, HubCardTarget.Stats), HubCardTarget.available(true))
        assertEquals(listOf(HubCardTarget.Arc), HubCardTarget.available(false))
    }

    @Test fun everyCardTargetPointsAtItsOwnRegistryRows() {
        HubCardTarget.entries.forEach { card ->
            assertEquals("${card.label} colour".lowercase(), "${SettingsRegistry.byId.getValue(card.colourId).fullTitle}".lowercase())
            assertTrue(SettingsRegistry.byId.getValue(card.opacityId).fullTitle.lowercase().startsWith(card.label.lowercase()))
        }
        assertNotEquals(HubCardTarget.Arc.opacityId, HubCardTarget.Stats.opacityId)
    }

    @Test fun eachOpacityChangeWritesOnlyItsOwnCard() {
        val s = SettingsSnapshot()
        val arc = SettingsRegistry.byId.getValue("ArcHubOpacity")
        val stats = SettingsRegistry.byId.getValue("StatsHubOpacity")
        val steps = HubOpacity.stepsFor(s.glass)
        val a = arc.write!!(s, steps.lastIndex)
        assertEquals(100, a.hub.arcOpacityPercent(a.glass))
        assertEquals(s.hub.statsOpacity, a.hub.statsOpacity)
        val b = stats.write!!(s, 0)
        assertEquals(steps.first(), b.hub.statsOpacityPercent(b.glass))
        assertEquals(s.hub.arcOpacity, b.hub.arcOpacity)
    }
}
