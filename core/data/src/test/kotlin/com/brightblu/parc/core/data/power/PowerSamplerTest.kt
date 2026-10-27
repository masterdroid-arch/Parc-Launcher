package com.brightblu.parc.core.data.power

import com.brightblu.parc.core.data.apps.epoch
import com.brightblu.parc.core.data.apps.nairobi
import com.brightblu.parc.core.data.stats.ChargingState
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The T51 sampling gate, ring buffer and per-source failure rules, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class PowerSamplerTest {

    private class FakeProbe : PowerProbe {
        var batteryCalls = 0
        var screenCalls = 0
        var resetCalls = 0

        var throwOnBattery = false
        var throwOnScreen = false

        var currentMicroAmps: Long? = 1_000_000L
        var voltageMillivolts: Int? = 4_000
        var chargingState: ChargingState? = ChargingState.Charging
        var chargeTimeRemainingMillis: Long? = 1_800_000L
        var health: BatteryHealth? = BatteryHealth.Good
        var cycleCount: Int? = 120
        var temperatureC: Float? = 33.5f
        var screenOnMillis: Long? = 3_600_000L

        val screenWindows = ArrayList<Pair<Long, Long>>()

        override suspend fun battery(): PowerBatterySample? {
            batteryCalls++
            if (throwOnBattery) throw IllegalStateException("battery")
            return PowerBatterySample(
                currentMicroAmps = currentMicroAmps,
                voltageMillivolts = voltageMillivolts,
                chargingState = chargingState,
                chargeTimeRemainingMillis = chargeTimeRemainingMillis,
                health = health,
                cycleCount = cycleCount,
                temperatureC = temperatureC,
            )
        }

        override suspend fun screenOn(windowStartMillis: Long, windowEndMillis: Long): Long? {
            screenCalls++
            screenWindows += windowStartMillis to windowEndMillis
            if (throwOnScreen) throw IllegalStateException("usage events")
            return screenOnMillis
        }

        override fun reset() {
            resetCalls++
        }
    }

    private val now = epoch(2026, 10, 17, 9, 0, nairobi)

    private fun TestScope.sampler(
        probe: PowerProbe,
        zone: ZoneId = nairobi,
    ): PowerSampler = PowerSampler(
        probe = probe,
        scope = backgroundScope,
        elapsedMillis = { testScheduler.currentTime },
        wallClock = { now },
        zone = { zone },
    )

    @Test
    fun `nothing is read before the sampler is activated`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        advanceTimeBy(6_000)

        assertEquals(0, probe.batteryCalls)
        assertEquals(0, probe.screenCalls)
        assertEquals(PowerStats.Empty, sampler.stats.value)
        assertFalse(sampler.isSampling)
    }

    @Test
    fun `one tick per five seconds until stopped`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        sampler.setActive(true)
        assertTrue(sampler.isSampling)
        advanceTimeBy(1)
        assertEquals(1, probe.batteryCalls)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, probe.batteryCalls)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, probe.batteryCalls)

        sampler.setActive(false)
        assertFalse(sampler.isSampling)
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(3, probe.batteryCalls)
        assertEquals(PowerStats.Empty, sampler.stats.value)
    }

    @Test
    fun `every available field lands in the snapshot`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)

        val stats = sampler.stats.value
        assertEquals(4f, stats.chargingPower!!.watts, 0.001f)
        assertEquals(PowerDirection.Charging, stats.chargingPower.direction)
        assertEquals(1_800_000L, stats.chargeTimeRemainingMillis)
        assertEquals(BatteryHealth.Good, stats.health)
        assertEquals(120, stats.cycleCount)
        assertEquals(listOf(33.5f), stats.temperatureHistoryC)
        assertEquals(3_600_000L, stats.screenOnTodayMillis)
    }

    @Test
    fun `the temperature ring buffer keeps the newest sixty samples`() = runTest {
        val probe = FakeProbe().apply { temperatureC = 1f }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        for (sample in 2..61) {
            probe.temperatureC = sample.toFloat()
            advanceTimeBy(5_000)
            runCurrent()
        }

        val history = sampler.stats.value.temperatureHistoryC
        assertEquals(PowerStatsSource.HistoryCapacity, history.size)
        assertEquals(2f, history.first(), 0.001f)
        assertEquals(61f, history.last(), 0.001f)
    }

    @Test
    fun `a missing temperature leaves the history unchanged`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        probe.temperatureC = null
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf(33.5f), sampler.stats.value.temperatureHistoryC)
        assertEquals(2, probe.batteryCalls)
    }

    @Test
    fun `screen-on time is read immediately and then at most every thirty seconds`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        assertEquals(1, probe.screenCalls)

        advanceTimeBy(24_000)
        runCurrent()
        assertEquals(1, probe.screenCalls)

        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(2, probe.screenCalls)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, probe.screenCalls)
    }

    @Test
    fun `screen-on is queried from local midnight to now`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)

        val (start, end) = probe.screenWindows.single()
        assertEquals(epoch(2026, 10, 17, 0, 0, nairobi), start)
        assertEquals(now, end)
    }

    @Test
    fun `a throwing battery read leaves the other fields alone`() = runTest {
        val probe = FakeProbe().apply { throwOnBattery = true }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)

        val stats = sampler.stats.value
        assertNull(stats.chargingPower)
        assertNull(stats.chargeTimeRemainingMillis)
        assertNull(stats.health)
        assertNull(stats.cycleCount)
        assertTrue(stats.temperatureHistoryC.isEmpty())
        assertEquals(3_600_000L, stats.screenOnTodayMillis)
    }

    @Test
    fun `a refused screen query is null and is retried at the next interval`() = runTest {
        val probe = FakeProbe().apply { throwOnScreen = true }
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        assertNull(sampler.stats.value.screenOnTodayMillis)

        probe.throwOnScreen = false
        advanceTimeBy(30_000)
        runCurrent()

        assertEquals(2, probe.screenCalls)
        assertEquals(3_600_000L, sampler.stats.value.screenOnTodayMillis)
    }

    @Test
    fun `stopping clears the history and resets the probe`() = runTest {
        val probe = FakeProbe()
        val sampler = sampler(probe)

        sampler.setActive(true)
        advanceTimeBy(1)
        sampler.setActive(false)

        assertEquals(1, probe.resetCalls)
        assertTrue(sampler.stats.value.temperatureHistoryC.isEmpty())
        assertEquals(PowerStats.Empty, sampler.stats.value)
    }
}
