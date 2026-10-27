package com.brightblu.parc

import com.brightblu.parc.core.data.stats.ChargingState
import com.brightblu.parc.core.data.stats.CpuCluster
import com.brightblu.parc.core.data.stats.DeviceStats
import com.brightblu.parc.core.data.stats.PlugType
import com.brightblu.parc.core.data.stats.StorageUsage
import com.brightblu.parc.core.data.stats.StatsFormat
import com.brightblu.parc.core.data.stats.ThermalStatus
import com.brightblu.parc.model.Layer
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One temperature reading for the HUD's arc gauges. [label] is the fixed two- or three-letter
 * sensor name (CPU/GPU/Skin) and [celsius] is the reading; a reading is always non-null here
 * because unavailable sensors are filtered out before construction.
 */
data class TempGauge(val label: String, val celsius: Float)

/**
 * The stats HUD's rendered snapshot (T33). Every field is a display fact or a formatted string;
 * nothing here touches Android. Unavailable sensors are carried as `null`/empty so the composable
 * can hide a cell rather than print an em dash, except where the whole module is empty
 * ([allUnavailable]).
 */
data class StatsPresentation(
    val cpuLoad: String,
    val batteryPercent: Int?,
    val charging: Boolean,
    val plug: String?,
    /** The arc gauges that actually have a reading (CPU, GPU, Skin), in that order. */
    val temperatures: List<TempGauge>,
    /** The collapsed "one temperature": the first available of CPU, GPU, Skin, battery. */
    val oneTemperature: TempGauge?,
    val thermalChip: String?,
    val ramPercent: Int?,
    val storagePercent: Int?,
    val storageText: String?,
    val staleTemperatures: String?,
    val networkDown: String,
    val networkUp: String,
    val networkAvailable: Boolean,
    val wifiBars: Int?,
    val wifiLinkSpeed: String?,
    val uptime: String,
    val allUnavailable: Boolean,
) {
    companion object {
        fun of(stats: DeviceStats): StatsPresentation {
            val temperatures = listOfNotNull(
                stats.cpuTemperatureC?.let { TempGauge("CPU", it) },
                stats.gpuTemperatureC?.let { TempGauge("GPU", it) },
                stats.skinTemperatureC?.let { TempGauge("Skin", it) },
            )
            val one = temperatures.firstOrNull()
                ?: stats.batteryTemperatureC?.let { TempGauge("Bat", it) }
            return StatsPresentation(
                cpuLoad = stats.cpuLoadPercent?.let { "${it.roundToInt()}%" } ?: StatsFormat.Unavailable,
                batteryPercent = stats.batteryPercent,
                charging = isCharging(stats.chargingState),
                plug = plugLabel(stats.plug),
                temperatures = temperatures,
                oneTemperature = one,
                thermalChip = stats.thermalStatus?.let { thermalLabel(it) },
                ramPercent = stats.ramUsedPercent,
                storagePercent = stats.storage?.usedPercent,
                storageText = storageText(stats.storage),
                staleTemperatures = stats.staleTemperatureSensors.sortedBy { it.ordinal }
                    .takeIf { it.isNotEmpty() }?.joinToString("  ") { "${it.name.uppercase(Locale.ROOT)} ~" },
                networkDown = StatsFormat.bytesPerSecond(stats.networkRxBytesPerSecond),
                networkUp = StatsFormat.bytesPerSecond(stats.networkTxBytesPerSecond),
                networkAvailable = stats.networkRxBytesPerSecond != null ||
                    stats.networkTxBytesPerSecond != null,
                wifiBars = wifiBars(stats.wifiRssiDbm),
                wifiLinkSpeed = stats.wifiLinkSpeedMbps?.let { "$it Mbps" },
                uptime = StatsFormat.uptime(stats.uptimeMillis),
                allUnavailable = stats.isAllUnavailable,
            )
        }
    }
}

/** Decimal GB, matching the capacity unit printed on storage devices; formatted off the draw loop. */
fun storageText(storage: StorageUsage?): String? {
    val usage = storage ?: return null
    if (usage.usedPercent == null) return null
    return String.format(Locale.ROOT, "%.1f / %.1f GB", usage.usedBytes / 1_000_000_000.0,
        usage.totalBytes / 1_000_000_000.0)
}

fun storageColor(percent: Int, skin: Skin): Long =
    if (percent > Metrics.hudStorageWarnPercent) skin.colors.warn else skin.colors.accent

/**
 * The HUD temperature scale colour: accent while cool, [SkinColors.warn] amber above
 * [Metrics.hudTempWarnC], and [SkinColors.accent2] magenta above [Metrics.hudTempHotC]. Pure and
 * deterministic so the threshold colours are unit-tested.
 */
fun temperatureColor(celsius: Float, skin: Skin): Long = when {
    celsius < Metrics.hudTempWarnC -> skin.colors.accent
    celsius < Metrics.hudTempHotC -> skin.colors.warn
    else -> skin.colors.accent2
}

/** The fraction (0..1) of the full gauge sweep a temperature represents; clamped to the max. */
fun temperatureFraction(celsius: Float): Float =
    (celsius / Metrics.hudTempMaxC).coerceIn(0f, 1f)

/** The full battery bar as `segments` booleans: true = filled, left to right. */
fun batterySegments(percent: Int?, segments: Int = Metrics.hudBatterySegments): List<Boolean>? {
    val pct = percent ?: return null
    val filled = (pct.coerceIn(0, 100) * segments / 100f).roundToInt()
    return List(segments) { it < filled }
}

/** Wi-Fi signal bars (0..[Metrics.hudWifiBars]) from RSSI in dBm; null when the source is absent. */
fun wifiBars(rssiDbm: Int?): Int? = when {
    rssiDbm == null -> null
    rssiDbm >= -50 -> Metrics.hudWifiBars
    rssiDbm >= -60 -> Metrics.hudWifiBars - 1
    rssiDbm >= -70 -> Metrics.hudWifiBars - 2
    rssiDbm >= -80 -> 1
    else -> 0
}

/** The charging bolt shows while charging or full-on-plug, not while merely plugged and idle. */
fun isCharging(state: ChargingState?): Boolean =
    state == ChargingState.Charging || state == ChargingState.Full

/** Short plug label for the charging indicator; null means nothing plugged in. */
fun plugLabel(plug: PlugType?): String? = when (plug) {
    PlugType.AC -> "AC"
    PlugType.USB -> "USB"
    PlugType.Wireless -> "Wireless"
    null -> null
}

/** Thermal-throttling level as a short label, or "—" when unavailable. */
fun thermalLabel(status: ThermalStatus?): String = status?.name ?: StatsFormat.Unavailable

/** The cluster clock to display: the highest readable cluster frequency, or null. */
fun cpuClockKhz(clusters: List<CpuCluster>?): Int? =
    clusters?.mapNotNull { it.currentKhz }?.maxOrNull()

/**
 * Whether the module may be shown at all, from the shell state alone: the setting is on, the Home
 * layer is current, and search is closed. Clock remains usable without sensor readings.
 */
fun statsEligible(enabled: Boolean, layer: Layer, searchOpen: Boolean): Boolean =
    enabled && layer == Layer.Home && !searchOpen

/** Sensor availability never hides Clock, Media or the page controls. */
@Suppress("UNUSED_PARAMETER")
fun statsModuleVisible(eligible: Boolean, stats: DeviceStats): Boolean =
    eligible

/** Sampling runs only while all three gates hold: resumed, screen on, and module eligible. */
fun statsActive(resumed: Boolean, screenOn: Boolean, eligible: Boolean): Boolean =
    resumed && screenOn && eligible
