package com.brightblu.parc

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.asStateFlow

/*
 * T183: the pure rules of note reminders (quick choices, the picker, the labels, the first line).
 * No Android and no Compose, so they are unit tested on the JVM.
 */
internal object NoteReminderRules {
    const val EveningHour = 19
    const val MorningHour = 9
    const val MaxFirstLine = 60
    /** The picker offers a day up to this far ahead. */
    const val MaxDaysAhead = 365
    const val MinuteStep = 5

    /** In one hour from [now]. */
    fun inOneHour(now: Long): Long = now + 3_600_000L

    /** Today 19:00, or tomorrow 19:00 once it is 19:00 or later. */
    fun thisEvening(now: Long, zone: ZoneId): Long {
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val today = local.toLocalDate().atTime(EveningHour, 0)
        val target = if (local.toLocalDateTime().isBefore(today)) today else today.plusDays(1)
        return target.atZone(zone).toInstant().toEpochMilli()
    }

    /** Tomorrow 09:00 (always the next calendar day). */
    fun tomorrowMorning(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atTime(MorningHour, 0)
            .atZone(zone).toInstant().toEpochMilli()

    /** The picker's day, hour and minute as a time, or null when it is not in the future. */
    fun fromPicker(dayOffset: Int, hour: Int, minute: Int, now: Long, zone: ZoneId): Long? {
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(dayOffset.coerceIn(0, MaxDaysAhead).toLong())
        val at = day.atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).atZone(zone).toInstant().toEpochMilli()
        return at.takeIf { it > now }
    }

    /** The first picker value: the next whole [MinuteStep] at least a few minutes ahead, as (dayOffset, hour, minute). */
    fun pickerStart(now: Long, zone: ZoneId): Triple<Int, Int, Int> {
        val local = Instant.ofEpochMilli(now + 600_000L).atZone(zone).toLocalDateTime()
        val minute = (local.minute / MinuteStep + 1) * MinuteStep
        val rounded = local.withMinute(0).withSecond(0).withNano(0).plusMinutes(minute.toLong())
        val days = java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), rounded.toLocalDate())
        return Triple(days.toInt(), rounded.hour, rounded.minute)
    }

    /** "Tue 14:00", or "Tue 2:00 PM"; with a short date when it is a week or more away. */
    fun whenText(at: Long, now: Long, zone: ZoneId, is24h: Boolean, locale: Locale = Locale.getDefault()): String {
        val local = Instant.ofEpochMilli(at).atZone(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), local.toLocalDate())
        val time = DateTimeFormatter.ofPattern(if (is24h) "HH:mm" else "h:mm a", locale).format(local)
        val day = DateTimeFormatter.ofPattern(if (days in 0..6) "EEE" else "EEE d MMM", locale).format(local)
        return "$day $time"
    }

    /** The reminder line of a note: "Reminder · Tue 14:00", or "Reminder · around Tue 14:00" when the alarm is not exact. */
    fun label(at: Long, now: Long, zone: ZoneId, is24h: Boolean, exact: Boolean, locale: Locale = Locale.getDefault()): String =
        "Reminder · " + (if (exact) "" else "around ") + whenText(at, now, zone, is24h, locale)

    /** The picker's day line, for example "Today", "Tomorrow" or "Thu 12 Nov". */
    fun dayText(dayOffset: Int, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val day: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(dayOffset.toLong())
        return when (dayOffset) {
            0 -> "Today"
            1 -> "Tomorrow"
            else -> DateTimeFormatter.ofPattern("EEE d MMM", locale).format(day)
        }
    }

    /** The first non-blank line of a note without its marks, cut to [MaxFirstLine]; empty for an empty note. */
    fun firstLine(text: String): String {
        val line = NoteMarkup.parse(text).map { NoteMarkup.stripped(it).trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        return if (line.length <= MaxFirstLine) line else line.take(MaxFirstLine - 1).trimEnd() + "…"
    }

    /** What an alarm that fires at [now] should do with a note whose stored reminder is [remindAt]. */
    fun isDue(remindAt: Long, now: Long): Boolean = remindAt > 0 && remindAt <= now + ToleranceMs

    /** An inexact alarm can be a little early on some phones; this much is still "due". */
    const val ToleranceMs = 60_000L
}

/** The alarms behind note reminders. A fake stands in for it in tests. */
internal interface NoteReminderAlarms {
    fun schedule(noteId: String, at: Long)
    fun cancel(noteId: String)
}

internal object NoNoteReminderAlarms : NoteReminderAlarms {
    override fun schedule(noteId: String, at: Long) = Unit
    override fun cancel(noteId: String) = Unit
}

/** A request to open one note, set by a tapped notification and consumed by the Notes page. */
internal object NoteOpenRequests {
    const val ExtraNoteId = "open_note_id"
    private val mutable = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val pending = mutable.asStateFlow()
    fun post(noteId: String) { if (noteId.isNotBlank()) mutable.value = noteId }
    fun consume(noteId: String) { if (mutable.value == noteId) mutable.value = null }
}
