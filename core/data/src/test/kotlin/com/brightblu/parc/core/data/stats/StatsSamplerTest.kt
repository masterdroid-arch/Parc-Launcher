package com.brightblu.parc.core.data.stats

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sampling gate, the snapshot assembly and the source-failure rules, driven by a fake probe
 * and virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsSamplerTest {

    private class FakeProbe : StatsProbe {
        var batteryCalls = 0
        var thermalCalls = 0
        var memoryCalls = 0
        var storageCalls = 0
        var wifiCalls = 0
        var networkCalls = 0
        var cpuCalls = 0
        var zoneCalls = 0
        var dumpsysCalls = 0
        var procStatCalls = 0
        var cpuinfoCalls = 0
        var resetCalls = 0

        var dumpsysAllowed = false
        var throwOnBattery = false
        var throwOnThermal = false
        var throwOnMemory = false
        var throwOnStorage = false
        var throwOnWifi = false
        var throwOnNetwork = false
        var throwOnCpu = false
        var throwOnZones = false
        var throwOnDumpsys = false
        var throwOnProcStat = false
        var throwOnCpuinfo = false

        var batterySample: BatterySample? = BatterySample(
            percent = 62,
            chargingState = ChargingState.Charging,
            plug = PlugType.AC,
            temperatureC = 33.5f,
            currentMa = -850f,
        )
        var thermalSample: ThermalSample? = ThermalSample(ThermalStatus.Light, 0.42f)
        var memorySample: MemorySample? = MemorySample(usedPercent = 55)
        var storageSample: StorageSample? = StorageSample(
            freePercent = 42,
            usedBytes = 118_000_000_000L,
            totalBytes = 256_000_000_000L,
        )
        var wifiSample: WifiSample? = WifiSample(rssiDbm = -58, linkSpeedMbps = 433)
        var networkBytes: NetworkBytes? = NetworkBytes(rxBytes = 1_000, txBytes = 2_000)
        var cpuClusterList: List<CpuCluster>? = listOf(CpuCluster(listOf(0, 1), 1_800_000))
        var zoneReading: TemperatureReading? = null
        var dumpsysSample: DumpsysSample? = DumpsysSample(
            temperatures = TemperatureReading(41f, 39f, 33f),
        )

        /** Null by default, so the tests below exercise the dumpsys cpuinfo fallback. */
        var procStatReading: ProcStatTicks? = null

        /** Optional per-call readings; consumed before [procStatReading]. */
        val procStatSequence: ArrayDeque<ProcStatTicks> = ArrayDeque()
        var cpuinfoLoad: Float? = 60.2f

        override suspend fun battery(): BatterySample? {
            batteryCalls++
            if (throwOnBattery) throw IllegalStateException("battery")
            return batterySample
        }

        override suspend fun thermal(): ThermalSample? {
            thermalCalls++
            if (throwOnThermal) throw IllegalStateException("thermal")
            return thermalSample
        }

        override suspend fun memory(): MemorySample? {
            memoryCalls++
            if (throwOnMemory) throw IllegalStateException("memory")
            return memorySample
        }

        override suspend fun storage(): StorageSample? {
            storageCalls++
            if (throwOnStorage) throw IllegalStateException("storage")
            return storageSample
        }

        override suspend fun wifi(): WifiSample? {
            wifiCalls++
            if (throwOnWifi) throw IllegalStateException("wifi")
            return wifiSample
        }

        override suspend fun network(): NetworkBytes? {
            networkCalls++
            if (throwOnNetwork) throw IllegalStateException("network")
            return networkBytes
        }

        override suspend fun cpuClusters(): List<CpuCluster>? {
            cpuCalls++
            if (throwOnCpu) throw IllegalStateException("cpu")
            return cpuClusterList
        }

        override suspend fun thermalZones(): TemperatureReading? {
            zoneCalls++
            if (throwOnZones) throw IllegalStateException("zones")
            return zoneReading
        }

        override fun dumpsysGranted(): Boolean = dumpsysAllowed

        override suspend fun dumpsys(): DumpsysSample? {
            dumpsysCalls++
            if (throwOnDumpsys) throw IllegalStateException("dumpsys")
            return dumpsysSample
        }

        override suspend fun procStatTicks(): ProcStatTicks? {
            procStatCalls++
            if (throwOnProcStat) throw IllegalStateException("procstat")
            return procStatSequence.removeFirstOrNull() ?: procStatReading
        }

        override suspend fun dumpsysCpuLoad(): Float? {
            cpuinfoCalls++
            if (throwOnCpuinfo) throw IllegalStateException("cpuinfo")
            return cpuinfoLoad
        }

        override fun reset() {
            resetCalls++
            dumpsysSample = null
        }

        val totalCalls: Int
            get() = batteryCalls + thermalCalls + memoryCalls + storageCalls + wifiCalls +
                networkCalls + cpuCalls + zoneCalls + dumpsysCalls + procStatCalls + cpuinfoCalls
    }

    /**
     * Virtual-time sampler: one tick is one simulated second. The tick job lives in
     * [backgroundScope], so a test that leaves sampling on still lets `runTest` finish.
     */
    private fun TestScope.sampler(
        probe: StatsProbe,
        thermalMemory: ThermalMemory = NoThermalMemory,
    ): StatsSampler =
        StatsSampler(
            probe = probe,
            scope = backgroundScope,
            elapsedMillis = { testScheduler.currentTime },
            thermalMemory = thermalMemory,
        )

    /** In-memory stand-in for the DataStore-backed T58 cross-start store. */
    private class FakeThermalMemory(
        var stored: Map<TemperatureSensor, Float> = emptyMap(),
    ) : ThermalMemory {
        var loads = 0
        var saves = 0
        var saved: Map<TemperatureSensor, Float>? = null

        override suspend fun load(): Map<TemperatureSensor, Float> {
            loads++
            return stored
        }

        override suspend fun save(values: Map<TemperatureSensor, Float>) {
            saves++
            saved = values
            stored = values
        }
    }

    @Test
    fun `nothing is read before the sampler is activated`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        advanceTimeBy(5_001)

        assertEquals(0, probe.totalCalls)
        assertEquals(DeviceStats.Empty, sampler.stats.value)
        assertFalse(sampler.isSampling)
    }

    @Test
    fun `one tick per second while active and nothing at all after stopping`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        sampler.setActive(true)
        assertTrue(sampler.isSampling)
        advanceTimeBy(1)
        assertEquals(1, probe.batteryCalls)
        advanceTimeBy(3_003)
        assertEquals(4, probe.batteryCalls)
        assertEquals(4, probe.thermalCalls)

        sampler.setActive(false)
        assertFalse(sampler.isSampling)
        advanceTimeBy(5_001)

        assertEquals(4, probe.batteryCalls)
        assertEquals(DeviceStats.Empty, sampler.stats.value)
    }

    @Test
    fun `every available field lands in the snapshot`() = runTest {
        val probe = FakeProbe().apply { dumpsysAllowed = true }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)

        val stats = sampler.stats.value
        assertEquals(62, stats.batteryPercent)
        assertEquals(ChargingState.Charging, stats.chargingState)
        assertEquals(PlugType.AC, stats.plug)
        assertEquals(33.5f, stats.batteryTemperatureC!!, 0.001f)
        assertEquals(-850f, stats.batteryCurrentMa!!, 0.001f)
        assertEquals(ThermalStatus.Light, stats.thermalStatus)
        assertEquals(0.42f, stats.thermalHeadroom!!, 0.001f)
        assertEquals(55, stats.ramUsedPercent)
        assertEquals(42, stats.storageFreePercent)
        assertEquals(StorageUsage(118_000_000_000L, 256_000_000_000L), stats.storage)
        assertEquals(46, stats.storage!!.usedPercent)
        assertEquals(emptySet<TemperatureSensor>(), stats.staleTemperatureSensors)
        assertEquals(listOf(CpuCluster(listOf(0, 1), 1_800_000)), stats.cpuClusters)
        assertEquals(41f, stats.cpuTemperatureC!!, 0.001f)
        assertEquals(39f, stats.gpuTemperatureC!!, 0.001f)
        assertEquals(33f, stats.skinTemperatureC!!, 0.001f)
        assertEquals(60.2f, stats.cpuLoadPercent!!, 0.001f)
        assertEquals(-58, stats.wifiRssiDbm)
        assertEquals(433, stats.wifiLinkSpeedMbps)
        // The sampler's monotonic clock is the uptime, so the first tick of a fresh run reads 0.
        assertEquals(0L, stats.uptimeMillis)
        assertNull(stats.networkRxBytesPerSecond)
        assertNull(stats.networkTxBytesPerSecond)
        assertFalse(stats.isAllUnavailable)
    }

    @Test
    fun `the HAL temperature set reaches the snapshot`() = runTest {
        val probe = FakeProbe().apply {
            dumpsysAllowed = true
            dumpsysSample = DumpsysSample(
                temperatures = TemperatureReading(
                    cpuTemperatureC = 49.888f,
                    gpuTemperatureC = 47.5f,
                    skinTemperatureC = 38.2f,
                    npuTemperatureC = 41.25f,
                    socTemperatureC = 44.1f,
                    batteryTemperatureC = 31f,
                ),
            )
        }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)

        val stats = sampler.stats.value
        assertEquals(49.888f, stats.cpuTemperatureC!!, 0.001f)
        assertEquals(47.5f, stats.gpuTemperatureC!!, 0.001f)
        assertEquals(38.2f, stats.skinTemperatureC!!, 0.001f)
        assertEquals(41.25f, stats.npuTemperatureC!!, 0.001f)
        assertEquals(44.1f, stats.socTemperatureC!!, 0.001f)
        // The battery broadcast wins over the thermalservice sensor when it carries a value.
        assertEquals(33.5f, stats.batteryTemperatureC!!, 0.001f)
    }

    @Test
    fun `battery temperature falls back to the thermalservice sensor`() = runTest {
        val stats = oneTick {
            batterySample = BatterySample(percent = 62)
            dumpsysSample = DumpsysSample(
                temperatures = TemperatureReading(batteryTemperatureC = 31f),
            )
        }

        assertEquals(31f, stats.batteryTemperatureC!!, 0.001f)
        assertEquals(62, stats.batteryPercent)
    }

    @Test
    fun `uptime follows the monotonic clock and is cleared when sampling stops`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1_001)

        assertEquals(1_000L, sampler.stats.value.uptimeMillis)

        sampler.setActive(false)
        assertNull(sampler.stats.value.uptimeMillis)
    }

    @Test
    fun `the history follows the rates and the CPU temperature and clears on stop`() = runTest {
        val probe = FakeProbe().apply { dumpsysAllowed = true }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        // The first tick has no previous counter reading, so the network series is still empty; the
        // first dumpsys round has already supplied a CPU temperature.
        assertTrue(sampler.history.value.networkRxBytesPerSecond.isEmpty())
        assertTrue(sampler.history.value.networkTxBytesPerSecond.isEmpty())
        assertEquals(listOf(41f), sampler.history.value.cpuTemperatureC)

        probe.networkBytes = NetworkBytes(rxBytes = 3_000, txBytes = 3_000)
        advanceTimeBy(1_001)
        assertEquals(listOf(2_000L), sampler.history.value.networkRxBytesPerSecond)
        assertEquals(listOf(1_000L), sampler.history.value.networkTxBytesPerSecond)
        // The dumpsys temperature is throttled to one round per five seconds, so the cached
        // reading repeats here: it is what the module knows, not an invented sample.
        assertEquals(listOf(41f, 41f), sampler.history.value.cpuTemperatureC)

        probe.networkBytes = NetworkBytes(rxBytes = 4_000, txBytes = 3_500)
        advanceTimeBy(1_001)
        assertEquals(listOf(2_000L, 1_000L), sampler.history.value.networkRxBytesPerSecond)
        assertEquals(listOf(1_000L, 500L), sampler.history.value.networkTxBytesPerSecond)

        sampler.setActive(false)
        assertTrue(sampler.history.value.isEmpty)
    }

    @Test
    fun `the history never exceeds the bounded capacity`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        repeat(StatsHistory.Capacity + 5) { index ->
            probe.networkBytes = NetworkBytes(rxBytes = 1_000L * index, txBytes = 1L)
            advanceTimeBy(1_001)
        }

        val history = sampler.history.value
        assertEquals(StatsHistory.Capacity, history.networkRxBytesPerSecond.size)
        assertEquals(history.networkRxBytesPerSecond.size, history.networkTxBytesPerSecond.size)
    }

    @Test
    fun `a failing source nulls only its own fields`() = runTest {
        val probe = FakeProbe().apply { throwOnBattery = true }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)

        val stats = sampler.stats.value
        assertNull(stats.batteryPercent)
        assertNull(stats.chargingState)
        assertNull(stats.plug)
        assertNull(stats.batteryTemperatureC)
        assertNull(stats.batteryCurrentMa)
        assertEquals(55, stats.ramUsedPercent)
        assertEquals(ThermalStatus.Light, stats.thermalStatus)
    }

    @Test
    fun `a throwing source for every read gives the all-unavailable snapshot`() = runTest {
        val probe = FakeProbe().apply {
            throwOnBattery = true
            throwOnThermal = true
            throwOnMemory = true
            throwOnStorage = true
            throwOnWifi = true
            throwOnNetwork = true
            throwOnCpu = true
            throwOnDumpsys = true
            throwOnProcStat = true
            throwOnCpuinfo = true
            dumpsysAllowed = true
        }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(4_004)

        assertTrue(sampler.stats.value.isAllUnavailable)
        assertEquals(null, sampler.stats.value.batteryPercent)
        assertTrue(sampler.isSampling)
    }

    @Test
    fun `a source that answers null or empty is unavailable, not zero`() = runTest {
        val probe = FakeProbe().apply {
            batterySample = null
            thermalSample = null
            memorySample = null
            storageSample = null
            wifiSample = null
            networkBytes = null
            cpuClusterList = emptyList()
            dumpsysSample = DumpsysSample()
            procStatReading = null
            cpuinfoLoad = null
            dumpsysAllowed = true
        }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)

        assertTrue(sampler.stats.value.isAllUnavailable)
        // Uptime is a session fact, not a reading, so it is present even in this snapshot.
        assertEquals(0L, sampler.stats.value.uptimeMillis)
    }

    @Test
    fun `network rates need two readings and reset when the counter disappears`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)
        sampler.setActive(true)

        advanceTimeBy(1)
        assertNull(sampler.stats.value.networkRxBytesPerSecond)

        probe.networkBytes = NetworkBytes(rxBytes = 3_000, txBytes = 3_000)
        advanceTimeBy(1_001)
        assertEquals(2_000L, sampler.stats.value.networkRxBytesPerSecond)
        assertEquals(1_000L, sampler.stats.value.networkTxBytesPerSecond)

        probe.networkBytes = null
        advanceTimeBy(1_001)
        assertNull(sampler.stats.value.networkRxBytesPerSecond)

        probe.networkBytes = NetworkBytes(rxBytes = 9_000, txBytes = 9_000)
        advanceTimeBy(1_001)
        assertNull(sampler.stats.value.networkRxBytesPerSecond)

        probe.networkBytes = NetworkBytes(rxBytes = 10_000, txBytes = 9_500)
        advanceTimeBy(1_001)
        assertEquals(1_000L, sampler.stats.value.networkRxBytesPerSecond)
    }

    @Test
    fun `a restart after stopping starts a fresh network baseline`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        probe.networkBytes = NetworkBytes(rxBytes = 5_000, txBytes = 5_000)
        advanceTimeBy(1_001)
        assertEquals(4_000L, sampler.stats.value.networkRxBytesPerSecond)

        sampler.setActive(false)
        sampler.setActive(true)
        advanceTimeBy(1)

        assertNull(sampler.stats.value.networkRxBytesPerSecond)
    }

    @Test
    fun `dumpsys is never spawned without the permission`() = runTest {
        val probe = FakeProbe().apply { dumpsysAllowed = false }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(9_009)

        assertEquals(0, probe.dumpsysCalls)
        assertEquals(0, probe.cpuinfoCalls)
        assertNull(sampler.stats.value.cpuTemperatureC)
        assertNull(sampler.stats.value.cpuLoadPercent)
    }

    @Test
    fun `the temperature round is spawned at most once per five seconds`() = runTest {
        val probe = FakeProbe().apply { dumpsysAllowed = true }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        assertEquals(1, probe.dumpsysCalls)

        advanceTimeBy(5_005)
        assertEquals(2, probe.dumpsysCalls)

        advanceTimeBy(5_001)
        assertEquals(3, probe.dumpsysCalls)
    }

    @Test
    fun `dumpsys values stay visible between throttled rounds`() = runTest {
        val probe = FakeProbe().apply { dumpsysAllowed = true }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)

        advanceTimeBy(2_002)
        assertEquals(1, probe.dumpsysCalls)
        assertEquals(41f, sampler.stats.value.cpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `a failing thermalservice clears the group but leaves load and the rest`() = runTest {
        val probe = FakeProbe().apply { dumpsysAllowed = true }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        assertEquals(41f, sampler.stats.value.cpuTemperatureC!!, 0.001f)

        probe.throwOnDumpsys = true
        advanceTimeBy(5_001)

        assertNull(sampler.stats.value.cpuTemperatureC)
        assertNull(sampler.stats.value.gpuTemperatureC)
        assertNull(sampler.stats.value.skinTemperatureC)
        // The load comes from the separate dumpsys cpuinfo fallback, so it survives.
        assertEquals(60.2f, sampler.stats.value.cpuLoadPercent!!, 0.001f)
        assertEquals(62, sampler.stats.value.batteryPercent)
    }

    @Test
    fun `a failing cpuinfo fallback hides the load`() = runTest {
        val probe = FakeProbe().apply {
            dumpsysAllowed = true
            throwOnCpuinfo = true
        }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)

        assertNull(sampler.stats.value.cpuLoadPercent)
    }

    @Test
    fun `proc stat deltas are preferred and no cpuinfo is spawned`() = runTest {
        val probe = FakeProbe().apply {
            dumpsysAllowed = true
            procStatSequence += ProcStatTicks(total = 1_000L, idle = 800L)
            procStatSequence += ProcStatTicks(total = 1_100L, idle = 850L)
        }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        // The first reading only establishes a baseline.
        assertNull(sampler.stats.value.cpuLoadPercent)
        assertEquals(0, probe.cpuinfoCalls)

        advanceTimeBy(5_001)
        // 100 ticks passed, 50 of them busy: 50 %.
        assertEquals(50f, sampler.stats.value.cpuLoadPercent!!, 0.001f)
        assertEquals(0, probe.cpuinfoCalls)
    }

    @Test
    fun `an unreadable proc stat falls back to the DUMP cpuinfo`() = runTest {
        val probe = FakeProbe().apply {
            dumpsysAllowed = true
            procStatReading = null
            cpuinfoLoad = 37f
        }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)

        assertEquals(37f, sampler.stats.value.cpuLoadPercent!!, 0.001f)
        assertEquals(1, probe.cpuinfoCalls)
    }

    @Test
    fun `no proc stat and no DUMP means no load and no cpuinfo spawn`() = runTest {
        val probe = FakeProbe().apply {
            dumpsysAllowed = false
            procStatReading = null
        }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)

        assertNull(sampler.stats.value.cpuLoadPercent)
        assertEquals(0, probe.cpuinfoCalls)
    }

    @Test
    fun `the load is carried between five second rounds`() = runTest {
        val probe = FakeProbe().apply {
            dumpsysAllowed = true
            procStatSequence += ProcStatTicks(total = 1_000L, idle = 800L)
            procStatSequence += ProcStatTicks(total = 1_100L, idle = 850L)
        }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        assertNull(sampler.stats.value.cpuLoadPercent)

        advanceTimeBy(5_001)
        assertEquals(50f, sampler.stats.value.cpuLoadPercent!!, 0.001f)

        // No new proc stat reading this second; the last computed value stays on screen.
        advanceTimeBy(1_001)
        assertEquals(50f, sampler.stats.value.cpuLoadPercent!!, 0.001f)
    }

    @Test
    fun `the previous start's HAL values hide the same first sample`() = runTest {
        val text = checkNotNull(javaClass.getResourceAsStream("/device-thermalservice.txt"))
            .bufferedReader()
            .use { it.readText() }
        val hal = DumpsysSample(
            temperatures = checkNotNull(StatsParsers.parseThermalservice(text)),
        )
        val memory = FakeThermalMemory(
            stored = mapOf(
                TemperatureSensor.Cpu to 49.888f,
                TemperatureSensor.Gpu to 49.628f,
                TemperatureSensor.Skin to 45.125f,
            ),
        )
        val probe = FakeProbe().apply {
            dumpsysAllowed = true
            dumpsysSample = hal
        }
        val sampler = sampler(probe, memory)

        sampler.setActive(true)
        advanceTimeBy(1)

        val stats = sampler.stats.value
        assertNull(stats.cpuTemperatureC)
        assertNull(stats.gpuTemperatureC)
        assertNull(stats.skinTemperatureC)
        assertEquals(1, memory.loads)
    }

    @Test
    fun `stopping stores the last HAL values for the next start`() = runTest {
        val memory = FakeThermalMemory()
        val probe = FakeProbe().apply {
            dumpsysAllowed = true
            dumpsysSample = DumpsysSample(
                temperatures = TemperatureReading(
                    cpuTemperatureC = 49.888f,
                    gpuTemperatureC = 49.628f,
                    skinTemperatureC = 45.125f,
                    batteryTemperatureC = 31f,
                ),
            )
        }
        val sampler = sampler(probe, memory)

        sampler.setActive(true)
        advanceTimeBy(1)
        assertEquals(1, memory.loads)
        assertEquals(49.888f, sampler.stats.value.cpuTemperatureC!!, 0.001f)
        sampler.setActive(false)
        // advanceUntilIdle leaves backgroundScope work pending, so advance once more to run the
        // save coroutine launched by stop().
        advanceUntilIdle()
        advanceTimeBy(10)

        assertEquals(1, memory.saves)
        val saved = checkNotNull(memory.saved)
        assertEquals(49.888f, saved[TemperatureSensor.Cpu]!!, 0.001f)
        assertEquals(49.628f, saved[TemperatureSensor.Gpu]!!, 0.001f)
        assertEquals(45.125f, saved[TemperatureSensor.Skin]!!, 0.001f)
        assertFalse(TemperatureSensor.Battery in saved)
    }

    @Test
    fun `stopping without a HAL read stores nothing`() = runTest {
        val memory = FakeThermalMemory()
        val probe = FakeProbe().apply { dumpsysAllowed = false }
        val sampler = sampler(probe, memory)

        sampler.setActive(true)
        advanceTimeBy(1)
        sampler.setActive(false)
        advanceUntilIdle()

        assertEquals(0, memory.saves)
    }

    /** Activates the sampler for exactly one tick with the given fake settings. */
    private fun TestScope.oneTick(configure: FakeProbe.() -> Unit): DeviceStats {
        val probe = FakeProbe().apply { dumpsysAllowed = true }.apply(configure)
        val sampler = sampler(probe)
        sampler.setActive(true)
        advanceTimeBy(1)
        val value = sampler.stats.value
        sampler.setActive(false)
        return value
    }

    @Test
    fun `a throwing thermal source nulls only the thermal fields`() = runTest {
        val stats = oneTick { throwOnThermal = true }

        assertNull(stats.thermalStatus)
        assertNull(stats.thermalHeadroom)
        assertEquals(62, stats.batteryPercent)
        assertEquals(55, stats.ramUsedPercent)
        assertEquals(listOf(CpuCluster(listOf(0, 1), 1_800_000)), stats.cpuClusters)
    }

    @Test
    fun `a throwing memory source nulls only the RAM field`() = runTest {
        val stats = oneTick { throwOnMemory = true }

        assertNull(stats.ramUsedPercent)
        assertEquals(62, stats.batteryPercent)
        assertEquals(ThermalStatus.Light, stats.thermalStatus)
    }

    @Test
    fun `a throwing cpu source nulls only the cluster list`() = runTest {
        val stats = oneTick { throwOnCpu = true }

        assertNull(stats.cpuClusters)
        assertEquals(62, stats.batteryPercent)
        assertEquals(41f, stats.cpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `a throwing storage source nulls only the storage field`() = runTest {
        val stats = oneTick { throwOnStorage = true }

        assertNull(stats.storageFreePercent)
        assertEquals(-58, stats.wifiRssiDbm)
        assertEquals(62, stats.batteryPercent)
        assertEquals(55, stats.ramUsedPercent)
    }

    @Test
    fun `a throwing wifi source nulls only the Wi-Fi fields`() = runTest {
        val stats = oneTick { throwOnWifi = true }

        assertNull(stats.wifiRssiDbm)
        assertNull(stats.wifiLinkSpeedMbps)
        assertEquals(42, stats.storageFreePercent)
        assertEquals(62, stats.batteryPercent)
    }

    @Test
    fun `storage is sampled at most once per thirty seconds`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        assertEquals(1, probe.storageCalls)
        assertEquals(42, sampler.stats.value.storageFreePercent)

        probe.storageSample = StorageSample(
            freePercent = 40,
            usedBytes = 120_000_000_000L,
            totalBytes = 256_000_000_000L,
        )
        advanceTimeBy(29_000)
        assertEquals(1, probe.storageCalls)
        assertEquals(42, sampler.stats.value.storageFreePercent)

        advanceTimeBy(1_000)
        assertEquals(2, probe.storageCalls)
        assertEquals(40, sampler.stats.value.storageFreePercent)

        advanceTimeBy(29_000)
        assertEquals(2, probe.storageCalls)
        advanceTimeBy(1_000)
        assertEquals(3, probe.storageCalls)
    }

    @Test
    fun `revoking DUMP clears HAL temperatures instead of leaving them frozen`() = runTest {
        val probe = FakeProbe().apply { dumpsysAllowed = true }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        assertEquals(41f, sampler.stats.value.cpuTemperatureC!!, 0.001f)

        probe.dumpsysAllowed = false
        advanceTimeBy(5_001)

        assertNull(sampler.stats.value.cpuTemperatureC)
        assertNull(sampler.stats.value.gpuTemperatureC)
        assertNull(sampler.stats.value.skinTemperatureC)
    }

    @Test
    fun `a restart re-reads storage immediately`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        assertEquals(1, probe.storageCalls)

        sampler.setActive(false)
        sampler.setActive(true)
        advanceTimeBy(1)
        assertEquals(2, probe.storageCalls)
    }

    @Test
    fun `a nullable storage size hides only the byte fields, not the free percentage`() = runTest {
        val stats = oneTick {
            storageSample = StorageSample(freePercent = 42)
        }

        assertEquals(42, stats.storageFreePercent)
        assertNull(stats.storage)
    }

    @Test
    fun `sysfs thermal zones are re-read every temperature round and win per sensor`() = runTest {
        val probe = FakeProbe().apply {
            dumpsysAllowed = true
            zoneReading = TemperatureReading(cpuTemperatureC = 50f, skinTemperatureC = 44f)
        }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        assertEquals(1, probe.zoneCalls)
        assertEquals(50f, sampler.stats.value.cpuTemperatureC!!, 0.001f)
        // The HAL value fills only the sensor the kernel did not report.
        assertEquals(39f, sampler.stats.value.gpuTemperatureC!!, 0.001f)
        assertEquals(44f, sampler.stats.value.skinTemperatureC!!, 0.001f)

        // Zones are on the 5 s temperature cadence, not the 1 s tick.
        advanceTimeBy(2_002)
        assertEquals(1, probe.zoneCalls)
        advanceTimeBy(3_003)
        assertEquals(2, probe.zoneCalls)
    }

    @Test
    fun `a denied kernel zone source falls back to the HAL`() = runTest {
        val stats = oneTick {
            throwOnZones = true
            dumpsysSample = DumpsysSample(
                temperatures = TemperatureReading(cpuTemperatureC = 49.888f),
            )
        }

        assertEquals(49.888f, stats.cpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `three identical five second HAL samples hide CPU and GPU even with a steady battery`() = runTest {
        val probe = FakeProbe().apply {
            dumpsysAllowed = true
            dumpsysSample = DumpsysSample(
                temperatures = TemperatureReading(cpuTemperatureC = 49.888f, gpuTemperatureC = 49.6f),
            )
        }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        assertEquals(49.888f, sampler.stats.value.cpuTemperatureC!!, 0.001f)
        assertEquals(emptySet<TemperatureSensor>(), sampler.stats.value.staleTemperatureSensors)

        // Two more 5 s samples: 10 s of evidence under the T58 rule. The battery is steady, which
        // no longer prevents the frozen verdict.
        advanceTimeBy(10_001)

        assertNull(sampler.stats.value.cpuTemperatureC)
        assertNull(sampler.stats.value.gpuTemperatureC)
        assertEquals(
            setOf(TemperatureSensor.Cpu, TemperatureSensor.Gpu),
            sampler.stats.value.staleTemperatureSensors,
        )
        // The battery broadcast is the live value and is never suppressed.
        assertEquals(33.5f, sampler.stats.value.batteryTemperatureC!!, 0.001f)
    }

    @Test
    fun `a frozen replay hides CPU GPU and skin through the real sampler`() = runTest {
        val text = checkNotNull(javaClass.getResourceAsStream("/device-thermalservice.txt"))
            .bufferedReader()
            .use { it.readText() }
        val hal = DumpsysSample(
            temperatures = checkNotNull(StatsParsers.parseThermalservice(text)),
        )
        val probe = FakeProbe().apply {
            dumpsysAllowed = true
            dumpsysSample = hal
        }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(10_001)

        val stats = sampler.stats.value
        assertNull(stats.cpuTemperatureC)
        assertNull(stats.gpuTemperatureC)
        assertNull(stats.skinTemperatureC)
        assertTrue(
            stats.staleTemperatureSensors.containsAll(
                setOf(TemperatureSensor.Cpu, TemperatureSensor.Gpu, TemperatureSensor.Skin),
            ),
        )
        assertEquals(33.5f, stats.batteryTemperatureC!!, 0.001f)
    }

    @Test
    fun `a changed reading clears the stale mark`() = runTest {
        val probe = FakeProbe().apply {
            dumpsysAllowed = true
            dumpsysSample = DumpsysSample(
                temperatures = TemperatureReading(cpuTemperatureC = 49.888f),
            )
        }
        val sampler = sampler(probe)

        sampler.setActive(true)
        repeat(20) { index ->
            probe.batterySample = probe.batterySample!!.copy(temperatureC = 30f + index / 10f)
            advanceTimeBy(1_001)
        }
        assertEquals(setOf(TemperatureSensor.Cpu), sampler.stats.value.staleTemperatureSensors)

        probe.dumpsysSample = DumpsysSample(
            temperatures = TemperatureReading(cpuTemperatureC = 52.5f),
        )
        advanceTimeBy(5_001)

        assertEquals(52.5f, sampler.stats.value.cpuTemperatureC!!, 0.001f)
        assertEquals(emptySet<TemperatureSensor>(), sampler.stats.value.staleTemperatureSensors)
    }

    @Test
    fun `a stale temperature is never recorded into the history`() = runTest {
        val probe = FakeProbe().apply {
            dumpsysAllowed = true
            dumpsysSample = DumpsysSample(
                temperatures = TemperatureReading(cpuTemperatureC = 49.888f),
            )
        }
        val sampler = sampler(probe)

        sampler.setActive(true)
        repeat(20) { index ->
            probe.batterySample = probe.batterySample!!.copy(temperatureC = 30f + index / 10f)
            advanceTimeBy(1_001)
        }
        assertEquals(setOf(TemperatureSensor.Cpu), sampler.stats.value.staleTemperatureSensors)
        val recorded = sampler.history.value.cpuTemperatureC.size

        advanceTimeBy(5_001)

        assertEquals(recorded, sampler.history.value.cpuTemperatureC.size)
    }

    @Test
    fun `stopping clears the probe cache once per active session`() = runTest {
        val probe = FakeProbe().apply { dumpsysAllowed = true }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        assertEquals(0, probe.resetCalls)

        sampler.setActive(false)
        assertEquals(1, probe.resetCalls)
        sampler.setActive(false)
        assertEquals(1, probe.resetCalls)

        sampler.setActive(true)
        advanceTimeBy(1)
        sampler.setActive(false)
        assertEquals(2, probe.resetCalls)
    }

    @Test
    fun `a stale dumpsys value is never published after a restart`() = runTest {
        val probe = FakeProbe().apply { dumpsysAllowed = true }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        assertEquals(41f, sampler.stats.value.cpuTemperatureC!!, 0.001f)

        // The module is hidden, then shown again. The probe has nothing usable to report this time,
        // so the snapshot must be null rather than the value captured before it was hidden.
        sampler.setActive(false)
        probe.cpuinfoLoad = null
        sampler.setActive(true)
        advanceTimeBy(1)

        assertNull(sampler.stats.value.cpuTemperatureC)
        assertNull(sampler.stats.value.cpuLoadPercent)
        assertEquals(62, sampler.stats.value.batteryPercent)
    }
}
