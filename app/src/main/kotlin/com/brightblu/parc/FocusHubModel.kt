package com.brightblu.parc

import com.brightblu.parc.core.data.focus.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class FocusHubModel(val status: String, val apps: String?, val nextSchedule: String?, val passStatus: String? = null)

/** Never accepts app labels or folder names: locked membership cannot leak through this page. */
internal fun focusHubModel(state: FocusState, modes: List<FocusMode>, now: Long,
    zone: ZoneId = ZoneId.systemDefault()): FocusHubModel {
    val active = state.active
    val breakUntil = state.breakUntilMillis
    val status = when {
        active != null -> "${active.glyph} ${active.name} · ${focusRemaining(state.copy(evaluatedAtMillis = now))}" + if (active.strict) " · Strict" else ""
        breakUntil != null -> "Focus paused · ${((breakUntil - now).coerceAtLeast(0) + 59_999) / 60_000} min break left"
        else -> "No active focus"
    }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val next = modes.filter { it.enabled }.flatMap { mode ->
        FocusPolicies.schedules(mode).filter { it.enabled }.flatMap { schedule ->
            (0L..7L).mapNotNull { offset ->
                val day = today.plusDays(offset)
                val start = day.atTime(schedule.startMinute / 60, schedule.startMinute % 60).atZone(zone)
                if (FocusDay.of(day.dayOfWeek) in schedule.days && start.toInstant().toEpochMilli() > now) mode to start else null
            }
        }
    }.minWithOrNull(compareBy<Pair<FocusMode, java.time.ZonedDateTime>> { it.second.toInstant() }.thenByDescending { it.first.priority })
    val line = next?.let { (mode, start) ->
        val day = if (start.toLocalDate() == today) "" else if (start.toLocalDate() == today.plusDays(1)) "tomorrow " else start.format(DateTimeFormatter.ofPattern("EEE "))
        "${mode.name} starts $day${start.format(DateTimeFormatter.ofPattern("HH:mm"))}"
    }
    return FocusHubModel(status, active?.let { focusSectionSummary("Apps", it) }, line, active?.let { FocusFrictionRules.status(it, now, zone) })
}

internal enum class FocusQuickOption { Badges, Comms, Dnd, Greyscale }
internal fun focusQuickChange(mode: FocusMode, option: FocusQuickOption, globalPages: Set<String> = HubCatalog.pages.toSet()): FocusMode = when (option) {
    FocusQuickOption.Badges -> mode.copy(silenceBadges = !mode.silenceBadges)
    FocusQuickOption.Comms -> mode.copy(hubPages = (mode.hubPages ?: globalPages).let {
        if ("comms" in it) it - "comms" else it + "comms"
    })
    FocusQuickOption.Dnd -> mode.copy(syncDnd = !mode.syncDnd)
    FocusQuickOption.Greyscale -> mode.copy(iconTreatment = if (mode.iconTreatment == FocusIconTreatment.Greyscale) FocusIconTreatment.Normal else FocusIconTreatment.Greyscale)
}

internal fun focusQuickOptionAllowed(mode: FocusMode, option: FocusQuickOption, dndAllowed: Boolean): Boolean =
    !mode.strict && (option != FocusQuickOption.Dnd || dndAllowed)
