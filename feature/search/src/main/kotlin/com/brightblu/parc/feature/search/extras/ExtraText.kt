package com.brightblu.parc.feature.search.extras

import com.brightblu.parc.core.data.agenda.AgendaEvent
import com.brightblu.parc.core.data.agenda.AgendaRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Pure caption text for the extra result rows. */
object ExtraText {

    const val SettingsHeader = "Phone settings"
    const val FilesHeader = "Files"
    const val EventsHeader = "Calendar"

    /** `Today 14:00`, `Tomorrow 09:30`, `Fri 24 Oct 09:30`, or `Tomorrow, all day`. */
    fun eventWhen(
        event: AgendaEvent,
        nowMs: Long,
        zone: ZoneId,
        is24: Boolean,
        locale: Locale = Locale.getDefault(),
    ): String {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        // All-day events are UTC dates; timed ones are instants in the phone's zone.
        val date: LocalDate = if (event.allDay) Instant.ofEpochMilli(event.beginMs).atZone(ZoneOffset.UTC).toLocalDate()
        else Instant.ofEpochMilli(event.beginMs).atZone(zone).toLocalDate()
        val running = !event.allDay && event.beginMs <= nowMs
        val shownDate = if (date.isBefore(today)) today else date
        val day = when (ChronoUnit.DAYS.between(today, shownDate)) {
            0L -> "Today"
            1L -> "Tomorrow"
            else -> shownDate.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
        }
        return when {
            event.allDay -> "$day, all day"
            running -> "$day, now"
            else -> "$day ${AgendaRules.clock(event.beginMs, zone, is24, locale)}"
        }
    }

    /** `Image`, `Video`, `Audio`, `Download` for the second line of a file row. */
    fun fileKind(kind: FileKind): String = when (kind) {
        FileKind.Image -> "Image"
        FileKind.Video -> "Video"
        FileKind.Audio -> "Audio"
        FileKind.Download -> "Download"
        FileKind.Other -> "File"
    }
}
