package com.brightblu.parc.core.data.focus

import com.brightblu.parc.core.data.apps.epoch
import com.brightblu.parc.core.data.apps.nairobi
import java.time.ZoneId

/** 2026-10-19, a Monday, at 12:00 local, the reference instant for most focus tests. */
internal fun mondayNoon(zone: ZoneId = nairobi): Long = epoch(2026, 10, 19, 12, 0, zone)

/** A schedule helper that keeps the tests on the hour/minute vocabulary. */
internal fun schedule(
    days: Set<FocusDay> = FocusDay.entries.toSet(),
    startHour: Int = 22,
    startMinute: Int = 0,
    endHour: Int = 7,
    endMinute: Int = 0,
    enabled: Boolean = true,
): FocusSchedule = FocusSchedule(
    days = days,
    startMinute = startHour * 60 + startMinute,
    endMinute = endHour * 60 + endMinute,
    enabled = enabled,
)

/** A mode with a package or two so the hidden-set behaviour is visible in assertions. */
internal fun mode(
    name: String,
    schedule: FocusSchedule = schedule(),
    hidden: Set<String> = setOf("com.test.$name"),
    silenceBadges: Boolean = false,
): FocusMode = FocusMode(
    name = name,
    hiddenPackages = hidden,
    silenceBadges = silenceBadges,
    schedule = schedule,
)
