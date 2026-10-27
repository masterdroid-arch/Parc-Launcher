package com.brightblu.parc.core.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for the bounded sparkline series; no Android runtime needed. */
class StatsHistoryTest {

    @Test
    fun `an empty history has no samples in any series`() {
        val history = StatsHistory()

        assertTrue(history.isEmpty)
        assertEquals(emptyList<Long>(), history.networkRxBytesPerSecond)
        assertEquals(emptyList<Long>(), history.networkTxBytesPerSecond)
        assertEquals(emptyList<Float>(), history.cpuTemperatureC)
    }

    @Test
    fun `network samples are appended in order and stay aligned`() {
        val history = StatsHistory()
            .recordNetwork(rxBytesPerSecond = 1_000L, txBytesPerSecond = 500L)
            .recordNetwork(rxBytesPerSecond = 2_000L, txBytesPerSecond = 700L)

        assertFalse(history.isEmpty)
        assertEquals(listOf(1_000L, 2_000L), history.networkRxBytesPerSecond)
        assertEquals(listOf(500L, 700L), history.networkTxBytesPerSecond)
        assertEquals(history.networkRxBytesPerSecond.size, history.networkTxBytesPerSecond.size)
    }

    @Test
    fun `a missing sample appends nothing rather than a zero`() {
        val history = StatsHistory()
            .recordNetwork(rxBytesPerSecond = 1_000L, txBytesPerSecond = 500L)
            .recordNetwork(rxBytesPerSecond = null, txBytesPerSecond = null)
            .recordCpuTemperature(null)

        assertEquals(listOf(1_000L), history.networkRxBytesPerSecond)
        assertEquals(listOf(500L), history.networkTxBytesPerSecond)
        assertEquals(emptyList<Float>(), history.cpuTemperatureC)
    }

    @Test
    fun `temperature samples are a series of their own`() {
        val history = StatsHistory()
            .recordCpuTemperature(41.2f)
            .recordNetwork(rxBytesPerSecond = 1_000L, txBytesPerSecond = 500L)
            .recordCpuTemperature(42.5f)

        assertEquals(listOf(41.2f, 42.5f), history.cpuTemperatureC)
        assertEquals(listOf(1_000L), history.networkRxBytesPerSecond)
    }

    @Test
    fun `each record returns a new value and leaves the old one untouched`() {
        val original = StatsHistory()
        val recorded = original.recordCpuTemperature(41.2f).recordNetwork(1L, 2L)

        assertTrue(original.isEmpty)
        assertEquals(emptyList<Float>(), original.cpuTemperatureC)
        assertEquals(listOf(41.2f), recorded.cpuTemperatureC)
        assertEquals(listOf(1L), recorded.networkRxBytesPerSecond)
    }

    @Test
    fun `a full series keeps the newest sixty samples and drops the oldest`() {
        val history = (1L..StatsHistory.Capacity + 10L).fold(StatsHistory()) { series, sample ->
            series.recordNetwork(rxBytesPerSecond = sample, txBytesPerSecond = sample * 10L)
        }

        assertEquals(60, StatsHistory.Capacity)
        assertEquals(StatsHistory.Capacity, history.networkRxBytesPerSecond.size)
        assertEquals(StatsHistory.Capacity, history.networkTxBytesPerSecond.size)
        // The first ten samples are gone; the newest sample is last.
        assertEquals(11L, history.networkRxBytesPerSecond.first())
        assertEquals(70L, history.networkRxBytesPerSecond.last())
        assertEquals(110L, history.networkTxBytesPerSecond.first())
        assertEquals(700L, history.networkTxBytesPerSecond.last())
    }

    @Test
    fun `a full temperature series is bounded the same way`() {
        val history = (1..StatsHistory.Capacity + 1).fold(StatsHistory()) { series, sample ->
            series.recordCpuTemperature(sample.toFloat())
        }

        assertEquals(StatsHistory.Capacity, history.cpuTemperatureC.size)
        assertEquals(2f, history.cpuTemperatureC.first(), 0.001f)
        assertEquals((StatsHistory.Capacity + 1).toFloat(), history.cpuTemperatureC.last(), 0.001f)
    }

    @Test
    fun `recording when nothing changed returns an equal value`() {
        val history = StatsHistory().recordCpuTemperature(41f)
        val same = history.recordNetwork(null, null).recordCpuTemperature(null)

        assertEquals(history, same)
        assertSame(history.cpuTemperatureC, same.cpuTemperatureC)
    }
}
