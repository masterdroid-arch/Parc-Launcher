package com.brightblu.parc.ring

import com.brightblu.parc.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OPUS8 (T133): empty-space hold arbitration and front names. */
class T133RingTest {
    private fun armed(layer: Layer = Layer.Home, editing: Boolean = false, onHub: Boolean = false,
        onSlot: Boolean = false, onSearchButton: Boolean = false, offered: Boolean = true) =
        EmptySpacePress.armed(layer, editing, onHub, onSlot, onSearchButton, offered)

    @Test fun aHoldOnEmptyHomeSpaceIsArmed() = assertTrue(armed())

    @Test fun theHubHoldAndJoystickKeepTheHub() = assertFalse(armed(onHub = true))

    @Test fun anAppOrFolderKeepsItsOwnMenu() = assertFalse(armed(onSlot = true))

    @Test fun theSearchButtonIsNotEmptySpace() = assertFalse(armed(onSearchButton = true))

    @Test fun editModeNeverOpensTheHomeMenu() = assertFalse(armed(editing = true))

    @Test fun onlyHomeOffersIt() {
        assertFalse(armed(layer = Layer.AllApps))
        assertFalse(armed(layer = Layer.Folder))
        assertFalse(armed(layer = Layer.Recents))
    }

    @Test fun aHostThatDoesNotOfferItLeavesTheFieldAsBefore() = assertFalse(armed(offered = false))

    @Test fun travelPastTheSlopATurnOrASecondFingerDropsTheHold() {
        assertTrue(EmptySpacePress.holds(0f, rotating = false, fingers = 1))
        assertTrue(EmptySpacePress.holds(EmptySpacePress.SlopDp, rotating = false, fingers = 1))
        // Search pull-down and swipe up both travel far before they act.
        assertFalse(EmptySpacePress.holds(EmptySpacePress.SlopDp + 1f, rotating = false, fingers = 1))
        assertFalse(EmptySpacePress.holds(1f, rotating = true, fingers = 1))
        assertFalse(EmptySpacePress.holds(1f, rotating = false, fingers = 2))
    }

    @Test fun frontNamesShowInAllAppsAndFoldersOnly() {
        assertTrue(FrontNames.shown(Layer.AllApps))
        assertTrue(FrontNames.shown(Layer.Folder))
        assertFalse(FrontNames.shown(Layer.Home))
        assertFalse(FrontNames.shown(Layer.Recents))
    }

    @Test fun theFrontOfATurningRingFollowsTheOffset() {
        assertEquals(0, FrontNames.frontItem(true, 0f, 30, 0))
        assertEquals(5, FrontNames.frontItem(true, 5f, 30, 0))
        assertEquals(5, FrontNames.frontItem(true, 4.6f, 30, 0))
        assertEquals(29, FrontNames.frontItem(true, -1f, 30, 0))
    }

    @Test fun theFrontOfAStillArcIsItsLowestItemAndAnEmptyArcHasNone() {
        assertEquals(2, FrontNames.frontItem(false, 3f, 4, 2))
        assertEquals(-1, FrontNames.frontItem(false, 0f, 0, -1))
    }

    @Test fun aTurningNameFadesOutBeforeTheNextOneShows() {
        assertEquals(1f, FrontNames.fade(0f), 0f)
        assertEquals(0.5f, FrontNames.fade(0.25f), 1e-6f)
        assertEquals(0f, FrontNames.fade(0.5f), 0f)
        assertEquals(0f, FrontNames.fade(-0.8f), 0f)
    }

    @Test fun overlapChecks() {
        assertTrue(FrontNames.rectHitsDisc(0f, 0f, 10f, 10f, 12f, 5f, 3f))
        assertFalse(FrontNames.rectHitsDisc(0f, 0f, 10f, 10f, 14f, 5f, 3f))
        assertTrue(FrontNames.rectsOverlap(0f, 0f, 10f, 10f, 9f, 9f, 5f, 5f))
        assertFalse(FrontNames.rectsOverlap(0f, 0f, 10f, 10f, 10f, 0f, 5f, 5f))
    }
}
