package com.brightblu.parc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** T411: first-run tips queue (one at a time, most important first) instead of stacking. */
class T411Test {
    private val none = emptySet<String>()

    @Test fun gettingAroundComesFirstThenHomeChipsThenPageName() {
        assertEquals(TipKind.GettingAround, TipQueue.pick(false, none))
        // Even with every chip already seen, an unseen card still wins.
        assertEquals(TipKind.GettingAround, TipQueue.pick(false, GestureHintRules.allIds()))
        assertEquals(TipKind.HomeGesture, TipQueue.pick(true, none))
        assertEquals(TipKind.HomeGesture, TipQueue.pick(true, setOf("turn_arc", "search", "hub")))
        assertEquals(TipKind.PageName, TipQueue.pick(true, setOf("turn_arc", "search", "hub", "edit")))
        assertNull(TipQueue.pick(true, GestureHintRules.allIds()))
    }

    @Test fun onlyThePickedTipIsAllowedSoTwoCanNeverBeUpTogether() {
        val latch = TipLatch()
        // Fresh install: the card is picked, the chips are refused.
        assertTrue(latch.allowed(TipKind.GettingAround, false, none))
        assertFalse(latch.claim(TipKind.PageName, false, none))
        assertFalse(latch.claim(TipKind.HomeGesture, false, none))
        // Of the three kinds, exactly one is allowed.
        val fresh = TipLatch()
        assertEquals(1, TipKind.entries.count { fresh.allowed(it, false, none) })
    }

    @Test fun theNextTipWaitsForTheNextStartEvenAfterGotIt() {
        val latch = TipLatch()
        assertTrue(latch.allowed(TipKind.GettingAround, false, none))
        // The person taps "Got it": the card is seen, but this start's pick does not change.
        assertFalse(latch.allowed(TipKind.HomeGesture, true, none))
        assertFalse(latch.claim(TipKind.HomeGesture, true, none))
        assertFalse(latch.claim(TipKind.PageName, true, none))
        // The next start (a new latch) offers the first chip.
        val nextStart = TipLatch()
        assertTrue(nextStart.claim(TipKind.HomeGesture, true, none))
    }

    @Test fun aChipIsClaimedOnceAndASecondAskIsRefusedInTheSameStart() {
        val latch = TipLatch()
        assertTrue(latch.claim(TipKind.HomeGesture, true, none))
        assertFalse(latch.claim(TipKind.HomeGesture, true, setOf("turn_arc")))
        assertFalse(latch.claim(TipKind.PageName, true, setOf("turn_arc")))
    }

    @Test fun showHintsAgainPicksAfresh() {
        val latch = TipLatch()
        assertTrue(latch.claim(TipKind.HomeGesture, true, none))
        latch.reset()
        assertTrue(latch.claim(TipKind.HomeGesture, true, setOf("turn_arc")))
    }

    @Test fun nothingIsAllowedWhenEverythingIsSeen() {
        val latch = TipLatch()
        for (kind in TipKind.entries) assertFalse(latch.allowed(kind, true, GestureHintRules.allIds()))
    }

    @Test fun theWhatsNewDialogHoldsTipsBackAndReleasesThem() {
        val latch = TipLatch()
        latch.setWhatsNewOpen(true)
        assertFalse(latch.allowed(TipKind.GettingAround, false, none))
        assertFalse(latch.claim(TipKind.HomeGesture, true, none))
        latch.setWhatsNewOpen(false)
        // The pick was made once; closing the dialog lets the picked tip in (the card here).
        assertTrue(latch.allowed(TipKind.GettingAround, false, none))
    }

    @Test fun aTipOnScreenIsReportedSoTheNewAppBarCanWait() {
        val latch = TipLatch()
        assertFalse(latch.tipOnScreen)
        latch.setShowing(TipKind.PageName, true)
        assertTrue(latch.tipOnScreen)
        latch.setShowing(TipKind.PageName, false)
        assertFalse(latch.tipOnScreen)
    }

    @Test fun everySurfaceAsksTheQueueBeforeItDraws() {
        val dirs = listOf("src/main/kotlin/com/brightblu/parc", "app/src/main/kotlin/com/brightblu/parc")
        fun source(name: String) = dirs.map { File(it, name) }.first { it.exists() }.readText()
        assertTrue(source("LauncherHome.kt").contains("TipKind.GettingAround"))
        assertTrue(source("LauncherHome.kt").contains("TipKind.HomeGesture"))
        assertTrue(source("HubPages.kt").contains("TipKind.PageName"))
        assertTrue(source("LauncherHome.kt").contains("TipSession.latch.tipOnScreen"))
        assertTrue(source("ReleaseNotesUi.kt").contains("setWhatsNewOpen"))
    }
}
