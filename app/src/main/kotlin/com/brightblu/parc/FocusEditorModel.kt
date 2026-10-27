package com.brightblu.parc

import com.brightblu.parc.core.data.focus.*
import com.brightblu.parc.model.*

internal enum class FocusLevel { Relaxed, Firm, Strict, Custom }
internal val FocusColours = Skin.FocusPalette
internal fun focusLevel(mode: FocusMode): FocusLevel = FocusLevel.entries.firstOrNull {
    it != FocusLevel.Custom && applyFocusLevel(mode, it).let { mapped ->
        mapped.strict == mode.strict && mapped.pauseBeforeOpen == mode.pauseBeforeOpen && mapped.friction == mode.friction
    }
} ?: FocusLevel.Custom
internal fun applyFocusLevel(mode: FocusMode, level: FocusLevel): FocusMode = when (level) {
    FocusLevel.Custom -> mode
    FocusLevel.Relaxed -> mode.copy(strict = false, pauseBeforeOpen = true, friction = mode.friction.copy(
        passMinutes = 5, passLimit = 2, period = PassPeriod.Day, challenge = FocusChallenge.None,
        delaySeconds = listOf(0, 0, 0), unlimitedPasses = true))
    FocusLevel.Firm -> mode.copy(strict = false, pauseBeforeOpen = true, friction = mode.friction.copy(
        passMinutes = 5, passLimit = 2, period = PassPeriod.Day, challenge = FocusChallenge.None,
        delaySeconds = List(3) { (Metrics.focusFirmDelayMs / 1_000).toInt() }, unlimitedPasses = false))
    FocusLevel.Strict -> mode.copy(strict = true, pauseBeforeOpen = true, friction = mode.friction.copy(
        passMinutes = 5, passLimit = 2, period = PassPeriod.Day, challenge = FocusChallenge.Sentence,
        delaySeconds = FocusFriction().delaySeconds, unlimitedPasses = false))
}
internal fun focusAppCount(count: Int): String = "$count ${if (count == 1) "app" else "apps"}"
internal fun focusAppsValue(mode: FocusMode, apps: List<AppEntry> = emptyList(), folders: List<Folder> = emptyList()): String {
    val ids = folders.filter { it.id.value in mode.folderIds }.flatMap { it.appIds }.toSet()
    val chosen = mode.hiddenPackages + apps.filter { it.id in ids }.map { it.packageName }
    val count = (if (mode.appPolicy == FocusAppPolicy.BlockList) chosen - mode.alwaysAllowed else chosen).size
    val quantity = focusAppCount(count)
    val group = if (mode.folderIds.isNotEmpty() && folders.isEmpty()) " and ${mode.folderIds.size} ${if (mode.folderIds.size == 1) "folder" else "folders"}" else ""
    return if (mode.appPolicy == FocusAppPolicy.AllowList) "Allows only $quantity$group, plus always allowed apps"
    else if (count == 0 && group.isEmpty()) "Nothing hidden yet" else "Hides $quantity$group"
}
internal fun focusWhenValue(mode: FocusMode): String {
    val times = FocusPolicies.schedules(mode).filter { it.enabled && it.days.isNotEmpty() }
    if (times.isEmpty()) return "Only when I start it"
    return times.joinToString("; ", transform = ::focusScheduleWords)
}
private fun focusScheduleWords(time: FocusSchedule): String {
    val days = if (time.days == FocusDay.entries.take(5).toSet()) "Mon to Fri" else time.daysLabel
    return "$days, ${time.startLabel} to ${time.endLabel}" + if (time.overnight) " the next day" else ""
}

internal fun focusQuietValue(mode: FocusMode): String = buildList {
    if (mode.silenceBadges) add("Badges")
    if (mode.hiddenComms.isNotEmpty()) add("messages from ${mode.hiddenComms.size} ${if (mode.hiddenComms.size == 1) "app" else "apps"}")
    if (mode.syncDnd) add("Do Not Disturb")
}.joinToString(" and ").ifEmpty { "Off" }
internal fun focusLookValue(mode: FocusMode): String = buildList {
    if (mode.iconTreatment != FocusIconTreatment.Normal) add("${mode.iconTreatment.name} icons")
    if (mode.skinId != null) add("Focus skin")
    if (mode.mainAndAllowedOnly) add("Main and allowed apps")
    mode.hubPages?.let { add("${it.size} Home ${if (it.size == 1) "page" else "pages"}") }
}.joinToString(", ").ifEmpty { "Unchanged" }
/** One source for list, editor and hub; no wall clock or Android dependency. */
internal fun focusModeSentence(mode: FocusMode, apps: List<AppEntry> = emptyList(), folders: List<Folder> = emptyList()): String {
    val effect = focusAppsValue(mode, apps, folders).let { if (it == "Nothing hidden yet") "hides no apps yet" else it.replaceFirstChar { c -> c.lowercase() } }
    val quiet = focusQuietValue(mode).let { if (it == "Off") "" else " and quiets ${it.replaceFirstChar { c -> c.lowercase() }}" }
    val times = FocusPolicies.schedules(mode).filter { it.enabled && it.days.isNotEmpty() }
    val whenText = if (times.isEmpty()) "when you start it" else focusScheduleWords(times.first()) +
        if (times.size > 1) " and ${times.size - 1} other ${if (times.size == 2) "time" else "times"}" else ""
    val exit = when {
        mode.strict -> "Ending early needs a challenge or an accountability code."
        mode.friction.challenge != FocusChallenge.None -> "Ending early needs a challenge."
        mode.friction.delaySeconds.first() == 0 -> "You can end it after one question."
        mode.friction.delaySeconds.distinct().size > 1 -> "Ending early starts with a ${mode.friction.delaySeconds.first()}-second pause; later waits can be longer."
        else -> "You can end it with a ${mode.friction.delaySeconds.first()}-second pause."
    }
    val look = focusLookValue(mode).let { if (it == "Unchanged") "" else " Look: $it." }
    return "While ${mode.name} is on, Parc $effect$quiet, $whenText. $exit$look"
}
internal val FocusPresetDescriptions = mapOf(
    FocusPreset.Work to "Hides social apps and games; weekdays ready to schedule",
    FocusPreset.Study to "Hides social apps and games for study time",
    FocusPreset.Sleep to "Keeps essentials and dims icons for the night",
    FocusPreset.Drive to "Keeps essentials and quiets badges while driving",
    FocusPreset.Weekend to "Hides distractions for your time off",
    FocusPreset.Custom to "Start with nothing hidden and choose what helps")

/** Bounds are measured during layout; release chooses the actual destination, including long drags. */
internal fun focusDropDelta(names: List<String>, centers: Map<String, Float>, name: String, y: Float): Int {
    val from = names.indexOf(name)
    if (from < 0) return 0
    val target = names.indices.filter { names[it] in centers }.minByOrNull { kotlin.math.abs(centers.getValue(names[it]) - y) } ?: return 0
    return target - from
}

internal fun restoreFocusApps(current: FocusMode, before: FocusMode, clearingApps: Boolean): FocusMode =
    if (clearingApps) current.copy(hiddenPackages = current.hiddenPackages + before.hiddenPackages,
        folderIds = current.folderIds + before.folderIds)
    else current.copy(alwaysAllowed = current.alwaysAllowed + before.alwaysAllowed)

/** Shared with every settings editor (T115). */
internal typealias FocusEditTiming = com.brightblu.parc.feature.settings.ui.EditorTiming
