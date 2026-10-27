package com.brightblu.parc.core.data.timers

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** T101: pure text for the Timers page. */
object TimerFormat {
    /** Countdown text, rounded up to whole seconds: `0:05`, `9:59`, `1:02:03`. */
    fun countdown(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
        val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    /** Stopwatch text with hundredths: `00:07.42`, `1:02:03.45`. */
    fun stopwatch(ms: Long): String {
        val total = ms.coerceAtLeast(0L)
        val cs = total % 1000 / 10
        val seconds = total / 1000
        val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d.%02d".format(Locale.ROOT, h, m, s, cs)
        else "%02d:%02d.%02d".format(Locale.ROOT, m, s, cs)
    }

    /** A duration the way a person says it: `1 min`, `1 h 30 min`, `45 s`. */
    fun duration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1000
        val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
        val parts = buildList {
            if (h > 0) add("$h h")
            if (m > 0) add("$m min")
            if (s > 0) add("$s s")
        }
        return if (parts.isEmpty()) "0 s" else parts.joinToString(" ")
    }

    fun statusText(status: TimerStatus): String = when (status) {
        TimerStatus.Idle -> "Ready"
        TimerStatus.Running -> "Running"
        TimerStatus.Paused -> "Paused"
        TimerStatus.Finished -> "Done"
    }

    fun presetLabel(minutes: Int): String = duration(minutes * 60_000L)

    /** `Tue 07:30` (or `Tue 7:30 AM`); a null trigger gives the empty line. */
    fun nextAlarm(triggerMs: Long?, zone: ZoneId, is24: Boolean, locale: Locale = Locale.getDefault()): String {
        if (triggerMs == null) return "No alarm set"
        val pattern = if (is24) "EEE HH:mm" else "EEE h:mm a"
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(Instant.ofEpochMilli(triggerMs))
    }

    /** `Lap 3`. */
    fun lapName(index: Int): String = "Lap ${index + 1}"
}
