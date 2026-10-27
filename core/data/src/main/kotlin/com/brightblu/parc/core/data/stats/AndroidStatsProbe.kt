package com.brightblu.parc.core.data.stats

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [StatsProbe] over public Android APIs: `BatteryManager` plus the sticky
 * `ACTION_BATTERY_CHANGED` broadcast for percent/status/plug/temperature, `PowerManager` for
 * thermal state, `TrafficStats` for device byte counters, `ActivityManager.MemoryInfo` for RAM,
 * `StatFs` for storage, `WifiManager` for the current Wi-Fi link (ACCESS_WIFI_STATE only),
 * `/sys/.../cpufreq` for CPU clocks, `/proc/stat` for CPU load and `dumpsys` (DUMP permission
 * only) for the vendor temperatures. `dumpsys cpuinfo` is read only when `/proc/stat` is denied
 * (T58).
 *
 * Every read that the platform can refuse is either checked (DUMP, ACCESS_WIFI_STATE) or mapped to
 * null, never guessed. The deprecated `registerReceiver(null, filter)` overload only reads the
 * sticky broadcast: no receiver is registered, nothing stays alive, and the Android 14 receiver-flag
 * requirement applies to registered receivers only.
 */
internal class AndroidStatsProbe(
    context: Context,
    private val dumpsysRunner: DumpsysRunner,
    private val sysfs: SysfsReader = FileSysfsReader,
    private val zoneSource: ThermalZoneSource = FileThermalZoneSource,
    private val procStatSource: ProcStatSource = FileProcStatSource,
    private val cpuCount: Int = Runtime.getRuntime().availableProcessors(),
    private val forecastSeconds: Int = DefaultForecastSeconds,
) : StatsProbe {

    private val appContext = context.applicationContext
    private val isDebuggable =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val batteryManager =
        appContext.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /** The previous thermalservice round, so a round without a usable block keeps the last one. */
    private var previousDumpsys: DumpsysSample? = null

    override suspend fun battery(): BatterySample? {
        val status = batteryStatusIntent()
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val rawTemperature = status
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, StatsParsers.UnsupportedProperty)
            ?: StatsParsers.UnsupportedProperty
        val rawPlugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, NoPlug) ?: NoPlug
        val rawStatus = status
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        return BatterySample(
            percent = StatsParsers.batteryPercent(level, scale) ?: capacityPercent(),
            chargingState = StatsParsers.chargingState(rawStatus),
            plug = StatsParsers.plugType(rawPlugged),
            temperatureC = StatsParsers.batteryTemperatureCelsius(rawTemperature),
            currentMa = StatsParsers.batteryCurrentMilliAmps(
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            ),
        )
    }

    override suspend fun thermal(): ThermalSample = ThermalSample(
        status = StatsParsers.thermalStatus(powerManager.currentThermalStatus),
        headroom = powerManager.getThermalHeadroom(forecastSeconds).takeIf { it.isFinite() },
    )

    override suspend fun memory(): MemorySample {
        val info = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(info)
        val total = info.totalMem
        if (total <= 0L) return MemorySample()
        val used = ((total - info.availMem) * 100L / total).toInt().coerceIn(0, 100)
        return MemorySample(usedPercent = used)
    }

    override suspend fun storage(): StorageSample? = withContext(Dispatchers.IO) {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes
            val available = stat.availableBytes
            StorageSample(
                freePercent = StatsParsers.storageFreePercent(total, available),
                usedBytes = StatsParsers.storageUsage(total, available)?.usedBytes,
                totalBytes = total.takeIf { it > 0L },
            )
        } catch (unexpected: Throwable) {
            null
        }
    }

    /**
     * The current Wi-Fi link. `WifiManager.getConnectionInfo` is deprecated on API 31+ but is kept
     * because it is the only source that needs ACCESS_WIFI_STATE alone; the replacement
     * (`ConnectivityManager.getNetworkCapabilities`) would also need ACCESS_NETWORK_STATE, which
     * this task is not allowed to add. RSSI and link speed are not personal data, so they are not
     * redacted the way the SSID is. Unverified on API 36: a disconnected link is expected to
     * report `INVALID_RSSI` (-127) and a non-positive link speed, both of which map to null.
     */
    @Suppress("DEPRECATION")
    override suspend fun wifi(): WifiSample? {
        val granted = appContext.checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val manager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val info = try {
            manager.connectionInfo
        } catch (unexpected: Throwable) {
            return null
        } ?: return null
        return WifiSample(
            rssiDbm = StatsParsers.wifiRssiDbm(info.rssi),
            linkSpeedMbps = StatsParsers.wifiLinkSpeedMbps(
                txMbps = info.txLinkSpeedMbps,
                rxMbps = info.rxLinkSpeedMbps,
            ),
        )
    }

    override suspend fun network(): NetworkBytes? {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        // Both methods return TrafficStats.UNSUPPORTED (-1) where the device has no counters.
        if (rx < 0L || tx < 0L) return null
        return NetworkBytes(rxBytes = rx, txBytes = tx)
    }

    override suspend fun cpuClusters(): List<CpuCluster>? = withContext(Dispatchers.IO) {
        val readings = (0 until cpuCount).mapNotNull { cpu ->
            val currentKhz = sysfs.read(cpuFreqPath(cpu)) ?: return@mapNotNull null
            RawCpuFreq(
                cpuIndex = cpu,
                relatedCpus = sysfs.read(relatedCpusPath(cpu)),
                currentKhz = currentKhz,
            )
        }
        StatsParsers.parseCpuClusters(readings).takeIf { it.isNotEmpty() }
    }

    /**
     * Kernel thermal zones, read fresh on every tick (T44). On a denied kernel this returns null
     * after a failed directory listing, which is cheap; it never throws and needs no permission.
     */
    override suspend fun thermalZones(): TemperatureReading? = withContext(Dispatchers.IO) {
        zoneSource.readZones()?.let { StatsParsers.parseThermalZones(it) }
    }

    override fun dumpsysGranted(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    override fun reset() {
        // The next active period starts on a clean slate.
        previousDumpsys = null
    }

    override suspend fun dumpsys(): DumpsysSample = withContext(Dispatchers.IO) {
        // A new process is spawned on every round: no dump text is ever cached (T44).
        val text = dumpsysRunner.run(ServiceThermalservice)
        val temperatures = text?.let { StatsParsers.parseThermalservice(it) }
        StatsParsers.mergeDumpsys(previousDumpsys, temperatures)
            .also {
                previousDumpsys = it
                logThermalservice(text, it)
            }
    }

    /**
     * `/proc/stat` is the preferred CPU-load source (T58): world-readable on most kernels, no
     * permission and no process. On this app's SELinux domain the read can still be denied; that
     * becomes null and [StatsSampler] tries the `dumpsys cpuinfo` fallback. Unverified from the
     * app UID at implementation time, so both paths are exercised by tests.
     */
    override suspend fun procStatTicks(): ProcStatTicks? = withContext(Dispatchers.IO) {
        val text = procStatSource.read()
        val ticks = text?.let { StatsParsers.parseProcStat(it) }
        logProcStat(text, ticks)
        ticks
    }

    /**
     * The DUMP-only fallback. The `cpuinfo` alias is not reachable by every app UID (T39 observed
     * `Can't find service: cpuinfo` on the emulator), so a short or unparsable dump is null and
     * the HUD hides the line. This is the only `cpuinfo` spawn; the temperature round no longer
     * alternates with it (T58).
     */
    override suspend fun dumpsysCpuLoad(): Float? = withContext(Dispatchers.IO) {
        val text = dumpsysRunner.run(ServiceCpuinfo)
        val load = text?.let { StatsParsers.parseCpuinfoLoadPercent(it) }
        logCpuinfo(text, load)
        load
    }

    /**
     * Debug-build diagnostics (T39). On a device, `adb logcat -s Parc:D` then shows whether the
     * `dumpsys` child process produced output at all and which slots came back filled, which is
     * what distinguishes a refused spawn from a parse that finds nothing. Debug builds only
     * (`FLAG_DEBUGGABLE`), and it logs no app name and no notification text: the values are
     * hardware facts.
     */
    private fun logThermalservice(text: String?, sample: DumpsysSample) {
        if (!isDebuggable) return
        Log.d(
            LogTag,
            "stats dumpsys=$ServiceThermalservice bytes=${text?.length ?: -1} " +
                "slots=${sample.filledSlots()}",
        )
    }

    private fun logCpuinfo(text: String?, load: Float?) {
        if (!isDebuggable) return
        Log.d(
            LogTag,
            "stats dumpsys=$ServiceCpuinfo bytes=${text?.length ?: -1} " +
                "slots=${if (load == null) "none" else "load"}",
        )
    }

    private fun logProcStat(text: String?, ticks: ProcStatTicks?) {
        if (!isDebuggable) return
        Log.d(
            LogTag,
            "stats procstat bytes=${text?.length ?: -1} " +
                "slots=${if (ticks == null) "none" else "ticks"}",
        )
    }

    private fun DumpsysSample.filledSlots(): String = buildList {
        temperatures.cpuTemperatureC?.let { add("cpu") }
        temperatures.gpuTemperatureC?.let { add("gpu") }
        temperatures.skinTemperatureC?.let { add("skin") }
        temperatures.npuTemperatureC?.let { add("npu") }
        temperatures.socTemperatureC?.let { add("soc") }
        temperatures.batteryTemperatureC?.let { add("battery") }
    }.joinToString(separator = ",").ifEmpty { "none" }

    private val NoPlug = 0

    /** Reads the sticky battery broadcast; no receiver is registered. */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun batteryStatusIntent(): Intent? = try {
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (unexpected: RuntimeException) {
        null
    }

    private fun capacityPercent(): Int? = batteryManager
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        .takeIf { it != StatsParsers.UnsupportedProperty }
        ?.coerceIn(0, 100)

    private fun cpuFreqPath(cpu: Int) = "$CpuRoot/cpu$cpu/cpufreq/scaling_cur_freq"

    private fun relatedCpusPath(cpu: Int) = "$CpuRoot/cpu$cpu/cpufreq/related_cpus"

    companion object {
        const val DefaultForecastSeconds = 0
        const val ServiceThermalservice = "thermalservice"
        const val ServiceCpuinfo = "cpuinfo"

        /** PROJECT.md §9: the debug log tag, used only by [logRound]. */
        private const val LogTag = "Parc"
        private const val CpuRoot = "/sys/devices/system/cpu"
    }
}
