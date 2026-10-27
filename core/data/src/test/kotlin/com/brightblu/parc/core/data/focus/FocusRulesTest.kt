package com.brightblu.parc.core.data.focus

import com.brightblu.parc.core.data.apps.HourMillis
import com.brightblu.parc.core.data.apps.epoch
import com.brightblu.parc.core.data.apps.nairobi
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure focus-schedule maths (T72): boundaries, overnight windows, zone handling, first-match order
 * and the manual override rules. All instants are built from local wall-clock times in Nairobi
 * (UTC+3, no DST) unless a test explicitly uses another zone.
 */
class FocusRulesTest {

    private val zone: ZoneId = nairobi

    /** October 2026: the 19th is a Monday, the 20th a Tuesday. */
    private fun at(day: Int, hour: Int, minute: Int = 0, month: Int = 10): Long =
        epoch(2026, month, day, hour, minute, zone)

    private fun scheduled(
        modes: List<FocusMode>,
        now: Long,
        override: FocusOverride? = null,
    ): FocusResolution = FocusRules.resolve(now, modes, override, zone)

    // --- schedule matching -------------------------------------------------

    @Test
    fun `a daytime window starts inclusive and ends exclusive`() {
        val schedule = schedule(
            days = setOf(FocusDay.Mon),
            startHour = 9,
            endHour = 17,
        )

        assertFalse(FocusRules.isScheduledActive(at(19, 8, 59), schedule, zone))
        assertTrue(FocusRules.isScheduledActive(at(19, 9, 0), schedule, zone))
        assertTrue(FocusRules.isScheduledActive(at(19, 16, 59), schedule, zone))
        assertFalse(FocusRules.isScheduledActive(at(19, 17, 0), schedule, zone))
        assertFalse(FocusRules.isScheduledActive(at(20, 12, 0), schedule, zone))
    }

    @Test
    fun `seconds inside a boundary minute do not change the answer`() {
        val schedule = schedule(days = setOf(FocusDay.Mon), startHour = 9, endHour = 17)

        assertTrue(FocusRules.isScheduledActive(at(19, 16, 59) + 59_999, schedule, zone))
        assertFalse(FocusRules.isScheduledActive(at(19, 17, 0) + 30_000, schedule, zone))
    }

    @Test
    fun `an overnight window runs into a next day that is not selected`() {
        val schedule = schedule(days = setOf(FocusDay.Mon), startHour = 22, endHour = 7)

        assertFalse(FocusRules.isScheduledActive(at(19, 21, 59), schedule, zone))
        assertTrue(FocusRules.isScheduledActive(at(19, 22, 0), schedule, zone))
        assertTrue(FocusRules.isScheduledActive(at(20, 6, 59), schedule, zone))
        assertFalse(FocusRules.isScheduledActive(at(20, 7, 0), schedule, zone))
        assertFalse(FocusRules.isScheduledActive(at(20, 22, 0), schedule, zone))
    }

    @Test
    fun `a morning does not look back at a night that started on an unselected day`() {
        val schedule = schedule(days = setOf(FocusDay.Mon), startHour = 22, endHour = 7)

        assertFalse(FocusRules.isScheduledActive(at(19, 6, 0), schedule, zone))
    }

    @Test
    fun `a full-day window lasts twenty-four hours from its start`() {
        val schedule = schedule(days = setOf(FocusDay.Mon), startHour = 8, endHour = 8)

        assertFalse(FocusRules.isScheduledActive(at(19, 7, 59), schedule, zone))
        assertTrue(FocusRules.isScheduledActive(at(19, 8, 0), schedule, zone))
        assertTrue(FocusRules.isScheduledActive(at(19, 23, 59), schedule, zone))
        assertTrue(FocusRules.isScheduledActive(at(20, 7, 59), schedule, zone))
        assertFalse(FocusRules.isScheduledActive(at(20, 8, 0), schedule, zone))
    }

    @Test
    fun `a disabled schedule and a schedule without days never activate`() {
        val disabled = schedule(days = setOf(FocusDay.Mon), enabled = false)
        val dayless = schedule(days = emptySet())

        assertFalse(FocusRules.isScheduledActive(at(19, 12, 0), disabled, zone))
        assertFalse(FocusRules.isScheduledActive(at(19, 12, 0), dayless, zone))
        assertNull(FocusRules.nextChange(at(19, 12, 0), listOf(mode("Work", dayless)), null, zone))
    }

    @Test
    fun `the first matching mode wins and list order is the tie-break`() {
        val work = mode("Work", schedule(days = setOf(FocusDay.Mon), startHour = 9, endHour = 17))
        val home = mode("Home", schedule(days = setOf(FocusDay.Mon), startHour = 8, endHour = 18))

        assertEquals("Work", FocusRules.activeFocus(at(19, 12, 0), listOf(work, home), zone)?.name)
        assertEquals("Home", FocusRules.activeFocus(at(19, 12, 0), listOf(home, work), zone)?.name)
    }

    @Test
    fun `a non-matching mode before the match is skipped`() {
        val later = mode("Later", schedule(days = setOf(FocusDay.Tue), startHour = 9, endHour = 17))
        val now = mode("Now", schedule(days = setOf(FocusDay.Mon), startHour = 9, endHour = 17))

        assertEquals("Now", FocusRules.activeFocus(at(19, 12, 0), listOf(later, now), zone)?.name)
    }

    @Test
    fun `a schedule is read in the given zone`() {
        val schedule = schedule(days = setOf(FocusDay.Mon), startHour = 22, endHour = 7)
        val instant = epoch(2026, 10, 19, 22, 30, zone)

        assertTrue(FocusRules.isScheduledActive(instant, schedule, zone))
        assertFalse(FocusRules.isScheduledActive(instant, schedule, ZoneId.of("UTC")))
    }

    // --- next boundary -----------------------------------------------------

    @Test
    fun `the next change before a daytime window is its start`() {
        val schedule = schedule(days = setOf(FocusDay.Mon), startHour = 9, endHour = 17)

        assertEquals(at(19, 9), FocusRules.nextTransition(at(19, 8), schedule, zone))
    }

    @Test
    fun `the next change inside a daytime window is its end`() {
        val schedule = schedule(days = setOf(FocusDay.Mon), startHour = 9, endHour = 17)

        assertEquals(at(19, 17), FocusRules.nextTransition(at(19, 12), schedule, zone))
    }

    @Test
    fun `the next change after a daytime window is the next selected start`() {
        val schedule = schedule(days = setOf(FocusDay.Mon), startHour = 9, endHour = 17)

        assertEquals(at(26, 9), FocusRules.nextTransition(at(19, 18), schedule, zone))
    }

    @Test
    fun `the next change inside an overnight window is the morning close`() {
        val schedule = schedule(days = setOf(FocusDay.Mon), startHour = 22, endHour = 7)

        assertEquals(at(20, 7), FocusRules.nextTransition(at(19, 23), schedule, zone))
    }

    @Test
    fun `the next change after an overnight window is the next selected start`() {
        val schedule = schedule(days = setOf(FocusDay.Mon), startHour = 22, endHour = 7)

        assertEquals(at(26, 22), FocusRules.nextTransition(at(20, 8), schedule, zone))
    }

    @Test
    fun `a boundary exactly now is not returned again`() {
        val schedule = schedule(days = setOf(FocusDay.Mon), startHour = 9, endHour = 17)

        assertEquals(at(19, 17), FocusRules.nextTransition(at(19, 9), schedule, zone))
    }

    @Test
    fun `the next change across a month boundary is still correct`() {
        val schedule = schedule(days = setOf(FocusDay.Sat), startHour = 22, endHour = 7)

        assertEquals(
            epoch(2026, 11, 1, 7, 0, zone),
            FocusRules.nextTransition(epoch(2026, 10, 31, 23, 0, zone), schedule, zone),
        )
    }

    // --- override resolution ----------------------------------------------

    @Test
    fun `no override resolves the scheduled mode and its end`() {
        val work = mode("Work", schedule(days = setOf(FocusDay.Mon)))

        val result = scheduled(listOf(work), at(19, 23))

        assertEquals(work, result.mode)
        assertFalse(result.manual)
        assertEquals(at(20, 7), result.nextChangeMillis)
    }

    @Test
    fun `a manual on wins outside the schedule`() {
        val work = mode("Work", schedule(days = setOf(FocusDay.Mon)))
        val until = at(20, 7)
        val override = FocusOverride("Work", on = true, untilMillis = until)

        val result = scheduled(listOf(work), at(19, 12), override)

        assertEquals(work, result.mode)
        assertTrue(result.manual)
        assertEquals(until, result.nextChangeMillis)
    }

    @Test
    fun `a manual on without an expiry schedules no wakeup`() {
        val work = mode("Work", schedule(days = emptySet()))
        val override = FocusOverride("Work", on = true, untilMillis = null)

        val result = scheduled(listOf(work), at(19, 12), override)

        assertEquals(work, result.mode)
        assertTrue(result.manual)
        assertNull(result.nextChangeMillis)
    }

    @Test
    fun `an expired manual on is ignored`() {
        val work = mode("Work", schedule(days = setOf(FocusDay.Tue)))
        val override = FocusOverride("Work", on = true, untilMillis = at(19, 12))

        val result = scheduled(listOf(work), at(19, 12), override)

        assertNull(result.mode)
        assertFalse(result.manual)
        assertEquals(at(20, 22), result.nextChangeMillis)
    }

    @Test
    fun `a manual off suppresses only the mode it names`() {
        val work = mode("Work", schedule(days = setOf(FocusDay.Mon)))
        val home = mode("Home", schedule(days = setOf(FocusDay.Mon)))
        val override = FocusOverride("Work", on = false, untilMillis = at(20, 7))

        val result = scheduled(listOf(work, home), at(19, 23), override)

        assertEquals(home, result.mode)
        assertFalse(result.manual)
    }

    @Test
    fun `a manual off for the only mode leaves nothing active`() {
        val work = mode("Work", schedule(days = setOf(FocusDay.Mon)))
        val override = FocusOverride("Work", on = false, untilMillis = at(20, 7))

        val result = scheduled(listOf(work), at(19, 23), override)

        assertNull(result.mode)
        assertEquals(at(20, 7), result.nextChangeMillis)
    }

    @Test
    fun `an override for a mode that no longer exists falls back to the schedule`() {
        val home = mode("Home", schedule(days = setOf(FocusDay.Tue)))
        val override = FocusOverride("Gone", on = true, untilMillis = at(19, 18))

        val result = scheduled(listOf(home), at(19, 12), override)

        assertNull(result.mode)
        assertEquals(at(19, 18), result.nextChangeMillis)
    }

    @Test
    fun `a manual off that is not live any more changes nothing`() {
        val work = mode("Work", schedule(days = setOf(FocusDay.Mon)))
        val override = FocusOverride("Work", on = false, untilMillis = at(19, 12))

        val result = scheduled(listOf(work), at(19, 23), override)

        assertEquals(work, result.mode)
    }

    // --- manual on / off ends ---------------------------------------------

    @Test
    fun `manual on ends at the next closing time`() {
        val work = mode("Work", schedule(days = setOf(FocusDay.Mon)))

        assertEquals(at(20, 7), FocusRules.manualOnUntil(at(19, 12), work, zone))
    }

    @Test
    fun `manual on inside the morning still ends this morning`() {
        val work = mode("Work", schedule(days = setOf(FocusDay.Sun)))

        assertEquals(at(19, 7), FocusRules.manualOnUntil(at(19, 6), work, zone))
    }

    @Test
    fun `manual on exactly at the closing minute ends the next day`() {
        val work = mode("Work", schedule(days = setOf(FocusDay.Mon)))

        assertEquals(at(20, 7), FocusRules.manualOnUntil(at(19, 7), work, zone))
    }

    @Test
    fun `manual off inside a full-day window ends when the twenty-four hours are up`() {
        val work = mode("Work", schedule(days = setOf(FocusDay.Mon), startHour = 8, endHour = 8))

        assertEquals(at(20, 8), FocusRules.manualOffUntil(at(19, 9), work, zone))
    }

    @Test
    fun `manual on for a disabled or day-less schedule has no end`() {
        val disabled = mode("Work", schedule(days = setOf(FocusDay.Mon), enabled = false))
        val dayless = mode("Home", schedule(days = emptySet()))

        assertNull(FocusRules.manualOnUntil(at(19, 12), disabled, zone))
        assertNull(FocusRules.manualOnUntil(at(19, 12), dayless, zone))
    }

    @Test
    fun `manual off ends with the window it suppresses`() {
        val work = mode("Work", schedule(days = setOf(FocusDay.Mon)))

        assertEquals(at(20, 7), FocusRules.manualOffUntil(at(19, 23), work, zone))
        assertEquals(at(20, 7), FocusRules.manualOffUntil(at(20, 6), work, zone))
        assertNull(FocusRules.manualOffUntil(at(19, 12), work, zone))
    }

    @Test
    fun `manual off for a disabled schedule has nothing to suppress`() {
        val work = mode("Work", schedule(days = setOf(FocusDay.Mon), enabled = false))

        assertNull(FocusRules.manualOffUntil(at(19, 23), work, zone))
    }

    // --- labels and validation --------------------------------------------

    @Test
    fun `schedule labels are stable and locale independent`() {
        val overnight = schedule(days = setOf(FocusDay.Mon, FocusDay.Wed), startHour = 22, endHour = 7)
        val fullDay = schedule(days = setOf(FocusDay.Mon), startHour = 8, endHour = 8)
        val everyDay = schedule()
        val midnight = FocusSchedule(
            days = setOf(FocusDay.Sun),
            startMinute = 0,
            endMinute = 23 * 60 + 59,
        )

        assertEquals("22:00", overnight.startLabel)
        assertEquals("07:00", overnight.endLabel)
        assertEquals("22:00-07:00", overnight.timeRangeLabel)
        assertEquals("Mon, Wed", overnight.daysLabel)
        assertTrue(overnight.overnight)
        assertFalse(overnight.fullDay)
        assertEquals("24 h", fullDay.timeRangeLabel)
        assertTrue(fullDay.fullDay)
        assertEquals("Every day", everyDay.daysLabel)
        assertEquals("", schedule(days = emptySet()).daysLabel)
        assertEquals("00:00", midnight.startLabel)
        assertEquals("23:59", midnight.endLabel)
    }

    @Test
    fun `invalid values are rejected at construction`() {
        val failures = listOf<() -> Unit>(
            { FocusSchedule(startMinute = -1) },
            { FocusSchedule(endMinute = FocusSchedule.MinutesPerDay) },
            { FocusMode(" ") },
            { FocusOverride("", on = true) },
            { FocusOverride("Work", on = true, untilMillis = 0L) },
        )

        failures.forEach { build ->
            try {
                build()
                throw AssertionError("expected IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                // The invalid document/test input must fail loudly, never half-apply.
            }
        }
    }

    @Test
    fun `an override is live until its expiry instant, exclusive`() {
        val override = FocusOverride("Work", on = true, untilMillis = 1_000L)

        assertTrue(override.isLiveAt(999L))
        assertFalse(override.isLiveAt(1_000L))
        assertTrue(FocusOverride("Work", on = false).isLiveAt(Long.MAX_VALUE))
    }

    @Test
    fun `a one-hour window still fits the promise that next change is after now`() {
        val now = mondayNoon()
        val work = mode("Work", schedule(days = setOf(FocusDay.Mon), startHour = 23, endHour = 0))
        val next = FocusRules.nextChange(now, listOf(work), null, zone)

        assertEquals(epoch(2026, 10, 19, 23, 0, zone), next)
        assertTrue(next!! > now)
        assertTrue(next - now < 24 * HourMillis)
    }
}
