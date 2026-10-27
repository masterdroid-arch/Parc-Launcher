package com.brightblu.parc.core.data.stats

import java.io.File

/** One `/sys/class/thermal/thermal_zone*` entry: vendor type name and raw `temp` text. */
internal data class RawThermalZone(val type: String?, val temp: String?)

/**
 * The kernel thermal-zone boundary, so the parser can be tested without a device.
 *
 * Thread-safety: implementations must be safe to call from any thread; the production reader has
 * no state.
 */
internal interface ThermalZoneSource {
    /** Every zone the process may read, or null when the directory itself is unreadable. */
    fun readZones(): List<RawThermalZone>?
}

/**
 * Production reader. `/sys/class/thermal` is world-readable on most kernels, but SELinux can deny
 * the app process even when a shell can read the files, so a missing directory, a denied open or a
 * missing file all become null and the stats layer falls back to `dumpsys thermalservice`.
 *
 * Only the small `type` and `temp` files are read; nothing is written and no permission is
 * required. Values are parsed by [StatsParsers.parseThermalZones].
 */
internal object FileThermalZoneSource : ThermalZoneSource {

    private const val Root = "/sys/class/thermal"
    private val ZoneName = Regex("""thermal_zone\d+""")

    override fun readZones(): List<RawThermalZone>? {
        val zones = try {
            File(Root).listFiles { file -> ZoneName.matches(file.name) }
        } catch (unexpected: Throwable) {
            null
        } ?: return null
        if (zones.isEmpty()) return null
        return zones.sortedBy { it.name }.map { zone ->
            RawThermalZone(
                type = readText(File(zone, "type")),
                temp = readText(File(zone, "temp")),
            )
        }
    }

    private fun readText(file: File): String? = try {
        file.readText().trim()
    } catch (unexpected: Throwable) {
        null
    }
}
