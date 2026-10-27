package com.brightblu.parc

import com.brightblu.parc.core.data.focus.FocusDay
import com.brightblu.parc.core.data.focus.FocusMode
import com.brightblu.parc.core.data.focus.FocusSchedule
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** OPUS6 (audit F8): what the Do Not Disturb receiver does when its alarm fires. */
class FocusDndPlanTest {
    private val zone = ZoneId.of("Europe/Lisbon")
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val night = FocusMode(name = "Night", syncDnd = true,
        schedule = FocusSchedule(FocusDay.entries.toSet(), startMinute = 23 * 60, endMinute = 7 * 60))

    @Test fun duringTheWindowDndIsOnAndTheNextCheckIsTheEnd() {
        val (active, next) = FocusDnd.plan(at(26, 23, 30), listOf(night), null, zone)
        assertTrue(active)
        assertEquals(at(27, 7), next)
    }

    @Test fun afterTheWindowDndIsOffEvenIfParcWasKilledMeanwhile() {
        // The alarm fires at 07:00 with no running process: the receiver turns the rule off.
        val (active, next) = FocusDnd.plan(at(27, 7), listOf(night), null, zone)
        assertFalse(active)
        assertEquals(at(27, 23), next)
    }

    @Test fun noAlarmWhenNoModeSyncsDnd() {
        val plain = night.copy(syncDnd = false)
        val (active, next) = FocusDnd.plan(at(26, 23, 30), listOf(plain), null, zone)
        assertFalse(active)
        assertNull(next)
        assertNull(FocusDnd.nextCheck(listOf(plain), at(27, 7)))
        assertEquals(at(27, 7), FocusDnd.nextCheck(listOf(night), at(27, 7)))
    }
}
