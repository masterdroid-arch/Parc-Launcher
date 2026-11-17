package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.ledger.DayLedger
import com.brightblu.parc.core.data.ledger.LedgerDay
import com.brightblu.parc.core.data.rhythm.RhythmBins
import com.brightblu.parc.core.data.rhythm.RhythmOptions
import com.brightblu.parc.core.data.rhythm.RhythmView
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T188: how the Rhythm page is registered, and what its presentation says. */
class T188Test {
    private val today = LocalDate.of(2026, 11, 10)

    @Test fun theRhythmPageIsInTheCatalogHiddenForOldAndNewUsersAndHasNoTiles() {
        assertTrue("rhythm" in HubCatalog.pages)
        assertTrue("rhythm" in HubCatalog.defaultHiddenPages)
        assertTrue("rhythm" in HubCatalog.newUserHiddenPages)
        assertTrue("rhythm" in HubCatalog.tilelessPages)
        assertTrue("rhythm" in HubCatalog.m13Pages)
        assertTrue(HubCatalog.tiles("rhythm").isEmpty())
        assertFalse("rhythm" in HubConfig().pages)
        assertNotNull(hubPageOptions["rhythm"])
        assertNotNull(hubTemplates["rhythm"])
        assertEquals("Rhythm", hubPageName("rhythm"))
    }

    @Test fun rhythmSitsAfterStepsAndAnOldSavedConfigGainsItHidden() {
        assertEquals(HubCatalog.pages.indexOf("steps") + 1, HubCatalog.pages.indexOf("rhythm"))
        val old = "clock:true::;system:true:battery:;network:true:traffic:;apps:true:new apps:;sound:true:media:"
        val config = HubConfig.decode(old)
        assertTrue("rhythm" in config.hiddenPages)
        assertFalse("rhythm" in config.pages)
        assertTrue("rhythm" in HubConfig.decode(config.encode()).hiddenPages)
        assertTrue("rhythm" in HubConfig.decode(HubConfig().showPage("rhythm", true).encode()).pages)
    }

    @Test fun theDemoHubHasARhythmPage() {
        assertTrue("rhythm" in DemoHubPages)
    }

    @Test fun theBackupCarriesTheWakingHours() {
        assertNotNull(BackupSpecs.hub.key("rhythm_options"))
    }

    @Test fun theRingIsDescribedInWordsForTalkBack() {
        val bins = BooleanArray(RhythmBins.Count).also { it[96] = true; it[97] = true }
        val ledger = DayLedger.Empty.withDay(LedgerDay(today, screenOnMillis = 600_000L, pickups = 4, firstPickupMinute = 485,
            phoneFreeMillis = 7_200_000L, rhythmBins = RhythmBins.encode(bins)))
        val shown = RhythmView.show(ledger, today, today, 0L, 86_400_000L)
        assertEquals("Day ring for Today, midnight at the top. The screen was on 08:00 to 08:10", RhythmPresentation.ringDescription(shown, today))
        assertEquals("First pickup 08:05. Pickups 4. Screen on 10 min. Longest phone-free stretch 2 h 00 min between 06:00 and 24:00",
            RhythmPresentation.numbersDescription(shown, RhythmOptions()))
    }

    @Test fun anUnrecordedDaySaysSoInsteadOfAnEmptyRing() {
        val shown = RhythmView.show(DayLedger.Empty, today.minusDays(1), today, 0L, 86_400_000L)
        assertEquals("Day ring for Yesterday. Not recorded", RhythmPresentation.ringDescription(shown, today))
        assertEquals("First pickup none. Pickups none. Screen on —. Longest phone-free stretch — between 06:00 and 24:00",
            RhythmPresentation.numbersDescription(shown, RhythmOptions()))
    }
}
