package com.brightblu.parc.core.data.stats

import java.util.Locale
import kotlin.math.abs

/**
 * Pure text formatting for [DeviceStats] values, for the stats module in the launcher. Every
 * function returns [Unavailable] for a null input, so callers can pass nullable fields straight
 * through. Locale-independent (the dot is always the decimal separator).
 */
object StatsFormat {

    /** Shown when a value's source is unavailable. */
    const val Unavailable = "—"

    private const val Thousand = 1000.0
    private const val Million = Thousand * Thousand
    private const val Billion = Million * Thousand

    /** Byte rate as `1.2 MB/s`, `512 B/s`, or [Unavailable]. Decimal units, one fraction digit. */
    fun bytesPerSecond(bytesPerSecond: Long?): String {
        val bytes = bytesPerSecond ?: return Unavailable
        val magnitude = abs(bytes)
        return when {
            magnitude < 1000L -> "$bytes B/s"
            magnitude < 1_000_000L -> scaled(bytes, Thousand, "KB/s")
            magnitude < 1_000_000_000L -> scaled(bytes, Million, "MB/s")
            else -> scaled(bytes, Billion, "GB/s")
        }
    }

    /** Temperature as `38.5°`, or [Unavailable]. */
    fun temperature(celsius: Float?): String {
        val value = celsius ?: return Unavailable
        return String.format(Locale.US, "%.1f°", value)
    }

    /** Whole percentage as `45%`, or [Unavailable]. */
    fun percent(percent: Int?): String = percent?.let { "$it%" } ?: Unavailable

    /** Percentage with one fraction digit as `12.4%`, or [Unavailable]. */
    fun percent(percent: Float?): String {
        val value = percent ?: return Unavailable
        return String.format(Locale.US, "%.1f%%", value)
    }

    /** Signed current as `-1205 mA`, or [Unavailable]. */
    fun milliAmps(milliAmps: Float?): String {
        val value = milliAmps ?: return Unavailable
        return String.format(Locale.US, "%.0f mA", value)
    }

    /** Clock as `1.8 GHz`, or [Unavailable]. */
    fun gigaHertz(kiloHertz: Int?): String {
        val value = kiloHertz ?: return Unavailable
        return String.format(Locale.US, "%.1f GHz", value / 1_000_000f)
    }

    /** Thermal headroom (1.0 = severe) as `0.42`, or [Unavailable]. */
    fun headroom(headroom: Float?): String {
        val value = headroom ?: return Unavailable
        return String.format(Locale.US, "%.2f", value)
    }

    /**
     * Uptime as `3d 4h 12m`, `4h 12m`, `12m 30s` or `45s`, or [Unavailable]. A negative value is
     * not an uptime, so it is unavailable rather than a wrapped number.
     */
    fun uptime(uptimeMillis: Long?): String {
        val millis = uptimeMillis ?: return Unavailable
        if (millis < 0L) return Unavailable
        val totalSeconds = millis / 1_000L
        val days = totalSeconds / 86_400L
        val hours = totalSeconds % 86_400L / 3_600L
        val minutes = totalSeconds % 3_600L / 60L
        val seconds = totalSeconds % 60L
        return when {
            days > 0L -> "${days}d ${hours}h ${minutes}m"
            hours > 0L -> "${hours}h ${minutes}m"
            minutes > 0L -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /** Keeps the sign, so a negative rate is never rounded into a plausible positive one. */
    private fun scaled(bytes: Long, divisor: Double, unit: String): String =
        String.format(Locale.US, "%.1f %s", bytes / divisor, unit)
}
