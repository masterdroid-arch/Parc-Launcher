package com.brightblu.parc

import com.brightblu.parc.core.data.power.*
import com.brightblu.parc.core.data.network.MobileNetworkType
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
