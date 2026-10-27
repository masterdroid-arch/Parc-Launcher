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
}
