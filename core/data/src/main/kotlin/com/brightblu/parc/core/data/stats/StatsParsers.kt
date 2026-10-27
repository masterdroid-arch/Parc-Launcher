package com.brightblu.parc.core.data.stats

import kotlin.math.abs

/** Plain TrafficStats byte totals for one reading; the sampler stamps them with its own clock. */
internal data class NetworkBytes(val rxBytes: Long, val txBytes: Long)

/** One byte-counter reading at a monotonic timestamp. */
internal data class NetworkSample(val rxBytes: Long, val txBytes: Long, val atMillis: Long)

/** Per-second byte rates derived from two [NetworkSample]s. */
internal data class NetworkRate(val rxBytesPerSecond: Long, val txBytesPerSecond: Long)

/** Temperatures from one source; null for a sensor that was not reported. */
internal data class TemperatureReading(
    val cpuTemperatureC: Float? = null,
    val gpuTemperatureC: Float? = null,
    val skinTemperatureC: Float? = null,
    val npuTemperatureC: Float? = null,
    val socTemperatureC: Float? = null,
    val batteryTemperatureC: Float? = null,
) {
    /** True when the source reported no sensor at all. */
    val isEmpty: Boolean get() = this == TemperatureReading()
}

/** Raw per-CPU sysfs contents used to group CPUs into cpufreq clusters. */
internal data class RawCpuFreq(
    val cpuIndex: Int,
    val relatedCpus: String?,
    val currentKhz: String?,
)

/**
 * Pure parsing and normalisation for the device-stats sources. No Android types, no I/O; every
 * function returns null rather than a guess when the input is missing, unparsable or implausible.
 */
internal object StatsParsers {

    /** `BatteryManager.getIntProperty` returns this when the property is unsupported. */
    const val UnsupportedProperty = Int.MIN_VALUE

    /**
     * `BATTERY_PROPERTY_CURRENT_NOW` is documented in microamperes, but some devices report
     * milliamperes. Any current above 10 A is impossible, so a magnitude above this bound is read
     * as microamperes and divided by 1000. The boundary is a heuristic: a microampere device
     * reporting less than 10 mA would be read as milliamperes.
     */
    private const val MicroAmpBoundary = 10_000

    /** Signed battery current in milliamperes, or null when the property is unsupported. */
    fun batteryCurrentMilliAmps(rawCurrent: Int): Float? {
        if (rawCurrent == UnsupportedProperty) return null
        return if (rawCurrent in -MicroAmpBoundary..MicroAmpBoundary) {
            rawCurrent.toFloat()
        } else {
            rawCurrent / 1000f
        }
    }

    /** `EXTRA_LEVEL`/`EXTRA_SCALE` as a whole percentage, or null when the range is unusable. */
    fun batteryPercent(level: Int, scale: Int): Int? {
        if (scale <= 0 || level < 0) return null
        return (level * 100 / scale).coerceIn(0, 100)
    }

    /** Maps a `BatteryManager.BATTERY_STATUS_*` value; unknown (1) and anything else become null. */
    fun chargingState(rawStatus: Int): ChargingState? = when (rawStatus) {
        2 -> ChargingState.Charging
        5 -> ChargingState.Full
        3 -> ChargingState.Discharging
        4 -> ChargingState.NotCharging
        else -> null
    }

    /** Maps a `BatteryManager.BATTERY_PLUGGED_*` bitmask; 0 (battery) and dock (8) become null. */
    fun plugType(rawPlugged: Int): PlugType? = when {
        rawPlugged and 1 != 0 -> PlugType.AC
        rawPlugged and 2 != 0 -> PlugType.USB
        rawPlugged and 4 != 0 -> PlugType.Wireless
        else -> null
    }

    /** Maps a `PowerManager.THERMAL_STATUS_*` (0..6) value; anything else becomes null. */
    fun thermalStatus(rawStatus: Int): ThermalStatus? = when (rawStatus) {
        0 -> ThermalStatus.None
        1 -> ThermalStatus.Light
        2 -> ThermalStatus.Moderate
        3 -> ThermalStatus.Severe
        4 -> ThermalStatus.Critical
        5 -> ThermalStatus.Emergency
        6 -> ThermalStatus.Shutdown
        else -> null
    }

    /** `EXTRA_TEMPERATURE` is stored in tenths of a degree Celsius. */
    fun batteryTemperatureCelsius(rawTenths: Int): Float? {
        if (rawTenths == UnsupportedProperty) return null
        return rawTenths / 10f
    }

    /**
     * Bytes per second between two counter readings. The first reading of a session has no
     * previous sample and yields null; a counter that moved backwards (reset) also yields null.
     * Rates are integer bytes per second over the real elapsed time, not over an assumed tick.
     */
    fun networkRate(previous: NetworkSample?, current: NetworkSample): NetworkRate? {
        if (previous == null) return null
        val elapsedMillis = current.atMillis - previous.atMillis
        if (elapsedMillis <= 0L) return null
        val rx = current.rxBytes - previous.rxBytes
        val tx = current.txBytes - previous.txBytes
        if (rx < 0L || tx < 0L) return null
        return NetworkRate(rx * 1000L / elapsedMillis, tx * 1000L / elapsedMillis)
    }

    // --- dumpsys thermalservice -------------------------------------------------------------

    /**
     * A section of `dumpsys thermalservice` that holds sensor readings: a header line that ends
     * with `:` and names temperatures (`Cached temperatures:`, `Current temperatures from HAL:`).
     * A production dump carries two such blocks over the same sensors, so both are read.
     */
    private val TemperatureBlockHeader = Regex("""^\s*[^:]*temperature[^:]*:\s*$""", RegexOption.IGNORE_CASE)
    private const val MinPlausibleTemperatureC = -40f
    private const val MaxPlausibleTemperatureC = 150f

    /**
     * The HAL prints one record per sensor on Android 13+; the platform produces lines exactly like
     * `Temperature{mValue=49.888, mType=0, mName=CPU, mStatus=0}`.
     */
    private val TemperatureRecord = Regex(
        """Temperature\s*\{\s*mValue\s*=\s*(-?\d+(?:\.\d+)?)\s*,""" +
            """\s*mType\s*=\s*(-?\d+)\s*,\s*mName\s*=\s*([^,}]*?)\s*,""" +
            """\s*mStatus\s*=\s*(-?\d+)\s*\}""",
    )

    /** A legacy `name: value` sensor line; anything after the number (unit, note) is ignored. */
    private val SensorLine = Regex("""^\s*([^:=]+?)\s*[:=]\s*(-?\d+(?:\.\d+)?).*$""")

    /**
     * Temperatures from every temperature block of `dumpsys thermalservice`, in the order the dump
     * prints them. Both the Android 13+ record form and the older `name: value` form are read.
     * Sensor names differ per vendor (`CPU`, `cpu0-0`, `gpuss-0`, `npu-therm`, `soc_therm`,
     * `skin-therm`, `batt`), so a sensor matches a slot when its letters contain the slot keyword
     * and the hottest match wins. A non-numeric or implausible reading is skipped: some devices
     * report a dead sensor as `-274.0`, which the plausible range rejects.
     *
     * Reading *every* block matters (T39): a real dump prints the service's cached readings
     * (`Cached temperatures:`, CPU 49.9 / GPU 49.6 / SKIN 45.1) and then a live HAL query
     * (`Current temperatures from HAL:`) whose CPU, GPU, NPU and SOC records are all the `-274.0`
     * sentinel. Reading only the live block loses CPU and GPU even though the dump lists them, and
     * takes its SKIN (31.25) over the cached one. Returns null when no block reports a wanted
     * sensor.
     */
    fun parseThermalservice(text: String): TemperatureReading? {
        val sensors = thermalSensors(text)
        val temperatures = TemperatureReading(
            cpuTemperatureC = hottest(sensors, "cpu"),
            gpuTemperatureC = hottest(sensors, "gpu"),
            skinTemperatureC = hottest(sensors, "skin"),
            npuTemperatureC = hottest(sensors, "npu"),
            socTemperatureC = hottest(sensors, "soc"),
            batteryTemperatureC = hottest(sensors, "batt"),
        )
        return temperatures.takeIf { !it.isEmpty }
    }

    /**
     * Every plausible sensor reading in every temperature block. A line that ends with `:` opens a
     * new section, and only a section whose header names temperatures is scanned, so the cooling
     * devices, the throttling thresholds and the headroom thresholds can never be read as
     * temperatures.
     */
    private fun thermalSensors(text: String): List<Pair<String, Float>> {
        val sensors = mutableListOf<Pair<String, Float>>()
        var inTemperatureBlock = false
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.endsWith(":")) {
                inTemperatureBlock = TemperatureBlockHeader.matches(trimmed)
                continue
            }
            if (!inTemperatureBlock || trimmed.isEmpty()) continue
            val sensor = sensor(trimmed) ?: continue
            if (sensor.second !in MinPlausibleTemperatureC..MaxPlausibleTemperatureC) continue
            sensors += sensor
        }
        return sensors
    }

    /** The sensor name and value on one line, in either supported form, or null. */
    private fun sensor(line: String): Pair<String, Float>? {
        TemperatureRecord.find(line)?.let { match ->
            val value = match.groupValues[1].toFloatOrNull() ?: return null
            return match.groupValues[3].trim() to value
        }
        // A braced record that is not a sensor record is another record type (a throttling
        // threshold, a cooling device) and never a `name: value` sensor line.
        if (line.contains('{')) return null
        val match = SensorLine.matchEntire(line) ?: return null
        val value = match.groupValues[2].toFloatOrNull() ?: return null
        return match.groupValues[1] to value
    }

    private fun hottest(sensors: List<Pair<String, Float>>, keyword: String): Float? =
        sensors.filter { it.first.lowercase().filter(Char::isLetter).contains(keyword) }
            .maxOfOrNull { it.second }

    // --- /sys/class/thermal -----------------------------------------------------------------

    /**
     * Temperatures from the kernel's thermal zones (T44). Each zone has a vendor `type` name and
     * a millidegree `temp`; the same keyword classification as the HAL is used (`cpu0-0`,
     * `mtktsgpu`, `skin-therm`, `battery`, ...), and the hottest matching zone wins per sensor.
     *
     * A zone with an unreadable `type` or `temp` is skipped, as is the `0` many kernels print for
     * an unpopulated zone. Returns null when no wanted sensor was readable, so the caller can fall
     * back to the HAL without inventing a value.
     */
    fun parseThermalZones(zones: List<RawThermalZone>): TemperatureReading? {
        val sensors = zones.mapNotNull { zone ->
            val type = zone.type?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val celsius = thermalZoneCelsius(zone.temp ?: return@mapNotNull null)
                ?: return@mapNotNull null
            type to celsius
        }
        val temperatures = TemperatureReading(
            cpuTemperatureC = hottest(sensors, "cpu"),
            gpuTemperatureC = hottest(sensors, "gpu"),
            skinTemperatureC = hottest(sensors, "skin"),
            npuTemperatureC = hottest(sensors, "npu"),
            socTemperatureC = hottest(sensors, "soc"),
            batteryTemperatureC = hottest(sensors, "batt"),
        )
        return temperatures.takeIf { !it.isEmpty }
    }

    /**
     * One thermal-zone `temp` file as degrees Celsius. The kernel prints millidegrees
     * (`49900` = 49.9 °C) on every Android device seen so far; a magnitude below 1000 is read as
     * degrees because some vendor kernels print that. `0` is the usual "zone not populated"
     * value, and the `-274` sentinel (and anything outside a device's physical range) is rejected.
     */
    fun thermalZoneCelsius(raw: String): Float? {
        val value = raw.trim().toFloatOrNull() ?: return null
        val celsius = if (abs(value) >= MillidegreeThreshold) value / 1000f else value
        if (celsius == 0f) return null
        return celsius.takeIf { it in MinPlausibleTemperatureC..MaxPlausibleTemperatureC }
    }

    private const val MillidegreeThreshold = 1000f

    /**
     * Per-sensor choice between the two temperature sources (T44): the kernel zone wins when it
     * was readable, and the HAL fills only the sensors the kernel did not report. Both inputs are
     * already validated, so this never combines readings from different moments into one sensor.
     */
    fun mergeTemperatureSources(
        primary: TemperatureReading?,
        fallback: TemperatureReading?,
    ): TemperatureReading = TemperatureReading(
        cpuTemperatureC = primary?.cpuTemperatureC ?: fallback?.cpuTemperatureC,
        gpuTemperatureC = primary?.gpuTemperatureC ?: fallback?.gpuTemperatureC,
        skinTemperatureC = primary?.skinTemperatureC ?: fallback?.skinTemperatureC,
        npuTemperatureC = primary?.npuTemperatureC ?: fallback?.npuTemperatureC,
        socTemperatureC = primary?.socTemperatureC ?: fallback?.socTemperatureC,
        batteryTemperatureC = primary?.batteryTemperatureC ?: fallback?.batteryTemperatureC,
    )

    // --- storage ----------------------------------------------------------------------------

    /**
     * Free space as a whole percentage of total space. `availableBytes` (what an app may still
     * write, reserved blocks excluded) is used rather than the raw free count. Returns null when
     * the total is unusable, so a broken mount never renders as 0 % or 100 %.
     */
    fun storageFreePercent(totalBytes: Long, availableBytes: Long): Int? {
        if (totalBytes <= 0L || availableBytes < 0L) return null
        return (availableBytes * 100L / totalBytes).toInt().coerceIn(0, 100)
    }

    /**
     * Bytes used on the data partition, matching [storageFreePercent]: used = total − available.
     * Null when the total is unusable or the free count is outside it, so the HUD can hide the
     * storage tile rather than print a nonsense ratio.
     */
    fun storageUsage(totalBytes: Long, availableBytes: Long): StorageUsage? {
        if (totalBytes <= 0L || availableBytes < 0L || availableBytes > totalBytes) return null
        return StorageUsage(usedBytes = totalBytes - availableBytes, totalBytes = totalBytes)
    }

    // --- Wi-Fi ------------------------------------------------------------------------------

    /**
     * `WifiInfo.INVALID_RSSI` (-127) means "no measurement", and 0 is not a signal either, so both
     * are unavailable. Anything above -1 dBm is outside the physical range of the radio.
     */
    private const val MinPlausibleRssiDbm = -126
    private const val MaxPlausibleRssiDbm = -1

    /** Signal strength in dBm, or null when the platform reported no usable measurement. */
    fun wifiRssiDbm(rawRssi: Int): Int? =
        rawRssi.takeIf { it in MinPlausibleRssiDbm..MaxPlausibleRssiDbm }

    /** The fastest reported TX/RX link speed in Mbps, or null when neither is a usable value. */
    fun wifiLinkSpeedMbps(txMbps: Int?, rxMbps: Int?): Int? =
        listOfNotNull(txMbps, rxMbps).filter { it > 0 }.maxOrNull()

    // --- dumpsys cpuinfo --------------------------------------------------------------------

    private val LoadLine = Regex("""^\s*Load:\s*(.+)$""", RegexOption.MULTILINE)

    /**
     * The summary line of a `dumpsys cpuinfo` block. The real line is prefixed with the overall
     * percentage and has no `TOTAL:` at the start of the line, for example
     * `23% TOTAL: 9.6% user + 5.1% kernel + 0.2% iowait + 8.6% irq + 0.2% softirq`, so the prefix
     * is optional in this pattern. A dump that does start the line with `TOTAL:` is also read.
     */
    private val TotalLine = Regex("""^\s*(?:[\d.]+\s*%\s*)?TOTAL:\s*(.+)$""", RegexOption.MULTILINE)
    private val TotalCategory =
        Regex("""([\d.]+)\s*%\s*(user|kernel|irq|softirq)""", RegexOption.IGNORE_CASE)

    /**
     * Busy CPU percentage from the `TOTAL:` line of `dumpsys cpuinfo`: user + kernel + irq +
     * softirq (iowait and idle are not CPU load). The `Load:` line above it holds the three load
     * averages, which are counts of runnable tasks and not a percentage, so it is used only to
     * find the block's own `TOTAL:` line; a dump without a `Load:` line falls back to its first
     * `TOTAL:`. The overall percentage that the real line carries before the word `TOTAL` is the
     * same sum and is not used. Returns null when no `TOTAL:` line carries a recognised category.
     * The result is clamped to 0..100.
     */
    fun parseCpuinfoLoadPercent(text: String): Float? {
        val total = totalLine(text) ?: return null
        val counted = TotalCategory.findAll(total).mapNotNull { match ->
            match.groupValues[1].toFloatOrNull()
        }.toList()
        if (counted.isEmpty()) return null
        val sum = counted.sumOf { it.toDouble() }.toFloat()
        return sum.coerceIn(0f, 100f)
    }

    private fun totalLine(text: String): String? {
        val afterLoad = LoadLine.findAll(text).lastOrNull()?.range?.last
            ?.let { loadIndex -> TotalLine.find(text, loadIndex + 1) }
        return (afterLoad ?: TotalLine.find(text))?.groupValues?.get(1)
    }

    // --- /sys cpufreq -----------------------------------------------------------------------

    /**
     * Groups CPUs into clusters. `cpufreq/related_cpus` (for example `0-5`) is the cluster key, so
     * CPUs sharing a policy merge; a CPU without that file becomes its own cluster. Clusters are
     * ordered by their lowest CPU index and every CPU in a cluster runs at the policy frequency,
     * so the first readable reading describes the cluster.
     */
    fun parseCpuClusters(raw: List<RawCpuFreq>): List<CpuCluster> =
        raw.groupBy { freq ->
            freq.relatedCpus?.trim()?.takeIf { it.isNotEmpty() } ?: "cpu${freq.cpuIndex}"
        }
            .values
            .sortedBy { cluster -> cluster.minOf { it.cpuIndex } }
            .map { members ->
                CpuCluster(
                    cpus = members.map { it.cpuIndex }.sorted(),
                    currentKhz = members.mapNotNull { it.currentKhz?.toIntOrNull() }.firstOrNull(),
                )
            }

    /**
     * Merges one `dumpsys thermalservice` round into the sample held so far. A round that reports
     * temperatures replaces them wholesale: a sensor that went away must not keep showing the
     * previous value (T44's liveness rule then covers a frozen source). A round with no usable
     * temperature block keeps the previous reading, which the sampler only does while the source
     * is being carried between ticks, never after a failed read (T58).
     */
    fun mergeDumpsys(
        previous: DumpsysSample?,
        temperatures: TemperatureReading?,
    ): DumpsysSample {
        val base = previous ?: DumpsysSample()
        return DumpsysSample(temperatures = temperatures ?: base.temperatures)
    }

    // --- /proc/stat --------------------------------------------------------------------------

    /**
     * The aggregate tick counters from the `cpu` line of `/proc/stat` (T58). The line is
     * `cpu user nice system idle iowait irq softirq steal guest guest_nice`; `guest` and
     * `guest_nice` are already included in `user`/`nice`, so at most the first eight counters are
     * summed. `idle` carries `idle + iowait`, the usual definition of non-busy time.
     *
     * Returns null for a denied or malformed file, a non-positive total, or an idle count above
     * the total, so a bad read is unavailable rather than a bogus percentage.
     */
    fun parseProcStat(text: String): ProcStatTicks? {
        val line = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it == "cpu" || it.startsWith("cpu ") }
            ?: return null
        val parts = line.split(Whitespace)
        if (parts.size < 6) return null
        val counters = parts.drop(1).take(8).map { it.toLongOrNull() ?: return null }
        if (counters.any { it < 0L }) return null
        val total = counters.sum()
        val idle = counters[3] + counters[4]
        if (total <= 0L || idle < 0L || idle > total) return null
        return ProcStatTicks(total = total, idle = idle)
    }

    /**
     * Busy CPU percentage between two `/proc/stat` readings. The denominator is the total tick
     * delta and the numerator is the non-idle delta, so both a busy sample and a long idle period
     * are represented correctly. Null when the counters did not advance (a stalled or reset
     * source) rather than a fake 0 %.
     */
    fun cpuLoadPercent(previous: ProcStatTicks, current: ProcStatTicks): Float? {
        val totalDelta = current.total - previous.total
        if (totalDelta <= 0L) return null
        val busyDelta = (current.total - current.idle) - (previous.total - previous.idle)
        if (busyDelta < 0L) return null
        return (busyDelta * 100.0 / totalDelta).toFloat().coerceIn(0f, 100f)
    }

    private val Whitespace = Regex("""\s+""")
}
