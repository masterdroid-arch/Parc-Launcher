package com.brightblu.parc

import com.brightblu.parc.model.Metrics
import com.brightblu.parc.ring.HubGesture
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class T116Test {
    @Test fun everyPageHasItsOwnContentAndAnExistingFullSettingsDestination() {
        assertEquals(HubCatalog.pages.toSet(), hubPageOptions.keys)
        assertEquals(listOf("notes", "timers", "agenda", "apps", "comms"), hubPageOptions.keys.take(5))
        hubPageOptions.forEach { (_, spec) ->
            assertTrue(spec.shows.isNotBlank())
            assertTrue(spec.tap.isNotBlank())
            assertTrue(spec.fullSettings in SettingsPage.entries)
        }
        assertEquals(SettingsPage.Agenda, hubPageOptions.getValue("agenda").fullSettings)
        assertEquals(SettingsPage.Comms, hubPageOptions.getValue("comms").fullSettings)
        assertEquals(SettingsPage.Look, hubPageOptions.getValue("clock").fullSettings)
        assertTrue(hubPageOptions.getValue("clock").tap.contains("Tap the clock"))
    }

    @Test fun moveSkipsHiddenNeighboursAndPersistsAcrossTheExistingDocumentFormat() {
        val original = legacyHubConfig().showPage("notes", true).showPage("power", false)
            .showTile("apps", "new apps", false).move("apps", "biggest apps", -1)
        val moved = original.moveVisiblePage("network", -1)
        val restored = HubConfig.decode(moved.encode())
        assertEquals(listOf("clock", "network", "system"), restored.pages.take(3))
        assertEquals(original.hiddenPages, restored.hiddenPages)
        assertEquals(original.tiles("apps"), restored.tiles("apps"))
        HubCatalog.pages.forEach { assertEquals(original.hiddenTiles[it].orEmpty(), restored.hiddenTiles[it].orEmpty()) }
        assertEquals(original.encode(), restored.moveVisiblePage("network", 1).encode())
    }

    @Test fun removeAndUndoKeepContentAndOtherLaterChanges() {
        val before = HubConfig().showPage("notes", true)
        val hidden = HubConfig.decode(before.showPage("notes", false).encode())
        assertFalse("notes" in hidden.pages)
        val later = hidden.showTile("timers", "stopwatch", false)
        val restored = HubConfig.decode(later.showPage("notes", true).encode())
        assertTrue("notes" in restored.pages)
        assertEquals(before.orderedPages, restored.orderedPages)
        assertTrue("stopwatch" in restored.hiddenTiles.getValue("timers"))
    }

    @Test fun movementAtEitherEndOrOfHiddenPageDoesNothing() {
        val c = HubConfig()
        assertEquals(c, c.moveVisiblePage("clock", -1))
        assertEquals(c, c.moveVisiblePage("comms", 1))
        assertEquals(c, c.moveVisiblePage("notes", 1))
        assertEquals(c, c.moveVisiblePage("unknown", 1))
        assertEquals(c.showPage("clock", false).encode(), HubConfig.decode(c.showPage("clock", false).encode()).encode())
    }

    @Test fun oldDocumentsKeepTheirChoicesAndGainNoNewStoredKeys() {
        val old = "system:true:battery,ram:ram;power:false:charging:;apps:true:new apps:"
        val restored = HubConfig.decode(old)
        assertTrue("power" in restored.hiddenPages)
        assertTrue("notes" in restored.hiddenPages)
        assertTrue("ram" in restored.hiddenTiles.getValue("system"))
        assertEquals("new apps", restored.tiles("apps").first())
        assertEquals(restored.encode(), HubConfig.decode(restored.encode()).encode())
    }

    @Test fun fakeClockAimingSurvivesHoldDeadlineAndDoesNotTurnIntoLongPress() = runTest {
        val g = HubGesture()
        g.down(true, testScheduler.currentTime)
        advanceTimeBy(100)
        g.move(Metrics.aimDeadZoneDp + 1)
        advanceTimeBy(Metrics.longPressMs * 3)
        assertTrue(g.aiming)
        assertFalse(g.longPress(testScheduler.currentTime))
        assertEquals(4, g.release(80f, testScheduler.currentTime, 4))
    }

    @Test fun fakeClockOutsidePageButtonAndSwipeCannotBecomeHubHoldOrTap() = runTest {
        val g = HubGesture()
        // The page-options button is in the top panel, outside the ring hub hit area.
        g.down(false, testScheduler.currentTime)
        advanceTimeBy(Metrics.longPressMs)
        assertFalse(g.longPress(testScheduler.currentTime))
        assertEquals(-1, g.release(0f, testScheduler.currentTime, -1))
        g.down(true, testScheduler.currentTime)
        g.cancel() // the existing parent swipe takes ownership
        advanceTimeBy(Metrics.longPressMs)
        assertFalse(g.longPress(testScheduler.currentTime))
        assertEquals(-1, g.release(0f, testScheduler.currentTime, -1))
        g.down(true, testScheduler.currentTime)
        advanceTimeBy(100)
        assertEquals(HubGesture.TAP, g.release(0f, testScheduler.currentTime, -1, 0f))
    }
}
