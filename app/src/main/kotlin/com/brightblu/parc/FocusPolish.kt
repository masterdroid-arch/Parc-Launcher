package com.brightblu.parc

import com.brightblu.parc.core.data.focus.*

internal val FocusGlyphs = listOf("•" to "Dot", "▣" to "Work", "◇" to "Study", "☾" to "Sleep", "→" to "Drive", "○" to "Weekend", "☆" to "Star", "♡" to "Heart", "△" to "Mountain", "□" to "Square", "☀" to "Sun", "✿" to "Flower")

internal fun stepFocusPriority(priority: Int, delta: Int) = (priority + delta).coerceIn(0, 9)

internal fun focusScheduleCount(mode: FocusMode): String {
    val count = 1 + mode.extraSchedules.size
    return "$count ${if (count == 1) "schedule" else "schedules"}"
}

internal fun focusSectionSummary(section: String, mode: FocusMode): String = when (section) {
    "Basics" -> "${FocusGlyphs.firstOrNull { it.first == mode.glyph }?.second ?: "Custom icon"} · ${if (mode.accent == null) "Skin accent" else "Custom accent"}"
    "Apps" -> {
        val count = mode.hiddenPackages.size
        val apps = "$count ${if (count == 1) "app" else "apps"}"
        val folders = mode.folderIds.size
        "${if (mode.appPolicy == FocusAppPolicy.BlockList) "Hiding" else "Allowing"} $apps" +
            if (folders == 0) "" else " · $folders ${if (folders == 1) "folder" else "folders"}"
    }
    "Notifications" -> "${if (mode.silenceBadges) "Silent badges" else "Badges on"} · DND ${if (mode.syncDnd) "on" else "off"}" +
        if (mode.hiddenComms.isEmpty()) "" else " · ${mode.hiddenComms.size} Comms hidden"
    "Look" -> "${mode.skinId ?: "Current skin"} · ${mode.iconTreatment.name}" +
        (mode.hubPages?.let { " · ${it.size} hub pages" } ?: " · Global hub pages") +
        if (mode.mainAndAllowedOnly) " · Main apps only" else ""
    "Schedule" -> "${focusScheduleCount(mode)} · Priority ${mode.priority}"
    "Friction" -> "${mode.friction.passLimit} passes per ${mode.friction.period.name.lowercase()} · ${mode.friction.passMinutes} min · ${mode.friction.challenge.name}"
    else -> ""
}

internal fun focusEditIssue(mode: FocusMode, name: String, original: String?, modes: List<FocusMode>): String = when {
    name.isBlank() -> "Name required"
    modes.any { it.name == name.trim() && it.name != original } -> "Name already used"
    else -> ""
}

internal fun focusRemaining(state: FocusState): String = state.untilMillis?.let {
    "${((it - state.evaluatedAtMillis).coerceAtLeast(0L) + 59_999) / 60_000} min left"
} ?: "Until ended"

internal fun focusSummaryText(summary: FocusSummary): String =
    "${summary.name}: ${summary.focusedMillis / 60_000} min focused · ${summary.blockedAttempts} blocked ${if (summary.blockedAttempts == 1) "attempt" else "attempts"}" + if (summary.emergencyPasses.isEmpty()) "" else " · Passes: " + summary.emergencyPasses.joinToString { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString() }
