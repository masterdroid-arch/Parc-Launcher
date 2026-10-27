package com.brightblu.parc.core.data.focus

import java.time.DayOfWeek
import kotlinx.serialization.Serializable

/**
 * One day of the week in a [FocusSchedule] (T72). The ISO order (Monday = 1) is what day arithmetic
 * uses; [label] is the three-letter form the settings screen shows. Immutable, non-null.
 */
@Serializable
enum class FocusDay(val isoDay: Int, val label: String) {
    Mon(1, "Mon"),
    Tue(2, "Tue"),
    Wed(3, "Wed"),
    Thu(4, "Thu"),
    Fri(5, "Fri"),
    Sat(6, "Sat"),
    Sun(7, "Sun"),
    ;

    companion object {
        /** The [FocusDay] for `java.time`'s weekday; never null. */
        fun of(day: DayOfWeek): FocusDay = entries[day.value - 1]
    }
}

/**
 * When a focus mode applies by itself (T72):
 *
 * - [days] are the days on which a window **starts**.
 * - [startMinute] is minutes after local midnight at which the window opens.
 * - [endMinute] is minutes after local midnight at which it closes.
 * - When `startMinute < endMinute` the window runs inside one day. When
 *   `startMinute >= endMinute` it is overnight: it opens on a selected day and closes the next
 *   morning. `startMinute == endMinute` is therefore a full 24-hour window, not an empty one.
 * - [enabled] false keeps the times stored but stops the schedule from activating the mode.
 *
 * Minutes are 0 to 1439; a window boundary at minute 0 means local midnight. Immutable; the sets must
 * be treated as read-only and are never mutated by this repository. All fields non-null.
 */
@Serializable
data class FocusSchedule(
    val days: Set<FocusDay> = emptySet(),
    val startMinute: Int = 0,
    val endMinute: Int = 0,
    val enabled: Boolean = true,
) {
    init {
        require(startMinute in 0 until MinutesPerDay) {
            "startMinute out of range: $startMinute"
        }
        require(endMinute in 0 until MinutesPerDay) {
            "endMinute out of range: $endMinute"
        }
    }

    /** True when the window opens on one day and closes on the next. */
    val overnight: Boolean get() = startMinute >= endMinute

    /** True for the full 24-hour window (`startMinute == endMinute`). */
    val fullDay: Boolean get() = startMinute == endMinute

    /** `22:00` style label for the opening time. */
    val startLabel: String get() = formatMinuteOfDay(startMinute)

    /** `07:00` style label for the closing time. */
    val endLabel: String get() = formatMinuteOfDay(endMinute)

    /** `22:00-07:00`, or `24 h` for a full-day window. ASCII hyphen on purpose. */
    val timeRangeLabel: String
        get() = if (fullDay) "24 h" else "$startLabel-$endLabel"

    /** `Mon, Tue` in ISO order, or `Every day`; empty when no day is selected. */
    val daysLabel: String
        get() {
            if (days.isEmpty()) return ""
            if (days.size == FocusDay.entries.size) return "Every day"
            return days.sortedBy { it.isoDay }.joinToString(", ") { it.label }
        }

    companion object {
        const val MinutesPerDay = 24 * 60
    }
}

/**
 * One named focus mode (T72): what to hide, whether to silence badges, and when it applies.
 *
 * [name] is the stable identity: it is unique in a stored list (the repository replaces a mode with
 * the same name) and it is what a manual override refers to. [hiddenPackages] holds package names,
 * not app ids, because the filter is by package and survives an app update. An empty set hides
 * nothing; [silenceBadges] only takes effect while the mode is active.
 *
 * Immutable; supplied collections must never be mutated after publication. [name] must not be
 * blank; the repository trims surrounding whitespace before storing.
 */
@Serializable
data class FocusMode(
    val name: String,
    val hiddenPackages: Set<String> = emptySet(),
    val silenceBadges: Boolean = false,
    val schedule: FocusSchedule = FocusSchedule(),
    val enabled: Boolean = true,
    val glyph: String = "•",
    val accent: Long? = null,
    val appPolicy: FocusAppPolicy = FocusAppPolicy.BlockList,
    val folderIds: Set<String> = emptySet(),
    val alwaysAllowed: Set<String> = emptySet(),
    val hiddenComms: Set<String> = emptySet(),
    val syncDnd: Boolean = false,
    val skinId: String? = null,
    val hubPages: Set<String>? = null,
    val mainAndAllowedOnly: Boolean = false,
    val iconTreatment: FocusIconTreatment = FocusIconTreatment.Normal,
    val extraSchedules: List<FocusSchedule> = emptyList(),
    val priority: Int = 0,
    val pauseBeforeOpen: Boolean = false,
    val strict: Boolean = false,
    val confirmationPhrase: String = "END FOCUS",
    val friction: FocusFriction = FocusFriction(),
    val ledger: FocusLedger = FocusLedger(),
) {
    init {
        require(name.isNotBlank()) { "Focus mode name must not be blank" }
    }
}

/**
 * A manual choice that outranks the schedule (T72). [on] true forces [modeName] active; false
 * suppresses it. A null [untilMillis] means the choice lasts until it is changed; otherwise the
 * choice stops applying at that epoch instant and the schedule decides again.
 *
 * Immutable, non-null fields except [untilMillis].
 */
@Serializable
data class FocusOverride(
    val modeName: String,
    val on: Boolean,
    val untilMillis: Long? = null,
    val breakUntilMillis: Long? = null,
    val emergencyPasses: List<Long> = emptyList(),
) {
    init {
        require(modeName.isNotBlank()) { "Focus override mode name must not be blank" }
        require(untilMillis == null || untilMillis > 0L) {
            "Focus override expiry must be positive: $untilMillis"
        }
    }

    /** True at [nowMillis] while this choice still applies. */
    fun isLiveAt(nowMillis: Long): Boolean = untilMillis == null || nowMillis < untilMillis
}

/**
 * Published focus state (T72). [active] is the mode whose hidden packages and badge rule apply now,
 * or null when nothing applies. [manual] says whether [active] is on because of a [FocusOverride]
 * rather than its schedule. [nextChangeMillis] is when [active] (or the next change) may differ, or
 * null when nothing will change until the modes or the override are edited.
 *
 * Immutable, safe to hold and compare. The convenience accessors never allocate a new set for the
 * empty case.
 */
data class FocusState(
    val active: FocusMode? = null,
    val manual: Boolean = false,
    val nextChangeMillis: Long? = null,
    val untilMillis: Long? = null,
    val breakUntilMillis: Long? = null,
    val evaluatedAtMillis: Long = 0L,
    val strictSession: Boolean = active?.strict == true,
) {
    /** Name of the active mode, or null. */
    val activeName: String? get() = active?.name

    /** Packages to hide now; empty when no mode is active. */
    val hiddenPackages: Set<String> get() = active?.hiddenPackages ?: emptySet()

    /** True when notification badges must be suppressed now. */
    val silenceBadges: Boolean get() = active?.silenceBadges == true
}

/**
 * Result of the pure resolution (T72): the mode that applies, whether a manual override chose it,
 * and the earliest instant at which the answer can change.
 */
data class FocusResolution(
    val mode: FocusMode?,
    val manual: Boolean,
    val nextChangeMillis: Long?,
)

/** `1439` to `23:59`, zero-padded and locale independent. */
internal fun formatMinuteOfDay(minute: Int): String {
    val hours = (minute / 60).toString().padStart(2, '0')
    val minutes = (minute % 60).toString().padStart(2, '0')
    return "$hours:$minutes"
}

@Serializable
enum class FocusAppPolicy { BlockList, AllowList }
@Serializable
enum class FocusIconTreatment { Normal, Dimmed, Greyscale }

data class FocusSummary(val name: String, val focusedMillis: Long, val blockedAttempts: Int, val emergencyPasses: List<Long> = emptyList())

/** Pure v2 policies. Folder packages are resolved against the current layout, never copied into it. */
object FocusPolicies {
    fun blocked(mode: FocusMode, packages: Set<String>, folderPackages: Set<String> = emptySet()): Set<String> {
        val chosen = mode.hiddenPackages + folderPackages
        return (if (mode.appPolicy == FocusAppPolicy.AllowList) packages - chosen else chosen) - mode.alwaysAllowed
    }
    fun canEnd(mode: FocusMode, phrase: String): Boolean = !mode.strict || phrase == mode.confirmationPhrase
    fun schedules(mode: FocusMode): List<FocusSchedule> = listOf(mode.schedule) + mode.extraSchedules
}

enum class FocusPreset { Work, Study, Sleep, Drive, Weekend, Custom;
    fun mode(essentialPackages: Set<String> = emptySet()): FocusMode {
        val weekdays = FocusDay.entries.take(5).toSet()
        val days = when (this) { Work, Study -> weekdays; Weekend -> setOf(FocusDay.Sat, FocusDay.Sun); Sleep -> FocusDay.entries.toSet(); else -> emptySet() }
        val start = when (this) { Work -> 9 * 60; Study -> 18 * 60; Sleep -> 22 * 60; Weekend -> 10 * 60; else -> 0 }
        val end = when (this) { Work -> 17 * 60; Study -> 20 * 60; Sleep -> 7 * 60; Weekend -> 18 * 60; else -> 0 }
        return FocusMode(name = if (this == Custom) "Custom" else name,
            glyph = when (this) { Work -> "▣"; Study -> "◇"; Sleep -> "☾"; Drive -> "→"; Weekend -> "○"; Custom -> "•" },
            silenceBadges = this != Custom && this != Weekend,
            appPolicy = if (this == Sleep || this == Drive) FocusAppPolicy.AllowList else FocusAppPolicy.BlockList,
            alwaysAllowed = essentialPackages, pauseBeforeOpen = this == Study,
            iconTreatment = if (this == Sleep) FocusIconTreatment.Dimmed else FocusIconTreatment.Normal,
            schedule = FocusSchedule(days, start, end, enabled = false))
    }
}
