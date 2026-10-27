package com.brightblu.parc.core.data.agenda

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One calendar the user can include or leave out (T100). [color] is an ARGB int; 0 means unknown. */
data class AgendaCalendar(val id: Long, val name: String, val color: Int = 0)

/**
 * One event instance. Timed events carry real UTC instants. All-day events follow the platform
 * convention: [beginMs] and [endMs] are UTC midnights and [endMs] is exclusive.
 */
data class AgendaEvent(
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val beginMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val location: String? = null,
    val color: Int = 0,
)

/** What reading the calendar produced. */
sealed interface AgendaRead {
    /** READ_CALENDAR is not granted (or was revoked). */
    data object NoPermission : AgendaRead
    data class Data(val events: List<AgendaEvent>) : AgendaRead
}

/** Source of events; the Android port and test fakes implement it. Never throws. */
interface AgendaSource {
    /** The calendars on the phone, or null without permission. */
    fun calendars(): List<AgendaCalendar>?
    fun events(fromMs: Long, toMs: Long): AgendaRead
}

/** The two persisted choices of the Agenda settings page. */
data class AgendaSettings(
    val days: Int = AgendaRules.DefaultDays,
    /** Display names of calendars left out. Names, not ids, so a restore on another phone still lines up. */
    val excluded: Set<String> = emptySet(),
) {
    fun includes(calendar: AgendaCalendar): Boolean = calendar.name !in excluded
    fun withCalendar(name: String, included: Boolean): AgendaSettings =
        copy(excluded = if (included) excluded - name else excluded + name)

    fun encodeDays(): String = AgendaRules.clampDays(days).toString()

    fun encodeExcluded(): String {
        val out = StringBuilder()
        for (name in excluded.sorted()) {
            val clean = name.replace(NameSeparator, ' ').trim()
            if (clean.isEmpty() || out.length + clean.length + 1 > MaxEncoded) continue
            if (out.isNotEmpty()) out.append(NameSeparator)
            out.append(clean)
        }
        return out.toString()
    }

    companion object {
        const val NameSeparator = '\n'
        const val MaxEncoded = 3_500

        fun decode(days: String?, excluded: String?): AgendaSettings = AgendaSettings(
            AgendaRules.clampDays(days?.trim()?.toIntOrNull()),
            excluded.orEmpty().split(NameSeparator).map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        )
    }
}

/** One day of the page: all-day events first, then timed ones in start order. */
data class AgendaDay(val date: LocalDate, val dayOffset: Int, val allDay: List<AgendaEvent>, val timed: List<AgendaEvent>) {
    val isEmpty: Boolean get() = allDay.isEmpty() && timed.isEmpty()
}

/** Pure rules for day windows, time zones, grouping, the next event and the text. */
object AgendaRules {
    const val DefaultDays = 2
    const val MinDays = 1
    const val MaxDays = 7
    const val MaxEvents = 200

    /**
     * The platform stores all-day events as UTC midnights, so a local-time query can miss or
     * include them by a day. The query is widened by this much on each side and the rules below
     * decide by local date.
     */
    const val QueryPaddingMs = 36L * 60 * 60 * 1000

    fun clampDays(days: Int?): Int = (days ?: DefaultDays).coerceIn(MinDays, MaxDays)

    fun startOfDay(date: LocalDate, zone: ZoneId): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** The visible window: local midnight today to local midnight after the last day (DST safe). */
    fun window(nowMs: Long, zone: ZoneId, days: Int): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return startOfDay(today, zone) to startOfDay(today.plusDays(clampDays(days).toLong()), zone)
    }

    /** The local dates an all-day event covers: UTC begin date up to (excluding) UTC end date. */
    fun allDayDates(event: AgendaEvent): ClosedRange<LocalDate> {
        val first = Instant.ofEpochMilli(event.beginMs).atZone(ZoneOffset.UTC).toLocalDate()
        val endExclusive = Instant.ofEpochMilli(event.endMs).atZone(ZoneOffset.UTC).toLocalDate()
        val last = if (endExclusive.isAfter(first)) endExclusive.minusDays(1) else first
        return first..last
    }

    fun onDay(event: AgendaEvent, date: LocalDate, zone: ZoneId): Boolean {
        if (event.allDay) return date in allDayDates(event)
        val start = startOfDay(date, zone)
        val end = startOfDay(date.plusDays(1), zone)
        return if (event.endMs <= event.beginMs) event.beginMs in start until end
        else event.beginMs < end && event.endMs > start
    }

    /** Keeps events of calendars that are not excluded. [names] maps calendar id to display name. */
    fun filter(events: List<AgendaEvent>, names: Map<Long, String>, settings: AgendaSettings): List<AgendaEvent> =
        if (settings.excluded.isEmpty()) events else events.filter { names[it.calendarId] !in settings.excluded }

    /**
     * Groups into [days] local days from today. Timed events that already ended today are dropped
     * (the page is about what is next); all-day events stay for the whole day.
     */
    fun group(events: List<AgendaEvent>, nowMs: Long, zone: ZoneId, days: Int): List<AgendaDay> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val unique = events.distinctBy { Triple(it.eventId, it.beginMs, it.calendarId) }
        return (0 until clampDays(days)).map { offset ->
            val date = today.plusDays(offset.toLong())
            val onDay = unique.filter { onDay(it, date, zone) }
            AgendaDay(
                date, offset,
                onDay.filter { it.allDay }.sortedWith(compareBy({ it.title.lowercase() }, { it.eventId })),
                onDay.filter { !it.allDay && (offset > 0 || stillRelevant(it, nowMs)) }
                    .sortedWith(compareBy({ it.beginMs }, { it.endMs }, { it.eventId })),
            )
        }
    }

    private fun stillRelevant(event: AgendaEvent, nowMs: Long): Boolean =
        if (event.endMs <= event.beginMs) event.beginMs >= nowMs else event.endMs > nowMs

    /** The first timed event that is running or still to come; all-day events are never "next". */
    fun next(days: List<AgendaDay>, nowMs: Long): AgendaEvent? =
        days.asSequence().flatMap { it.timed.asSequence() }
            .filter { stillRelevant(it, nowMs) }
            .minWithOrNull(compareBy({ it.beginMs }, { it.eventId }))

    /** `now`, `in 25 min`, `in 1 h 5 min`, `in 3 h`, `in 2 d`. Minutes round up so a 24:30 wait reads 25. */
    fun untilText(beginMs: Long, nowMs: Long): String {
        val delta = beginMs - nowMs
        if (delta <= 0) return "now"
        val minutes = (delta + 59_999) / 60_000
        return when {
            minutes < 60 -> "in $minutes min"
            minutes < 24 * 60 -> {
                val h = minutes / 60
                val m = minutes % 60
                if (m == 0L) "in $h h" else "in $h h $m min"
            }
            else -> "in ${minutes / (24 * 60)} d"
        }
    }

    fun dayLabel(day: AgendaDay, locale: Locale = Locale.getDefault()): String = when (day.dayOffset) {
        0 -> "Today"
        1 -> "Tomorrow"
        else -> day.date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
    }

    fun clock(ms: Long, zone: ZoneId, is24: Boolean, locale: Locale = Locale.getDefault()): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", locale))

    /** `09:00 - 10:30`; an event that crosses midnight shows `until 02:00` or `from 22:00` on the day it spills into. */
    fun timeRange(event: AgendaEvent, date: LocalDate, zone: ZoneId, is24: Boolean, locale: Locale = Locale.getDefault()): String {
        val start = startOfDay(date, zone)
        val end = startOfDay(date.plusDays(1), zone)
        val begins = event.beginMs >= start
        val ends = event.endMs <= end
        return when {
            event.endMs <= event.beginMs -> clock(event.beginMs, zone, is24, locale)
            begins && ends -> "${clock(event.beginMs, zone, is24, locale)} - ${clock(event.endMs, zone, is24, locale)}"
            begins -> "from ${clock(event.beginMs, zone, is24, locale)}"
            ends -> "until ${clock(event.endMs, zone, is24, locale)}"
            else -> "all day"
        }
    }

    fun titleText(event: AgendaEvent): String = event.title.ifBlank { "(No title)" }

    /** One short line for the empty states, or null when there is something to list. */
    fun emptyLine(read: AgendaRead?, days: List<AgendaDay>?): String? = when {
        read == null -> "Loading"
        read == AgendaRead.NoPermission -> "Calendar access is off"
        days != null && days.all { it.isEmpty } -> "Nothing planned"
        else -> null
    }
}
