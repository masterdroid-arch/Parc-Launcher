package com.brightblu.parc

import com.brightblu.parc.ring.HapticPreset
import com.brightblu.parc.ring.Ringer
import org.junit.Assert.*
import org.junit.Test

/** T101: Timers page in the stats hub: catalog, saved-config migration and the alert rules. */
class T101Test {
    @Test fun timersPageIsInTheCatalogBeforeAgendaWithThreeTiles() {
        assertEquals(HubCatalog.pages.indexOf("agenda") - 1, HubCatalog.pages.indexOf("timers"))
        assertEquals(listOf("timers", "stopwatch", "next alarm"), HubCatalog.tiles("timers"))
        assertTrue("timers" in HubConfig().pages)
        assertEquals(listOf("timers", "stopwatch", "next alarm"), HubConfig().tiles("timers"))
        assertTrue(HubConfig().hiddenTiles["timers"].isNullOrEmpty())
    }

    @Test fun configSavedAfterTheClockPageStillGetsTimersBeforeAgenda() {
        // A document written by the version that had clock and agenda but no timers.
        val old = "clock:true::;system:true:battery:;power:true:charging:;network:true:traffic:;apps:true:new apps:;" +
            "sound:true:media:;agenda:true:next events:;media:true:now playing:;comms:true::"
        val config = HubConfig.decode(old)
        assertEquals(listOf("clock", "system", "power", "network", "apps", "sound", "timers", "agenda", "media", "focus", "comms"), config.pages)
        assertTrue("timers" !in config.hiddenPages)
        assertEquals("timers", restoredHubPage("timers", config))
        assertEquals(config.encode(), HubConfig.decode(config.encode()).encode())
    }

    @Test fun movedPagesKeepTheUsersOrderWhenTimersIsAdded() {
        val old = "clock:true::;comms:true::;media:true:now playing:;agenda:true:next events:;sound:true:media:;" +
            "apps:true:new apps:;network:true:traffic:;power:true:charging:;system:true:battery:"
        val config = HubConfig.decode(old)
        assertEquals(listOf("clock", "focus", "comms", "media", "timers", "agenda", "sound", "apps", "network", "power", "system"), config.pages)
    }

    @Test fun timersPageAndTilesCanBeHiddenAndSurviveEncoding() {
        val config = HubConfig().showTile("timers", "stopwatch", false).showPage("timers", false)
        assertTrue("timers" !in config.pages)
        val restored = HubConfig.decode(config.encode())
        assertTrue("timers" !in restored.pages)
        assertEquals(setOf("stopwatch"), restored.hiddenTiles["timers"])
        assertEquals(listOf("stopwatch"), HubConfig().move("timers", "stopwatch", -1).tiles("timers").take(1))
    }

    @Test fun vibrationFollowsTheFeelHapticAndRinger() {
        assertNull(TimerAlertRules.vibration(HapticPreset.Off, Ringer.Normal))
        assertNull(TimerAlertRules.vibration(HapticPreset.Crisp, Ringer.Silent))
        val soft = TimerAlertRules.vibration(HapticPreset.Soft, Ringer.Normal)!!
        val crisp = TimerAlertRules.vibration(HapticPreset.Crisp, Ringer.Vibrate)!!
        val sharp = TimerAlertRules.vibration(HapticPreset.Sharp, Ringer.Normal)!!
        assertTrue(soft.max() < crisp.max() && crisp.max() < sharp.max())
        listOf(soft, crisp, sharp).forEach { assertEquals(0L, it.first()); assertEquals(0, it.size % 2) }
    }

    @Test fun notificationTextNamesTheDuration() {
        assertEquals("10 min timer finished", TimerAlertRules.text(600_000))
        assertEquals("1 h 30 min timer finished", TimerAlertRules.text(5_400_000))
        assertEquals("Timer done", TimerAlertRules.Title)
        assertEquals(7101, TimerAlertRules.notificationId(1))
        assertNotEquals(TimerAlertRules.notificationId(1), TimerAlertRules.notificationId(2))
    }
}
