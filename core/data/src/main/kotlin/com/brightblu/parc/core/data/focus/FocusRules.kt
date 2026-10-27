package com.brightblu.parc.core.data.focus

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure focus-schedule maths (T72). No Android, no clock, no state: every answer is a function of the
 * epoch instant, the zone and the modes passed in, which is what makes the schedule unit-testable
 * with a fake clock.
 *
 * Time model: an instant is epoch milliseconds; a schedule is local wall-clock minutes in [zone],
 * so the same stored mode does the right thing after a time-zone change. Window boundaries are
 * minute-aligned: a window is active from its start minute (inclusive) to its end minute
 * (exclusive). Seconds inside those minutes do not matter.
 *
 * Overnight ranges: a window whose end minute is at or before its start minute opens on a selected
 * day and closes the following local day, so `22:00-07:00` is active on Tuesday 02:00 when Monday is
 * selected. `start == end` is a full 24-hour window.
 *
 * Overlap: [activeFocus] returns the first matching mode in list order. The list order is therefore
 * meaningful and is preserved by the repository.
 */
object FocusRules {

    /**
     * The first enabled mode whose schedule contains [nowMillis], or null. Modes with no selected
     * days, or with `enabled` false, never match. Pure and deterministic for a given zone.
     */
    fun activeFocus(
        nowMillis: Long,
        modes: List<FocusMode>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): FocusMode? = modes.sortedByDescending { it.priority }.firstOrNull { modeActive(nowMillis, it, zone) }

    /** True when [schedule] would apply at [nowMillis] without a manual override. */
    fun isScheduledActive(
        nowMillis: Long,
        schedule: FocusSchedule,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (!schedule.enabled || schedule.days.isEmpty()) return false
        val today = localDate(nowMillis, zone)
        // A window is at most 24 hours long, so only a window that started today or yesterday can
        // still contain now.
        for (date in listOf(today, today.minusDays(1))) {
            if (FocusDay.of(date.dayOfWeek) !in schedule.days) continue
            val (start, end) = windowFor(date, schedule, zone)
            if (nowMillis >= start && nowMillis < end) return true
        }
        return false
    }

    /**
     * Resolves the schedule together with [override] at [nowMillis].
     *
     * A live `on` override wins over every schedule for the mode it names; a live `off` override
     * suppresses only that mode, so another scheduled mode can still be active. An expired override
     * is ignored. [FocusResolution.nextChangeMillis] is the earliest instant at which the answer can
     * differ: a scheduled start or end, or an override expiry. It is null when only an edit can
     * change the answer, which is what stops the process timer.
     */
    fun resolve(
        nowMillis: Long,
        modes: List<FocusMode>,
        override: FocusOverride?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): FocusResolution {
        val live = override?.takeIf { it.isLiveAt(nowMillis) }
        if (live?.breakUntilMillis != null && nowMillis < live.breakUntilMillis) {
            return FocusResolution(null, false, live.breakUntilMillis)
        }
        if (live != null && live.on) {
            val forced = modes.firstOrNull { it.name == live.modeName && it.enabled }
            if (forced != null) {
                return FocusResolution(
                    mode = forced,
                    manual = true,
                    nextChangeMillis = live.untilMillis?.takeIf { it > nowMillis },
                )
            }
        }

        val suppressed = live?.takeIf { !it.on }?.modeName
        val scheduled = modes.sortedByDescending { it.priority }.firstOrNull { mode ->
            mode.name != suppressed && modeActive(nowMillis, mode, zone)
        }

        var next = live?.untilMillis?.takeIf { it > nowMillis }
        for (mode in modes) {
            val transition = if (mode.enabled) FocusPolicies.schedules(mode).mapNotNull { nextTransition(nowMillis, it, zone) }.minOrNull() else null
            if (transition != null && (next == null || transition < next)) next = transition
        }
        return FocusResolution(mode = scheduled, manual = false, nextChangeMillis = next)
    }

    /** The earliest instant after [nowMillis] at which any schedule or live override changes; null if none. */
    fun nextChange(
        nowMillis: Long,
        modes: List<FocusMode>,
        override: FocusOverride?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? = resolve(nowMillis, modes, override, zone).nextChangeMillis

    /**
     * End of the scheduled window that contains [nowMillis], or null when the schedule is not active
     * now (including a disabled schedule or one with no days). Used to end a manual `off` exactly
     * when the occurrence it suppresses would have ended.
     */
    fun currentWindowEnd(
        nowMillis: Long,
        mode: FocusMode,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        if (!mode.enabled) return null
        val today = localDate(nowMillis, zone)
        return FocusPolicies.schedules(mode).filter { it.enabled }.flatMap { schedule ->
            listOf(today, today.minusDays(1)).mapNotNull { date ->
                if (FocusDay.of(date.dayOfWeek) !in schedule.days) null else {
                    val (start, end) = windowFor(date, schedule, zone)
                    end.takeIf { nowMillis >= start && nowMillis < end }
                }
            }
        }.maxOrNull()
    }

    private fun modeActive(now: Long, mode: FocusMode, zone: ZoneId): Boolean =
        mode.enabled && FocusPolicies.schedules(mode).any { isScheduledActive(now, it, zone) }

    /**
     * When a manual `on` for [mode] should stop by itself, or null for "until turned off".
     *
     * The rule is the next closing time of the schedule: today's `endMinute` when it is still ahead,
     * otherwise tomorrow's. Days do not matter here: a manual override is a "now" action, and its
     * natural end is the clock time at which the mode would normally close. A disabled schedule (or
     * one with no days) has no automatic end, so the manual choice stays until it is turned off.
     */
    fun manualOnUntil(
        nowMillis: Long,
        mode: FocusMode,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val schedule = mode.schedule
        if (!schedule.enabled || schedule.days.isEmpty()) return null
        val today = localDate(nowMillis, zone)
        val todayEnd = at(today, schedule.endMinute, zone)
        return if (todayEnd > nowMillis) todayEnd else at(today.plusDays(1), schedule.endMinute, zone)
    }

    /**
     * When a manual `off` for [mode] should stop suppressing it, or null when there is nothing to
     * suppress. Off only has meaning inside a scheduled window; outside one it clears itself.
     */
    fun manualOffUntil(
        nowMillis: Long,
        mode: FocusMode,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? = currentWindowEnd(nowMillis, mode, zone)

    /**
     * Earliest scheduled transition after [nowMillis], looking one day back (an overnight window
     * that opened yesterday) and eight days ahead (the next occurrence of any selected weekday).
     */
    internal fun nextTransition(
        nowMillis: Long,
        schedule: FocusSchedule,
        zone: ZoneId,
    ): Long? {
        if (!schedule.enabled || schedule.days.isEmpty()) return null
        val today = localDate(nowMillis, zone)
        var best: Long? = null
        for (offset in -1L..8L) {
            val date = today.plusDays(offset)
            if (FocusDay.of(date.dayOfWeek) !in schedule.days) continue
            val (start, end) = windowFor(date, schedule, zone)
            if (start > nowMillis && (best == null || start < best)) best = start
            if (end > nowMillis && (best == null || end < best)) best = end
        }
        return best
    }

    /** Start and end instants of the window that opens on [date] (end on the next day when overnight). */
    internal fun windowFor(date: LocalDate, schedule: FocusSchedule, zone: ZoneId): Pair<Long, Long> {
        val endDate = if (schedule.startMinute < schedule.endMinute) date else date.plusDays(1)
        return at(date, schedule.startMinute, zone) to at(endDate, schedule.endMinute, zone)
    }

    private fun at(date: LocalDate, minute: Int, zone: ZoneId): Long =
        ZonedDateTime.of(date, LocalTime.of(minute / 60, minute % 60), zone)
            .toInstant()
            .toEpochMilli()

    private fun localDate(nowMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
}
