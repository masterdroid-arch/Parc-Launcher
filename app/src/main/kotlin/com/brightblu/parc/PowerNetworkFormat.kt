package com.brightblu.parc

import com.brightblu.parc.core.data.power.*
import com.brightblu.parc.core.data.network.MobileNetworkType
import com.brightblu.parc.core.data.stats.StatsArchivePoint
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Locale-stable HUD readouts; missing values never become zero. */
internal object PowerNetworkFormat {
    const val Missing = "—"
    fun watts(power: ChargingPower?): String = power?.let {
        "${if (it.direction == PowerDirection.Charging) "↓" else "↑"} ${String.format(Locale.US, "%.1f W", it.watts)}"
    } ?: Missing
    fun duration(millis: Long?): String {
        if (millis == null || millis < 0) return Missing
        val minutes = millis / 60_000
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
    fun bytes(bytes: Long?): String {
        if (bytes == null || bytes < 0) return Missing
        return when {
            bytes < 1_000 -> "$bytes B"
            bytes < 1_000_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
            bytes < 1_000_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            else -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        }
    }
    fun health(value: BatteryHealth?): String = when (value) {
        null -> Missing
        BatteryHealth.Good -> "Good"
        BatteryHealth.Overheat -> "Overheated"
        BatteryHealth.Dead -> "Dead"
        BatteryHealth.OverVoltage -> "Over voltage"
        BatteryHealth.UnspecifiedFailure -> "Failure"
        BatteryHealth.Cold -> "Cold"
        BatteryHealth.Unknown -> "Unknown"
    }
    fun network(value: MobileNetworkType?): String = when (value) {
        null -> Missing
        MobileNetworkType.Nr -> "5G NR"
        MobileNetworkType.Lte -> "LTE"
        MobileNetworkType.Hspap -> "HSPA+"
        MobileNetworkType.Hspa -> "HSPA"
        MobileNetworkType.Umts -> "UMTS"
        MobileNetworkType.Gsm -> "GSM"
        MobileNetworkType.Cdma -> "CDMA"
        MobileNetworkType.IWlan -> "IWLAN"
    }
}

/** T159: one bar of "Last 8 hours". [percent] is null for an hour with no archived sample (a gap). */
internal data class HourBar(val startMillis: Long, val hourOfDay: Int, val percent: Int?, val current: Boolean)

/** T159: the pure parts of the Power page (DESIGN S22): the dot meter, the hour bars and their words. */
internal object PowerPage {
    const val MeterDots = 20
    const val LowPercent = 15
    const val BarCount = 8
    const val MaxFontScale = 1.3f
    const val MeterHeightDp = 12f
    const val DotFill = 0.6f
    const val BarsHeightDp = 48f
    const val BarFill = 0.5f
    const val EmptyHistory = "Collecting data, come back in an hour"

    /** Filled dots of the 20-dot meter: `round(percent / 5)`, clamped to 0..20; a missing reading fills none. */
    fun filledDots(percent: Int?): Int =
        if (percent == null) 0 else Math.round(percent.coerceIn(0, 100) / 5f).coerceIn(0, MeterDots)

    /** At 15 % or below the filled dots use the accent. */
    fun isLow(percent: Int?): Boolean = percent != null && percent <= LowPercent

    /**
     * The last [BarCount] local hours, oldest first, the current hour last. An hour's bar is the newest
     * archived level inside that hour (its level at the end of the hour; for the current hour, now).
     * An hour without a sample is a gap (null), so fewer than 8 hours of data leaves the leading bars empty.
     * Walks local hours with [ZoneId] rules so a DST change does not shift the bars.
     */
    fun hourBars(points: List<StatsArchivePoint>, nowMillis: Long, zone: ZoneId): List<HourBar> {
        val currentStart = Instant.ofEpochMilli(nowMillis).atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        val starts = (BarCount - 1 downTo 0).map { currentStart.minusHours(it.toLong()) }
        return starts.mapIndexed { i, start ->
            val from = start.toInstant().toEpochMilli()
            val to = if (i == BarCount - 1) nowMillis + 1 else starts[i + 1].toInstant().toEpochMilli()
            val level = points.lastOrNull { it.atMillis in from until to }
            HourBar(from, start.hour, level?.value?.let { Math.round(it).toInt().coerceIn(0, 100) }, i == BarCount - 1)
        }
    }

    fun hasData(bars: List<HourBar>): Boolean = bars.any { it.percent != null }

    /** Bar height as a fraction of the track; a gap is 0. */
    fun barFraction(bar: HourBar): Float = (bar.percent ?: 0) / 100f

    fun hourLabel(bar: HourBar): String = if (bar.current) "now" else String.format(Locale.US, "%02d", bar.hourOfDay)

    fun chargingState(charging: Boolean): String = if (charging) "Charging" else "Not charging"

    /** "Full in 1h 22m", or null when the system gave no usable time. */
    fun fullIn(charging: Boolean, millis: Long?): String? =
        if (charging && millis != null && millis > 0) "Full in ${PowerNetworkFormat.duration(millis)}" else null

    /** Screen-on time as whole hours and the remaining minutes; null when usage access gave nothing. */
    fun hoursMinutes(millis: Long?): Pair<Int, Int>? {
        if (millis == null || millis < 0) return null
        val minutes = (millis / 60_000).toInt()
        return minutes / 60 to minutes % 60
    }
}
