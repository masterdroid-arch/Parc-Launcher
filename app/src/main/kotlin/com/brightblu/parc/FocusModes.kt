package com.brightblu.parc

import com.brightblu.parc.core.data.focus.FocusDay
import com.brightblu.parc.core.data.focus.FocusMode
import com.brightblu.parc.core.data.focus.FocusSchedule
import com.brightblu.parc.core.data.focus.FocusState
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.AppIndex
import com.brightblu.parc.model.AppShortcut
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import android.graphics.Bitmap

/**
 * T73's app-side focus rules. Everything here is pure: the Settings page, the hub and the view
 * filters all read the same small model, so a unit test pins the behaviour without Android.
 *
 * The stored modes, schedules and manual override still live in T72's `FocusRepository`; this file
 * adds no storage and never writes the layout.
 */
internal object FocusDisplay {

    /**
     * The clock-block line. Empty when no mode is active, so a fresh install and an upgraded install
     * look exactly as before until a schedule or a manual switch turns a mode on. The hub's own
     * Focus button remains the switch, so the line can stay an indicator.
     */
    fun clockLine(state: FocusState): String = state.active?.let { mode ->
        val until = state.untilMillis ?: return@let "focus · ${mode.name}"
        val time = java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5)
        "${mode.glyph} ${mode.name} · until $time"
    } ?: ""

    /** Root Settings row detail: the active mode, `Off` when modes exist, or `No modes`. */
    fun summary(state: FocusState, modes: List<FocusMode>): String =
        state.activeName ?: if (modes.isNotEmpty()) "Off" else "No modes"

    /** Hub header switch label: the active mode name, else the generic control. */
    fun hubLabel(state: FocusState): String = state.activeName ?: "Focus"

    /** Content description for the hub switch and the Settings toggle. */
    fun hubDescription(state: FocusState): String =
        state.activeName?.let { "Turn focus off ($it)" } ?: "Turn focus on"

    /** Mode row detail: `Mon, Tue · 22:00-07:00 · 1 app`. */
    fun modeDetail(mode: FocusMode): String {
        val count = mode.hiddenPackages.size
        return listOf(
            mode.schedule.daysLabel.ifEmpty { "No days" },
            mode.schedule.timeRangeLabel,
            if (count == 1) "1 app" else "$count apps",
        ).joinToString(" · ")
    }

    /** Longest mode name the Settings editor accepts, so one mode cannot overflow the screen. */
    const val MaxNameLength = 32
}

/** What a manual focus switch does next. [None] means there is no mode to turn on. */
internal sealed interface FocusManualAction {
    data object None : FocusManualAction
    data class On(val name: String) : FocusManualAction
    data class Off(val name: String) : FocusManualAction
}

internal object FocusManual {
    /**
     * A manual switch turns the active mode off, or turns the first enabled mode on. The modes
     * are in resolution order (T72), so "first" is the one the repository would pick first.
     */
    fun toggle(state: FocusState, modes: List<FocusMode>): FocusManualAction {
        state.activeName?.let { return FocusManualAction.Off(it) }
        val first = modes.firstOrNull { it.enabled } ?: return FocusManualAction.None
        return FocusManualAction.On(first.name)
    }
}

/** View-level focus filter. Packages, not ids: the rule survives an app update. */
internal object FocusFilter {

    /** The apps that stay visible. An empty hidden set returns the same list instance. */
    fun visibleApps(apps: List<AppEntry>, hiddenPackages: Set<String>): List<AppEntry> {
        if (hiddenPackages.isEmpty()) return apps
        return apps.filterNot { it.packageName in hiddenPackages }
    }

    fun isVisible(entry: AppEntry, hiddenPackages: Set<String>): Boolean =
        entry.packageName !in hiddenPackages

    /** Notification badges stay on unless the setting is off or the active mode silences them. */
    fun badgesVisible(badgesEnabled: Boolean, state: FocusState): Boolean =
        badgesEnabled && !state.silenceBadges
}

/**
 * [AppIndex] view that removes the active focus mode's packages. Search indexes this view and the
 * search sheet resolves labels from it, so a hidden app disappears from results as soon as the mode
 * changes and comes back when it ends. Launch, icons and shortcuts delegate unchanged.
 */
internal class FocusAppIndex(
    private val delegate: AppIndex,
    private val focus: StateFlow<FocusState>,
    scope: CoroutineScope,
    layout: StateFlow<com.brightblu.parc.model.ParcLayout> = kotlinx.coroutines.flow.MutableStateFlow(com.brightblu.parc.model.ParcLayout()),
    /** T98: packages of locked folders that are hidden right now; search and shortcuts never see them. */
    private val locked: StateFlow<Set<String>> = kotlinx.coroutines.flow.MutableStateFlow(emptySet()),
) : AppIndex {

    override val apps: StateFlow<List<AppEntry>> =
        combine(delegate.apps, focus, layout, locked) { entries, state, current, hiddenByLock ->
            com.brightblu.parc.core.data.lock.LockedApps.visibleApps(focusSearchApps(entries, state, current), hiddenByLock)
        }.stateIn(scope, SharingStarted.Eagerly,
            com.brightblu.parc.core.data.lock.LockedApps.visibleApps(
                focusSearchApps(delegate.apps.value, focus.value, layout.value), locked.value))

    private data class IconKey(val id: AppId, val size: Int)
    private data class PreparedIcon(val source: Bitmap, val treatment: com.brightblu.parc.core.data.focus.FocusIconTreatment, val bitmap: Bitmap)
    private val icons = object : LinkedHashMap<IconKey, PreparedIcon>(64, 0.75f, true) {
        // OPUS2: room for every installed app, so a full All-apps scene never recreates treated icons.
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<IconKey, PreparedIcon>?): Boolean =
            size > maxOf(128, delegate.apps.value.size + 16)
    }
    override fun icon(id: AppId, sizePx: Int): Bitmap? = synchronized(icons) {
        val source = delegate.icon(id, sizePx) ?: return@synchronized null
        val treatment = focus.value.active?.iconTreatment ?: com.brightblu.parc.core.data.focus.FocusIconTreatment.Normal
        val key = IconKey(id, sizePx)
        icons[key]?.takeIf { it.source === source && it.treatment == treatment }?.bitmap ?: focusIcon(source, treatment)?.also {
            icons[key] = PreparedIcon(source, treatment, it)
        }
    }

    override fun launch(id: AppId) = delegate.launch(id)

    override fun shortcuts(id: AppId): List<AppShortcut> {
        val hidden = locked.value
        if (hidden.isNotEmpty() && delegate.apps.value.firstOrNull { it.id == id }?.packageName in hidden) return emptyList()
        return delegate.shortcuts(id)
    }
}

/** 15-minute schedule steps, wrapping at midnight. Pure and locale independent. */
internal fun stepMinuteOfDay(minute: Int, deltaMinutes: Int): Int =
    Math.floorMod(minute + deltaMinutes, FocusSchedule.MinutesPerDay)

internal fun formatMinuteOfDay(minute: Int): String {
    val safe = Math.floorMod(minute, FocusSchedule.MinutesPerDay)
    return "${(safe / 60).toString().padStart(2, '0')}:${(safe % 60).toString().padStart(2, '0')}"
}

/**
 * Editable copy of one mode. [originalName] is the stored identity while editing, null for a new
 * mode; it lets a rename keep its own name without being called a duplicate. A new draft has no
 * days and is therefore inert until the user picks at least one.
 */
internal data class FocusDraft(
    val originalName: String? = null,
    val name: String = "",
    val hiddenPackages: Set<String> = emptySet(),
    val silenceBadges: Boolean = false,
    val days: Set<FocusDay> = emptySet(),
    val startMinute: Int = 22 * 60,
    val endMinute: Int = 7 * 60,
    val scheduleEnabled: Boolean = true,
) {
    val isNew: Boolean get() = originalName == null

    fun toggleDay(day: FocusDay): FocusDraft =
        copy(days = if (day in days) days - day else days + day)

    fun stepStart(deltaMinutes: Int): FocusDraft =
        copy(startMinute = stepMinuteOfDay(startMinute, deltaMinutes))

    fun stepEnd(deltaMinutes: Int): FocusDraft =
        copy(endMinute = stepMinuteOfDay(endMinute, deltaMinutes))

    fun togglePackage(packageName: String): FocusDraft =
        copy(hiddenPackages = if (packageName in hiddenPackages) hiddenPackages - packageName
        else hiddenPackages + packageName)

    /** Empty when the draft can be saved; otherwise the one issue line the editor shows. */
    fun issue(existingNames: List<String>): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "Name required"
        if (trimmed.length > FocusDisplay.MaxNameLength) return "Name is too long"
        val duplicate = existingNames.any { it == trimmed && it != originalName }
        return if (duplicate) "Name already used" else ""
    }

    fun toMode(): FocusMode = FocusMode(
        name = name.trim(),
        hiddenPackages = hiddenPackages,
        silenceBadges = silenceBadges,
        schedule = FocusSchedule(days = days, startMinute = startMinute, endMinute = endMinute,
            enabled = scheduleEnabled),
    )

    /** `22:00-07:00 · Mon, Tue`, or `No days` while the schedule is incomplete. */
    fun scheduleSummary(): String {
        val schedule = FocusSchedule(days = days, startMinute = startMinute, endMinute = endMinute,
            enabled = scheduleEnabled)
        val daysText = schedule.daysLabel.ifEmpty { "No days" }
        return "${schedule.timeRangeLabel} · $daysText"
    }

    companion object {
        fun new(): FocusDraft = FocusDraft()

        fun of(mode: FocusMode): FocusDraft = FocusDraft(
            originalName = mode.name,
            name = mode.name,
            hiddenPackages = mode.hiddenPackages,
            silenceBadges = mode.silenceBadges,
            days = mode.schedule.days,
            startMinute = mode.schedule.startMinute,
            endMinute = mode.schedule.endMinute,
            scheduleEnabled = mode.schedule.enabled,
        )
    }
}

/** Case-insensitive label/package filter for the hide-app picker. Pure. */
internal fun filterFocusApps(apps: List<AppEntry>, query: String): List<AppEntry> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return apps
    val needle = trimmed.lowercase()
    return apps.filter {
        it.label.lowercase().contains(needle) || it.packageName.lowercase().contains(needle)
    }
}

/** Installed emergency and navigation apps offered as the preset's always-allowed defaults. */
internal fun essentialFocusPackages(apps: List<AppEntry>): Set<String> = apps.map { it.packageName }.filter {
    it in setOf("com.android.dialer", "com.google.android.dialer", "com.android.mms", "com.google.android.apps.messaging", "com.google.android.apps.maps", "com.android.messaging")
}.toSet()

internal fun focusBlockedPackages(mode: FocusMode?, apps: List<AppEntry>, layout: com.brightblu.parc.model.ParcLayout): Set<String> {
    if (mode == null) return emptySet()
    val folderIds = layout.folders.filter { it.id.value in mode.folderIds }.flatMap { it.appIds }.toSet()
    val folderPackages = apps.filter { it.id in folderIds }.map { it.packageName }.toSet()
    var blocked = com.brightblu.parc.core.data.focus.FocusPolicies.blocked(mode, apps.map { it.packageName }.toSet(), folderPackages)
    if (mode.mainAndAllowedOnly) {
        val mainIds = layout.arcs.firstOrNull()?.slots?.values?.filterIsInstance<com.brightblu.parc.model.Slot.App>()?.map { it.id }.orEmpty().toSet()
        val allowed = mode.hiddenPackages + mode.alwaysAllowed + folderPackages + apps.filter { it.id in mainIds }.map { it.packageName }
        blocked = blocked + apps.map { it.packageName }.filterNot { it in allowed }
    }
    return blocked
}

private fun focusSearchApps(entries: List<AppEntry>, state: FocusState,
    layout: com.brightblu.parc.model.ParcLayout): List<AppEntry> =
    if (state.active?.pauseBeforeOpen == true) entries
    else FocusFilter.visibleApps(entries, focusBlockedPackages(state.active, entries, layout))
