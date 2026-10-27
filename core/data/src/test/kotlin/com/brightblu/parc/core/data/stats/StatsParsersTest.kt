package com.brightblu.parc.core.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure parser tests over pasted `dumpsys` text and plain values; no Android runtime needed. */
class StatsParsersTest {

    // --- battery ----------------------------------------------------------------------------

    @Test
    fun `battery current is microamperes when the magnitude is above the boundary`() {
        assertEquals(1520f, StatsParsers.batteryCurrentMilliAmps(1_520_000)!!, 0.001f)
        assertEquals(-1234.567f, StatsParsers.batteryCurrentMilliAmps(-1_234_567)!!, 0.001f)
        assertEquals(10.001f, StatsParsers.batteryCurrentMilliAmps(10_001)!!, 0.001f)
    }

    @Test
    fun `battery current is read as milliamperes when the magnitude is small`() {
        assertEquals(3500f, StatsParsers.batteryCurrentMilliAmps(3500)!!, 0.001f)
        assertEquals(-2500f, StatsParsers.batteryCurrentMilliAmps(-2500)!!, 0.001f)
        assertEquals(10_000f, StatsParsers.batteryCurrentMilliAmps(10_000)!!, 0.001f)
    }

    @Test
    fun `an unsupported battery current property is null and zero stays zero`() {
        assertNull(StatsParsers.batteryCurrentMilliAmps(StatsParsers.UnsupportedProperty))
        assertEquals(0f, StatsParsers.batteryCurrentMilliAmps(0)!!, 0.001f)
    }

    @Test
    fun `battery percent needs a usable level and scale`() {
        assertEquals(50, StatsParsers.batteryPercent(level = 50, scale = 100))
        assertEquals(0, StatsParsers.batteryPercent(level = 0, scale = 100))
        assertEquals(100, StatsParsers.batteryPercent(level = 100, scale = 100))
        assertEquals(100, StatsParsers.batteryPercent(level = 150, scale = 100))
        assertNull(StatsParsers.batteryPercent(level = 1, scale = 0))
        assertNull(StatsParsers.batteryPercent(level = -1, scale = 100))
    }

    @Test
    fun `battery status codes map to the four states and unknown is null`() {
        assertEquals(ChargingState.Charging, StatsParsers.chargingState(2))
        assertEquals(ChargingState.Full, StatsParsers.chargingState(5))
        assertEquals(ChargingState.Discharging, StatsParsers.chargingState(3))
        assertEquals(ChargingState.NotCharging, StatsParsers.chargingState(4))
        assertNull(StatsParsers.chargingState(1))
        assertNull(StatsParsers.chargingState(0))
        assertNull(StatsParsers.chargingState(99))
    }

    @Test
    fun `plug bitmask maps to AC, USB or wireless and nothing else`() {
        assertEquals(PlugType.AC, StatsParsers.plugType(1))
        assertEquals(PlugType.USB, StatsParsers.plugType(2))
        assertEquals(PlugType.Wireless, StatsParsers.plugType(4))
        assertEquals(PlugType.AC, StatsParsers.plugType(1 or 2))
        assertNull(StatsParsers.plugType(0))
        assertNull(StatsParsers.plugType(8))
    }

    @Test
    fun `thermal status codes cover 0 to 6 and reject anything else`() {
        assertEquals(ThermalStatus.None, StatsParsers.thermalStatus(0))
        assertEquals(ThermalStatus.Light, StatsParsers.thermalStatus(1))
        assertEquals(ThermalStatus.Moderate, StatsParsers.thermalStatus(2))
        assertEquals(ThermalStatus.Severe, StatsParsers.thermalStatus(3))
        assertEquals(ThermalStatus.Critical, StatsParsers.thermalStatus(4))
        assertEquals(ThermalStatus.Emergency, StatsParsers.thermalStatus(5))
        assertEquals(ThermalStatus.Shutdown, StatsParsers.thermalStatus(6))
        assertNull(StatsParsers.thermalStatus(7))
        assertNull(StatsParsers.thermalStatus(-1))
    }

    @Test
    fun `battery temperature arrives in tenths of a degree`() {
        assertEquals(38.5f, StatsParsers.batteryTemperatureCelsius(385)!!, 0.001f)
        assertEquals(-5f, StatsParsers.batteryTemperatureCelsius(-50)!!, 0.001f)
        assertNull(StatsParsers.batteryTemperatureCelsius(StatsParsers.UnsupportedProperty))
    }

    // --- dumpsys thermalservice -------------------------------------------------------------

    private val thermalserviceSample = """
        IsStatusOverride: false
        Thermal Status: 0
        Thermal Status severity: 0
        Current temperatures from HAL:
          CPU: 41.2
          GPU: 39.8
          SKIN: 33.4
          BATTERY: 31.0
        Current cooling devices from HAL:
          cpu-cooling: 0
          gpu-cooling: 0
    """.trimIndent()

    @Test
    fun `thermalservice block yields the wanted temperatures`() {
        val temperatures = StatsParsers.parseThermalservice(thermalserviceSample)!!

        assertEquals(41.2f, temperatures.cpuTemperatureC!!, 0.001f)
        assertEquals(39.8f, temperatures.gpuTemperatureC!!, 0.001f)
        assertEquals(33.4f, temperatures.skinTemperatureC!!, 0.001f)
    }

    @Test
    fun `the cooling device section never becomes a temperature`() {
        val temperatures = StatsParsers.parseThermalservice(thermalserviceSample)!!

        assertEquals(41.2f, temperatures.cpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `vendor sensor names match on their letters`() {
        val text = """
            Current temperatures from HAL:
            	CPU: 43.56 degC
            	GPUSS-0: 40.1
            	skin-therm: 35.2
        """.trimIndent()

        val temperatures = StatsParsers.parseThermalservice(text)!!

        assertEquals(43.56f, temperatures.cpuTemperatureC!!, 0.001f)
        assertEquals(40.1f, temperatures.gpuTemperatureC!!, 0.001f)
        assertEquals(35.2f, temperatures.skinTemperatureC!!, 0.001f)
    }

    @Test
    fun `the hottest matching sensor wins`() {
        val text = """
            Current temperatures from HAL:
              cpu0-0: 39.0
              cpu1-0: 44.5
              gpu0: 38.0
        """.trimIndent()

        val temperatures = StatsParsers.parseThermalservice(text)!!

        assertEquals(44.5f, temperatures.cpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `an absent block or only unrelated sensors is null`() {
        assertNull(StatsParsers.parseThermalservice("Thermal Status: 0"))
        assertNull(
            StatsParsers.parseThermalservice(
                """
                Current temperatures from HAL:
                  AMBIENT: 26.5
                  PMIC: 40.0
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a block with only a battery sensor reports only the battery temperature`() {
        val temperatures = StatsParsers.parseThermalservice(
            """
            Current temperatures from HAL:
              BATTERY: 31.0
              AMBIENT: 26.5
            """.trimIndent(),
        )!!

        assertEquals(31f, temperatures.batteryTemperatureC!!, 0.001f)
        assertNull(temperatures.cpuTemperatureC)
        assertNull(temperatures.gpuTemperatureC)
    }

    @Test
    fun `an implausible reading is skipped without losing the others`() {
        val text = """
            Current temperatures from HAL:
              CPU: 9999.0
              GPU: 39.8
              SKIN: not-a-number
        """.trimIndent()

        val temperatures = StatsParsers.parseThermalservice(text)!!

        assertNull(temperatures.cpuTemperatureC)
        assertEquals(39.8f, temperatures.gpuTemperatureC!!, 0.001f)
        assertNull(temperatures.skinTemperatureC)
    }

    /**
     * A real device block (Android 16): the HAL prints one record per sensor, and its dead CPU/NPU
     * sensors report the `-274.0` sentinel.
     */
    private val deviceThermalserviceSample = """
        IsStatusOverride: false
        Thermal Status: 0
        Current temperatures from HAL:
          Temperature{mValue=49.888, mType=0, mName=CPU, mStatus=0}
          Temperature{mValue=-274.0, mType=0, mName=CPU, mStatus=0}
          Temperature{mValue=47.5, mType=0, mName=GPU, mStatus=0}
          Temperature{mValue=41.25, mType=0, mName=NPU, mStatus=0}
          Temperature{mValue=44.1, mType=0, mName=SOC, mStatus=0}
          Temperature{mValue=38.2, mType=0, mName=skin-therm, mStatus=0}
          Temperature{mValue=31.0, mType=0, mName=BATTERY, mStatus=0}
        Current cooling devices from HAL:
          Temperature{mValue=0.0, mType=0, mName=cpu-cooling, mStatus=0}
        Cooling device states from HAL:
        Restart count: 3
    """.trimIndent()

    @Test
    fun `the HAL record format yields all six wanted temperatures`() {
        val temperatures = StatsParsers.parseThermalservice(deviceThermalserviceSample)!!

        assertEquals(49.888f, temperatures.cpuTemperatureC!!, 0.001f)
        assertEquals(47.5f, temperatures.gpuTemperatureC!!, 0.001f)
        assertEquals(41.25f, temperatures.npuTemperatureC!!, 0.001f)
        assertEquals(44.1f, temperatures.socTemperatureC!!, 0.001f)
        assertEquals(38.2f, temperatures.skinTemperatureC!!, 0.001f)
        assertEquals(31.0f, temperatures.batteryTemperatureC!!, 0.001f)
    }

    @Test
    fun `the minus 274 sentinel is dropped without losing the rest of the block`() {
        val text = """
            Current temperatures from HAL:
              Temperature{mValue=-274.0, mType=0, mName=CPU, mStatus=0}
              Temperature{mValue=47.5, mType=0, mName=GPU, mStatus=0}
        """.trimIndent()

        val temperatures = StatsParsers.parseThermalservice(text)!!

        assertNull(temperatures.cpuTemperatureC)
        assertEquals(47.5f, temperatures.gpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `a sentinel never outranks a real reading of the same sensor`() {
        val text = """
            Current temperatures from HAL:
              Temperature{mValue=-274.0, mType=0, mName=CPU, mStatus=0}
              Temperature{mValue=49.888, mType=0, mName=CPU, mStatus=0}
              Temperature{mValue=44.0, mType=0, mName=cpu0-0, mStatus=0}
        """.trimIndent()

        val temperatures = StatsParsers.parseThermalservice(text)!!

        assertEquals(49.888f, temperatures.cpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `a line after the block header never becomes a temperature`() {
        val text = """
            Current temperatures from HAL:
              Temperature{mValue=49.888, mType=0, mName=CPU, mStatus=0}
            Current cooling devices from HAL:
              Temperature{mValue=99.0, mType=0, mName=cooling-cpu, mStatus=0}
        """.trimIndent()

        val temperatures = StatsParsers.parseThermalservice(text)!!

        assertEquals(49.888f, temperatures.cpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `both sensor line formats can appear in one block`() {
        val text = """
            Current temperatures from HAL:
              Temperature{mValue=41.5, mType=0, mName=CPU, mStatus=0}
              GPU: 39.8
              NPU: 40.0 degC
        """.trimIndent()

        val temperatures = StatsParsers.parseThermalservice(text)!!

        assertEquals(41.5f, temperatures.cpuTemperatureC!!, 0.001f)
        assertEquals(39.8f, temperatures.gpuTemperatureC!!, 0.001f)
        assertEquals(40.0f, temperatures.npuTemperatureC!!, 0.001f)
    }

    @Test
    fun `a sentinel-only block reports nothing at all`() {
        val text = """
            Current temperatures from HAL:
              Temperature{mValue=-274.0, mType=0, mName=CPU, mStatus=0}
        """.trimIndent()

        assertNull(StatsParsers.parseThermalservice(text))
    }

    // --- T39: a captured device dump --------------------------------------------------------

    /** A device's `dumpsys thermalservice`, verbatim from `reports/evidence/T39/`. */
    private fun deviceThermalservice(): String = checkNotNull(
        javaClass.getResourceAsStream("/device-thermalservice.txt"),
    ) { "device-thermalservice.txt is missing from the test resources" }
        .bufferedReader()
        .use { it.readText() }

    @Test
    fun `the captured device dump yields every wanted temperature`() {
        // The HUD showed no CPU or GPU tile on that device although this dump lists both. The live
        // HAL block reports -274.0 for CPU/GPU/NPU/SOC, so the values come from the cached block
        // that the old parser never read.
        val temperatures = StatsParsers.parseThermalservice(deviceThermalservice())!!

        assertEquals(49.888f, temperatures.cpuTemperatureC!!, 0.001f)
        assertEquals(49.628f, temperatures.gpuTemperatureC!!, 0.001f)
        assertEquals(49.781f, temperatures.npuTemperatureC!!, 0.001f)
        assertEquals(49.781f, temperatures.socTemperatureC!!, 0.001f)
        assertEquals(45.125f, temperatures.skinTemperatureC!!, 0.001f)
        assertEquals(43.9f, temperatures.batteryTemperatureC!!, 0.001f)
    }

    @Test
    fun `the device's cached reading wins over the live block's lower and lower-only sensors`() {
        // The live block reports a plausible SKIN (31.25) and BATTERY (29.3) while its CPU/GPU are
        // sentinels. The cached block holds 45.125 and 43.9 for the same two sensors; the cached
        // SKIN record also carries mStatus=2, which matches the platform's own Thermal Status: 2.
        val temperatures = StatsParsers.parseThermalservice(deviceThermalservice())!!

        assertEquals(45.125f, temperatures.skinTemperatureC!!, 0.001f)
        assertEquals(43.9f, temperatures.batteryTemperatureC!!, 0.001f)
    }

    @Test
    fun `a cached block alone is parsed`() {
        val text = """
            Cached temperatures:
              Temperature{mValue=41.0, mType=0, mName=CPU, mStatus=0}
              Temperature{mValue=37.5, mType=0, mName=GPU, mStatus=0}
            HAL Ready: true
        """.trimIndent()

        val temperatures = StatsParsers.parseThermalservice(text)!!

        assertEquals(41f, temperatures.cpuTemperatureC!!, 0.001f)
        assertEquals(37.5f, temperatures.gpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `a dead sensor in one block never hides a reading of the same sensor in another`() {
        val text = """
            Cached temperatures:
              Temperature{mValue=49.888, mType=0, mName=CPU, mStatus=0}
              Temperature{mValue=49.628, mType=0, mName=GPU, mStatus=0}
            HAL Ready: true
            HAL connection:
            	ThermalHAL AIDL 3  connected: yes
            Current temperatures from HAL:
              Temperature{mValue=-274.0, mType=0, mName=CPU, mStatus=0}
              Temperature{mValue=-274.0, mType=0, mName=GPU, mStatus=0}
        """.trimIndent()

        val temperatures = StatsParsers.parseThermalservice(text)!!

        assertEquals(49.888f, temperatures.cpuTemperatureC!!, 0.001f)
        assertEquals(49.628f, temperatures.gpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `across two blocks the hottest match of a sensor wins`() {
        val text = """
            Cached temperatures:
              CPU: 40.0
              GPU: 52.5
            Current temperatures from HAL:
              CPU: 44.0
              GPU: 50.0
        """.trimIndent()

        val temperatures = StatsParsers.parseThermalservice(text)!!

        assertEquals(44f, temperatures.cpuTemperatureC!!, 0.001f)
        assertEquals(52.5f, temperatures.gpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `the threshold and cooling sections of the captured dump never become temperatures`() {
        val text = """
            Current temperatures from HAL:
              Temperature{mValue=-274.0, mType=0, mName=CPU, mStatus=0}
            Current cooling devices from HAL:
              CoolingDevice{mValue=0, mType=4, mName=mutt-pa1}
            Temperature static thresholds from HAL:
              TemperatureThreshold{mType=0, mName=CPU, mHotThrottlingThresholds=[NaN, NaN, NaN, 85.0, 90.0], mColdThrottlingThresholds=[NaN, NaN, NaN, NaN, NaN]}
            Temperature headroom thresholds:
            [NaN, 0.76666665, 0.8333333, 1.0]
        """.trimIndent()

        assertNull(StatsParsers.parseThermalservice(text))
    }

    // --- /sys/class/thermal ----------------------------------------------------------------

    @Test
    fun `kernel thermal zones yield temperatures in millidegrees`() {
        val reading = StatsParsers.parseThermalZones(
            listOf(
                RawThermalZone("cpu0-0", "49900"),
                RawThermalZone("cpu1-0", "51250"),
                RawThermalZone("gpuss-0", "49628"),
                RawThermalZone("skin-therm", "45125"),
                RawThermalZone("npu-therm", "42000"),
                RawThermalZone("soc_therm", "44000"),
                RawThermalZone("battery", "30000"),
            ),
        )!!

        assertEquals(51.25f, reading.cpuTemperatureC!!, 0.001f)
        assertEquals(49.628f, reading.gpuTemperatureC!!, 0.001f)
        assertEquals(45.125f, reading.skinTemperatureC!!, 0.001f)
        assertEquals(42f, reading.npuTemperatureC!!, 0.001f)
        assertEquals(44f, reading.socTemperatureC!!, 0.001f)
        assertEquals(30f, reading.batteryTemperatureC!!, 0.001f)
    }

    @Test
    fun `a kernel that prints degrees is read as degrees`() {
        assertEquals(49f, StatsParsers.thermalZoneCelsius("49")!!, 0.001f)
        assertEquals(-5f, StatsParsers.thermalZoneCelsius("-5")!!, 0.001f)
        assertEquals(49.5f, StatsParsers.thermalZoneCelsius("49500")!!, 0.001f)
    }

    @Test
    fun `unpopulated and sentinel zones are unavailable`() {
        assertNull(StatsParsers.thermalZoneCelsius("0"))
        assertNull(StatsParsers.thermalZoneCelsius("-274000"))
        assertNull(StatsParsers.thermalZoneCelsius("-274"))
        assertNull(StatsParsers.thermalZoneCelsius("999900"))
        assertNull(StatsParsers.thermalZoneCelsius("not-a-number"))
        assertNull(StatsParsers.thermalZoneCelsius(""))
    }

    @Test
    fun `a zone without a type or a readable temperature is skipped`() {
        val reading = StatsParsers.parseThermalZones(
            listOf(
                RawThermalZone(null, "49000"),
                RawThermalZone("", "49000"),
                RawThermalZone("cpu0-0", null),
                RawThermalZone("cpu0-0", "garbage"),
                RawThermalZone("gpu0", "48000"),
            ),
        )!!

        assertNull(reading.cpuTemperatureC)
        assertEquals(48f, reading.gpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `unrelated zones never become a wanted sensor`() {
        val reading = StatsParsers.parseThermalZones(
            listOf(
                RawThermalZone("mtktswmt", "48000"),
                RawThermalZone("mtktspa", "46000"),
                RawThermalZone("charger", "40000"),
                RawThermalZone("mtktscpu", "51000"),
            ),
        )!!

        assertEquals(51f, reading.cpuTemperatureC!!, 0.001f)
        assertNull(reading.gpuTemperatureC)
        assertNull(reading.skinTemperatureC)
        assertNull(reading.batteryTemperatureC)
    }

    @Test
    fun `a zone set with no wanted sensor is null, not an empty reading`() {
        assertNull(StatsParsers.parseThermalZones(emptyList()))
        assertNull(
            StatsParsers.parseThermalZones(
                listOf(RawThermalZone("wifi", "39000"), RawThermalZone("cpu0-0", "0")),
            ),
        )
    }

    @Test
    fun `the kernel zone wins per sensor and the HAL fills only the rest`() {
        val zones = TemperatureReading(cpuTemperatureC = 50f, skinTemperatureC = 44f)
        val hal = TemperatureReading(
            cpuTemperatureC = 49.888f,
            gpuTemperatureC = 49.628f,
            skinTemperatureC = 45.125f,
        )

        val merged = StatsParsers.mergeTemperatureSources(zones, hal)

        assertEquals(50f, merged.cpuTemperatureC!!, 0.001f)
        assertEquals(49.628f, merged.gpuTemperatureC!!, 0.001f)
        assertEquals(44f, merged.skinTemperatureC!!, 0.001f)
        assertNull(merged.batteryTemperatureC)
    }

    @Test
    fun `a missing source is simply absent from the merge`() {
        val hal = TemperatureReading(gpuTemperatureC = 47f)

        assertEquals(hal, StatsParsers.mergeTemperatureSources(null, hal))
        assertEquals(hal, StatsParsers.mergeTemperatureSources(TemperatureReading(), hal))
        assertEquals(TemperatureReading(), StatsParsers.mergeTemperatureSources(null, null))
    }

    // --- storage ----------------------------------------------------------------------------

    @Test
    fun `free storage is a whole percentage of the total`() {
        assertEquals(25, StatsParsers.storageFreePercent(totalBytes = 100_000L, availableBytes = 25_000L))
        assertEquals(100, StatsParsers.storageFreePercent(totalBytes = 8L, availableBytes = 8L))
        assertEquals(0, StatsParsers.storageFreePercent(totalBytes = 8L, availableBytes = 0L))
    }

    @Test
    fun `an unusable total or a negative available count is null`() {
        assertNull(StatsParsers.storageFreePercent(totalBytes = 0L, availableBytes = 10L))
        assertNull(StatsParsers.storageFreePercent(totalBytes = -4L, availableBytes = 10L))
        assertNull(StatsParsers.storageFreePercent(totalBytes = 100L, availableBytes = -1L))
    }

    @Test
    fun `used storage is total minus available bytes`() {
        val usage = StatsParsers.storageUsage(
            totalBytes = 256_000_000_000L,
            availableBytes = 138_000_000_000L,
        )!!

        assertEquals(118_000_000_000L, usage.usedBytes)
        assertEquals(256_000_000_000L, usage.totalBytes)
        assertEquals(46, usage.usedPercent)
    }

    @Test
    fun `an impossible storage range is unavailable so the tile can hide`() {
        assertNull(StatsParsers.storageUsage(totalBytes = 0L, availableBytes = 10L))
        assertNull(StatsParsers.storageUsage(totalBytes = 100L, availableBytes = -1L))
        assertNull(StatsParsers.storageUsage(totalBytes = 100L, availableBytes = 101L))
    }

    @Test
    fun `storage percentages are whole and bounded`() {
        assertEquals(0, StorageUsage(usedBytes = 0L, totalBytes = 100L).usedPercent)
        assertEquals(100, StorageUsage(usedBytes = 100L, totalBytes = 100L).usedPercent)
        assertEquals(33, StorageUsage(usedBytes = 1L, totalBytes = 3L).usedPercent)
        assertNull(StorageUsage(usedBytes = 1L, totalBytes = 0L).usedPercent)
        assertNull(StorageUsage(usedBytes = -1L, totalBytes = 10L).usedPercent)
    }

    // --- Wi-Fi ------------------------------------------------------------------------------

    @Test
    fun `an RSSI of minus 127 or zero is no measurement`() {
        assertNull(StatsParsers.wifiRssiDbm(-127))
        assertNull(StatsParsers.wifiRssiDbm(0))
        assertNull(StatsParsers.wifiRssiDbm(12))
        assertNull(StatsParsers.wifiRssiDbm(-200))
        assertEquals(-58, StatsParsers.wifiRssiDbm(-58))
        assertEquals(-1, StatsParsers.wifiRssiDbm(-1))
    }

    @Test
    fun `link speed is the fastest usable direction and null when neither reports one`() {
        assertEquals(433, StatsParsers.wifiLinkSpeedMbps(txMbps = 216, rxMbps = 433))
        assertEquals(120, StatsParsers.wifiLinkSpeedMbps(txMbps = 120, rxMbps = null))
        assertNull(StatsParsers.wifiLinkSpeedMbps(txMbps = 0, rxMbps = -1))
        assertNull(StatsParsers.wifiLinkSpeedMbps(txMbps = null, rxMbps = null))
    }

    // --- dumpsys cpuinfo --------------------------------------------------------------------

    private val cpuinfoSample = """
        Load: 2.71 / 2.13 / 1.98
        CPU usage from 5623ms to 0ms ago (2026-10-15 10:20:31.411 to 2026-10-15 10:20:37.034):
          34% 1234/com.android.systemui: 22% user + 12% kernel / faults: 1234 minor
          11% 567/system_server: 7% user + 4% kernel / faults: 210 minor
          6% 890/com.brightblu.parc: 5% user + 1% kernel
        TOTAL: 48% user + 12% kernel + 0.3% iowait + 0% irq + 0.2% softirq
    """.trimIndent()

    @Test
    fun `cpuinfo TOTAL line becomes busy percent without iowait`() {
        assertEquals(60.2f, StatsParsers.parseCpuinfoLoadPercent(cpuinfoSample)!!, 0.001f)
    }

    @Test
    fun `the TOTAL line after the Load line is the one that counts`() {
        val text = """
            Load: 9.99 / 8.88 / 7.77
            TOTAL: 5% user + 5% kernel + 0% irq + 0% softirq
        """.trimIndent()

        assertEquals(10f, StatsParsers.parseCpuinfoLoadPercent(text)!!, 0.001f)
    }

    @Test
    fun `load averages are never read as a percentage`() {
        // The Load: numbers are runnable-task counts, not a percentage: without a TOTAL line the
        // load is unavailable rather than "999 %".
        assertNull(StatsParsers.parseCpuinfoLoadPercent("Load: 9.99 / 8.88 / 7.77"))
    }

    @Test
    fun `cpuinfo without a TOTAL line is null`() {
        assertNull(StatsParsers.parseCpuinfoLoadPercent("Load: 0.5 / 0.4 / 0.3"))
        assertNull(StatsParsers.parseCpuinfoLoadPercent("TOTAL: nothing to see"))
    }

    @Test
    fun `cpuinfo percent is clamped to one hundred`() {
        val text = "TOTAL: 90% user + 40% kernel + 0% iowait + 0% irq + 0% softirq"

        assertEquals(100f, StatsParsers.parseCpuinfoLoadPercent(text)!!, 0.001f)
    }

    // --- T39: the real cpuinfo summary line ------------------------------------------------

    @Test
    fun `the real TOTAL line is prefixed with the overall percentage`() {
        // Verbatim from a real dump (`reports/evidence/T39/emulator-cpuinfo-shell.txt`, line 95).
        // The old pattern required the line to start with TOTAL:, so no real dump ever matched.
        val text = """
            Load: 1.76 / 1.19 / 0.77
             32% 27562/com.android.systemui: 26% user + 5.1% kernel / faults: 1332635 minor
              4% 5085/com.example.parc: 2.8% user + 1.1% kernel / faults: 23407 minor
            23% TOTAL: 9.6% user + 5.1% kernel + 0.2% iowait + 8.6% irq + 0.2% softirq
        """.trimIndent()

        assertEquals(23.5f, StatsParsers.parseCpuinfoLoadPercent(text)!!, 0.001f)
    }

    @Test
    fun `an indented or unprefixed TOTAL line is still read`() {
        assertEquals(
            8f,
            StatsParsers.parseCpuinfoLoadPercent(
                "  90% TOTAL: 4% user + 3% kernel + 0% iowait + 1% irq + 0% softirq",
            )!!,
            0.001f,
        )
        assertEquals(
            8f,
            StatsParsers.parseCpuinfoLoadPercent(
                "TOTAL: 4% user + 3% kernel + 0% iowait + 1% irq + 0% softirq",
            )!!,
            0.001f,
        )
    }

    @Test
    fun `a per process line is never read as the summary`() {
        val text = """
            Load: 1.00 / 1.00 / 1.00
             20% 2338/system_server: 10% user + 9.9% kernel / faults: 2809 minor 9 major
            12% TOTAL: 7% user + 5% kernel + 0% irq + 0% softirq
        """.trimIndent()

        assertEquals(12f, StatsParsers.parseCpuinfoLoadPercent(text)!!, 0.001f)
    }

    @Test
    fun `the captured cpuinfo has no TOTAL line so it can never yield a load`() {
        // reports/evidence/phone_cpuinfo.txt, copied verbatim. It stops after the per-process
        // rows, so T58's fallback must report null rather than a guess.
        val text = checkNotNull(javaClass.getResourceAsStream("/device-cpuinfo.txt"))
            .bufferedReader()
            .use { it.readText() }

        assertNull(StatsParsers.parseCpuinfoLoadPercent(text))
    }


    // --- cpufreq sysfs ----------------------------------------------------------------------

    @Test
    fun `related cpus group the cpus of one policy into a cluster`() {
        val raw = listOf(
            RawCpuFreq(0, "0-5", "1800000"),
            RawCpuFreq(1, "0-5", "1800000"),
            RawCpuFreq(2, "0-5", "1800000"),
            RawCpuFreq(6, "6-7", "2000000"),
            RawCpuFreq(7, "6-7", "2000000"),
        )

        val clusters = StatsParsers.parseCpuClusters(raw)

        assertEquals(2, clusters.size)
        assertEquals(listOf(0, 1, 2), clusters[0].cpus)
        assertEquals(1_800_000, clusters[0].currentKhz)
        assertEquals(listOf(6, 7), clusters[1].cpus)
        assertEquals(2_000_000, clusters[1].currentKhz)
    }

    @Test
    fun `a missing related cpus file makes each cpu its own cluster`() {
        val clusters = StatsParsers.parseCpuClusters(
            listOf(
                RawCpuFreq(4, null, "1200000"),
                RawCpuFreq(5, "  ", "1200000"),
            ),
        )

        assertEquals(2, clusters.size)
        assertEquals(listOf(4), clusters[0].cpus)
        assertEquals(listOf(5), clusters[1].cpus)
    }

    @Test
    fun `an unparsable clock leaves the cluster frequency null`() {
        val clusters = StatsParsers.parseCpuClusters(listOf(RawCpuFreq(0, "0-7", "n/a")))

        assertEquals(1, clusters.size)
        assertNull(clusters[0].currentKhz)
    }

    // --- network deltas ---------------------------------------------------------------------

    @Test
    fun `the first network sample has no rate`() {
        assertNull(StatsParsers.networkRate(null, NetworkSample(1_000, 2_000, atMillis = 5_000)))
    }

    @Test
    fun `rates are deltas over the real elapsed time`() {
        val previous = NetworkSample(rxBytes = 1_000, txBytes = 2_000, atMillis = 1_000)
        val oneSecond = StatsParsers.networkRate(
            previous,
            NetworkSample(rxBytes = 2_000, txBytes = 2_500, atMillis = 2_000),
        )!!
        assertEquals(1_000L, oneSecond.rxBytesPerSecond)
        assertEquals(500L, oneSecond.txBytesPerSecond)

        val halfSecond = StatsParsers.networkRate(
            previous,
            NetworkSample(rxBytes = 1_500, txBytes = 2_250, atMillis = 1_500),
        )!!
        assertEquals(1_000L, halfSecond.rxBytesPerSecond)
        assertEquals(500L, halfSecond.txBytesPerSecond)
    }

    @Test
    fun `a counter reset or a zero interval is null`() {
        val previous = NetworkSample(rxBytes = 5_000, txBytes = 5_000, atMillis = 1_000)

        assertNull(StatsParsers.networkRate(previous, previous.copy(rxBytes = 4_000, atMillis = 2_000)))
        assertNull(StatsParsers.networkRate(previous, previous.copy(atMillis = 1_000)))
        assertNull(StatsParsers.networkRate(previous, previous.copy(atMillis = 500)))
    }

    // --- dumpsys merge ----------------------------------------------------------------------

    @Test
    fun `a thermalservice round replaces temperatures instead of carrying a vanished sensor`() {
        val previous = DumpsysSample(
            temperatures = TemperatureReading(cpuTemperatureC = 49.9f, gpuTemperatureC = 45f),
        )

        val merged = StatsParsers.mergeDumpsys(
            previous = previous,
            temperatures = TemperatureReading(gpuTemperatureC = 46f),
        )

        assertNull(merged.temperatures.cpuTemperatureC)
        assertEquals(46f, merged.temperatures.gpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `a merge with no temperature block keeps the previous reading`() {
        val previous = DumpsysSample(
            temperatures = TemperatureReading(cpuTemperatureC = 41f, gpuTemperatureC = 39f),
        )

        val merged = StatsParsers.mergeDumpsys(previous = previous, temperatures = null)

        assertEquals(41f, merged.temperatures.cpuTemperatureC!!, 0.001f)
        assertEquals(39f, merged.temperatures.gpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `a merge with nothing at all is the all-unavailable sample`() {
        val sample = StatsParsers.mergeDumpsys(null, null)

        assertEquals(DumpsysSample(), sample)
        assertEquals(true, sample.isAllUnavailable)
    }

    // --- /proc/stat (T58) -------------------------------------------------------------------

    private val procStatSample = """
        cpu  2994995 606138 1770629 51954266 26579 3090695 75219 0 0 0
        cpu0 749118 151534 442657 12988566 6644 772673 18804 0 0 0
        intr 1234567 0 0 0
    """.trimIndent()

    @Test
    fun `the proc stat cpu line becomes total and idle ticks`() {
        val ticks = StatsParsers.parseProcStat(procStatSample)!!

        // user + nice + system + idle + iowait + irq + softirq + steal.
        assertEquals(60_518_521L, ticks.total)
        // idle + iowait.
        assertEquals(51_980_845L, ticks.idle)
    }

    @Test
    fun `a malformed proc stat line is null`() {
        assertNull(StatsParsers.parseProcStat(""))
        assertNull(StatsParsers.parseProcStat("intr 1 2 3"))
        assertNull(StatsParsers.parseProcStat("cpu  1 2 3"))
        assertNull(StatsParsers.parseProcStat("cpu  1 2 3 nope 5 6 7 8"))
        assertNull(StatsParsers.parseProcStat("cpu  0 0 0 0 0 0 0 0"))
    }

    @Test
    fun `the second reading gives the busy percentage of the delta only`() {
        val previous = ProcStatTicks(total = 1_000L, idle = 850L)
        val current = ProcStatTicks(total = 1_090L, idle = 880L)

        // 90 ticks passed, 60 of them busy: 66.67 %.
        assertEquals(66.6667f, StatsParsers.cpuLoadPercent(previous, current)!!, 0.001f)
    }

    @Test
    fun `a fully idle or fully busy delta is zero or one hundred`() {
        assertEquals(
            0f,
            StatsParsers.cpuLoadPercent(
                ProcStatTicks(total = 1_000L, idle = 900L),
                ProcStatTicks(total = 1_100L, idle = 1_000L),
            )!!,
            0.001f,
        )
        assertEquals(
            100f,
            StatsParsers.cpuLoadPercent(
                ProcStatTicks(total = 1_000L, idle = 900L),
                ProcStatTicks(total = 1_100L, idle = 900L),
            )!!,
            0.001f,
        )
    }

    @Test
    fun `a stalled counter, a reset or a bad idle count is null`() {
        val base = ProcStatTicks(total = 1_000L, idle = 900L)

        assertNull(StatsParsers.cpuLoadPercent(base, base))
        assertNull(
            StatsParsers.cpuLoadPercent(
                base,
                ProcStatTicks(total = 900L, idle = 800L),
            ),
        )
        assertNull(
            StatsParsers.cpuLoadPercent(
                base,
                ProcStatTicks(total = 1_100L, idle = 1_200L),
            ),
        )
    }
}
