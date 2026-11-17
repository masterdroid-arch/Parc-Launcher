package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T242: a hold on a page name that never moved opens the quick options. */
class T242Test {
    @Test
    fun aHoldWithoutMovingOpensTheSheetButADragDoesNot() {
        assertTrue(HubTabHold.isQuickOptions(droppedOnOwnSlot = true, travelPx = 3f, slopPx = 8f))
        assertTrue(HubTabHold.isQuickOptions(true, 0f, 8f))
        assertFalse(HubTabHold.isQuickOptions(droppedOnOwnSlot = true, travelPx = 40f, slopPx = 8f))
        assertFalse(HubTabHold.isQuickOptions(droppedOnOwnSlot = false, travelPx = 2f, slopPx = 8f))
        assertFalse(HubTabHold.isQuickOptions(true, Float.NaN, 8f))
    }

    @Test
    fun theSheetsMoveAndHideChangesAreTheOptionsSheetsChanges() {
        val config = HubConfig()
        val visible = config.pages
        val second = visible[1]
        val moved = config.moveVisiblePage(second, -1)
        assertEquals(second, moved.pages.first())
        val hidden = config.showPage(second, false)
        assertFalse(second in hidden.pages)
        assertTrue(second in hidden.orderedPages)
    }
}
