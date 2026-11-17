package com.brightblu.parc

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/** Missing options retain the existing 24-hour, minute-only clock. Invalid zones never crash Home. */
internal data class ClockOptions(val hour24: Boolean = true, val seconds: Boolean = false, val zone: String = "") {
    fun encode() = "$hour24|$seconds|$zone"
    companion object {
        fun decode(raw: String?): ClockOptions {
            val fields = raw?.split('|') ?: return ClockOptions()
            return ClockOptions(fields.getOrNull(0) != "false", fields.getOrNull(1) == "true",
                fields.getOrNull(2)?.takeIf { it in ZoneId.getAvailableZoneIds() }.orEmpty())
        }
    }
}

internal object ClockDisplay {
    fun time(now: Long, zone: ZoneId, options: ClockOptions, locale: Locale = Locale.getDefault()): String =
        Instant.ofEpochMilli(now).atZone(zone).format(DateTimeFormatter.ofPattern(
            (if (options.hour24) "HH:mm" else "h:mm") + (if (options.seconds) ":ss" else "") + (if (options.hour24) "" else " a"), locale))
    fun date(now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
        Instant.ofEpochMilli(now).atZone(zone).format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale))
    fun week(now: Long, zone: ZoneId): Int = Instant.ofEpochMilli(now).atZone(zone).get(WeekFields.ISO.weekOfWeekBasedYear())
    fun until(alarm: Long, now: Long): String {
        val minutes = ((alarm - now).coerceAtLeast(0L) / 60_000L) + if (alarm > now && (alarm - now) % 60_000L != 0L) 1 else 0
        return if (minutes >= 60) "in ${minutes / 60} h ${minutes % 60} min" else "in $minutes min"
    }

    /** `Europe/Lisbon` or `America/New_York` become the city: `Lisbon`, `New York`. */
    fun city(zoneId: String): String = zoneId.substringAfterLast('/').replace('_', ' ')

    /** `Today`, `Tomorrow` or `Yesterday` for the second clock's date, as seen from the phone's own date. */
    fun dayWord(now: Long, phone: ZoneId, other: ZoneId): String {
        val here = Instant.ofEpochMilli(now).atZone(phone).toLocalDate()
        val there = Instant.ofEpochMilli(now).atZone(other).toLocalDate()
        return when (java.time.temporal.ChronoUnit.DAYS.between(here, there)) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            else -> date(now, other).substringBefore(',')
        }
    }

    /** `3 h ahead`, `1 h 30 min behind` or `same time`, as seen from the phone. */
    fun offsetText(now: Long, phone: ZoneId, other: ZoneId): String {
        val minutes = (other.rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds - phone.rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds) / 60
        if (minutes == 0) return "same time"
        val abs = kotlin.math.abs(minutes)
        val text = if (abs % 60 == 0) "${abs / 60} h" else if (abs < 60) "$abs min" else "${abs / 60} h ${abs % 60} min"
        return text + if (minutes > 0) " ahead" else " behind"
    }
}
