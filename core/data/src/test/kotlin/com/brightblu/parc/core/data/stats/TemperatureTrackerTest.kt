package com.brightblu.parc.core.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The T58 temperature source choice and frozen rules, with no Android types and a fake source
 * sequence. One `update` call is one 5 s temperature sample.
 */
class TemperatureTrackerTest {

    private val frozenCpu = TemperatureReading(cpuTemperatureC = 49.888f, gpuTemperatureC = 49.628f)

    @Test
    fun `three identical samples mark the sensors stale`() {
        val tracker = TemperatureTracker()

        repeat(2) { index ->
            val tracked = tracker.update(zones = null, hal = frozenCpu, halResolved = true)
            assertFalse("sample ${index + 1} must not be stale yet", TemperatureSensor.Cpu in tracked.stale)
        }

        val tracked = tracker.update(zones = null, hal = frozenCpu, halResolved = true)

        assertEquals(setOf(TemperatureSensor.Cpu, TemperatureSensor.Gpu), tracked.stale)
        assertNull(tracked.readings.cpuTemperatureC)
        assertNull(tracked.readings.gpuTemperatureC)
    }

    @Test
    fun `two identical samples are shown`() {
        val tracker = TemperatureTracker()

        tracker.update(null, frozenCpu, true)
        val tracked = tracker.update(null, frozenCpu, true)

        assertEquals(49.888f, tracked.readings.cpuTemperatureC!!, 0.001f)
        assertTrue(tracked.stale.isEmpty())
    }

    @Test
    fun `a changing sensor is never hidden`() {
        val tracker = TemperatureTracker()

        val tracked = (0 until 10).fold(tracker.update(null, frozenCpu, true)) { _, index ->
            tracker.update(
                zones = null,
                hal = TemperatureReading(
                    cpuTemperatureC = 49.888f + index,
                    gpuTemperatureC = 49.628f,
                ),
                halResolved = true,
            )
        }

        assertFalse(TemperatureSensor.Cpu in tracked.stale)
        assertEquals(58.888f, tracked.readings.cpuTemperatureC!!, 0.001f)
        // The GPU did not move, so it is hidden at the same time.
        assertTrue(TemperatureSensor.Gpu in tracked.stale)
        assertNull(tracked.readings.gpuTemperatureC)
    }

    @Test
    fun `a changed reading clears the stale mark immediately`() {
        val tracker = TemperatureTracker()
        repeat(3) { tracker.update(null, frozenCpu, true) }
        assertTrue(TemperatureSensor.Cpu in tracker.update(null, frozenCpu, true).stale)

        val tracked = tracker.update(
            zones = null,
            hal = TemperatureReading(cpuTemperatureC = 52.5f, gpuTemperatureC = 50f),
            halResolved = true,
        )

        assertEquals(52.5f, tracked.readings.cpuTemperatureC!!, 0.001f)
        assertEquals(emptySet<TemperatureSensor>(), tracked.stale)
    }

    @Test
    fun `a carried HAL value is published but does not advance the counter`() {
        val tracker = TemperatureTracker()

        // One real HAL sample, then nine carried ticks. If carried ticks counted, the reading
        // would be stale before the second real sample.
        var tracked = tracker.update(null, frozenCpu, halResolved = true)
        repeat(9) {
            tracked = tracker.update(null, frozenCpu, halResolved = false)
        }

        assertEquals(49.888f, tracked.readings.cpuTemperatureC!!, 0.001f)
        assertTrue(tracked.stale.isEmpty())
    }

    @Test
    fun `a value equal to the previous start is hidden at once`() {
        val tracker = TemperatureTracker()
        tracker.beginSession(
            mapOf(TemperatureSensor.Cpu to 49.888f, TemperatureSensor.Gpu to 49.628f),
        )

        val tracked = tracker.update(null, frozenCpu, halResolved = true)

        assertEquals(setOf(TemperatureSensor.Cpu, TemperatureSensor.Gpu), tracked.stale)
        assertNull(tracked.readings.cpuTemperatureC)
        assertNull(tracked.readings.gpuTemperatureC)
    }

    @Test
    fun `the cross-start baseline is consumed by the first observation`() {
        val tracker = TemperatureTracker()
        tracker.beginSession(mapOf(TemperatureSensor.Cpu to 49.888f))

        val first = tracker.update(
            zones = null,
            hal = TemperatureReading(cpuTemperatureC = 40f),
            halResolved = true,
        )
        assertTrue(first.stale.isEmpty())
        assertEquals(40f, first.readings.cpuTemperatureC!!, 0.001f)

        // Returning to the old start value later in the session is not evidence of a frozen HAL.
        val later = (0 until 2).fold(first) { _, _ ->
            tracker.update(null, TemperatureReading(cpuTemperatureC = 49.888f), true)
        }
        assertTrue(later.stale.isEmpty())
        assertEquals(49.888f, later.readings.cpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `a different first value after a restart is shown`() {
        val tracker = TemperatureTracker()
        tracker.beginSession(mapOf(TemperatureSensor.Cpu to 49.888f))

        val tracked = tracker.update(
            zones = null,
            hal = TemperatureReading(cpuTemperatureC = 47.2f),
            halResolved = true,
        )

        assertTrue(tracked.stale.isEmpty())
        assertEquals(47.2f, tracked.readings.cpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `a cross-start stale sensor recovers when it changes`() {
        val tracker = TemperatureTracker()
        tracker.beginSession(mapOf(TemperatureSensor.Cpu to 49.888f))
        assertTrue(TemperatureSensor.Cpu in tracker.update(null, frozenCpu, true).stale)

        val tracked = tracker.update(
            zones = null,
            hal = TemperatureReading(cpuTemperatureC = 50.25f),
            halResolved = true,
        )

        assertTrue(tracked.stale.isEmpty())
        assertEquals(50.25f, tracked.readings.cpuTemperatureC!!, 0.001f)
    }

    @Test
    fun `the last HAL values exclude the battery and reset clears them`() {
        val tracker = TemperatureTracker()
        tracker.update(
            null,
            TemperatureReading(
                cpuTemperatureC = 49.9f,
                gpuTemperatureC = 49.6f,
                skinTemperatureC = 45.1f,
                batteryTemperatureC = 31f,
            ),
            halResolved = true,
        )

        val values = tracker.lastHalValues()
        assertEquals(49.9f, values[TemperatureSensor.Cpu]!!, 0.001f)
        assertEquals(49.6f, values[TemperatureSensor.Gpu]!!, 0.001f)
        assertEquals(45.1f, values[TemperatureSensor.Skin]!!, 0.001f)
        assertFalse(TemperatureSensor.Battery in values)

        tracker.reset()
        assertTrue(tracker.lastHalValues().isEmpty())
    }

    @Test
    fun `sysfs wins per sensor and the HAL fills only the rest`() {
        val tracker = TemperatureTracker()

        val tracked = tracker.update(
            zones = TemperatureReading(cpuTemperatureC = 50f, skinTemperatureC = 44f),
            hal = TemperatureReading(
                cpuTemperatureC = 49.9f,
                gpuTemperatureC = 49.6f,
                skinTemperatureC = 45.1f,
                npuTemperatureC = 41f,
            ),
            halResolved = true,
        )

        assertEquals(50f, tracked.readings.cpuTemperatureC!!, 0.001f)
        assertEquals(44f, tracked.readings.skinTemperatureC!!, 0.001f)
        assertEquals(49.6f, tracked.readings.gpuTemperatureC!!, 0.001f)
        assertEquals(41f, tracked.readings.npuTemperatureC!!, 0.001f)
    }

    @Test
    fun `a readable kernel that stops reporting a sensor clears it`() {
        val tracker = TemperatureTracker()
        tracker.update(
            zones = TemperatureReading(skinTemperatureC = 44f),
            hal = null,
            halResolved = false,
        )

        val tracked = tracker.update(
            zones = TemperatureReading(),
            hal = null,
            halResolved = false,
        )

        assertNull(tracked.readings.skinTemperatureC)
    }

    @Test
    fun `reset forgets every run and the baseline`() {
        val tracker = TemperatureTracker()
        tracker.beginSession(mapOf(TemperatureSensor.Cpu to 49.888f))
        repeat(3) { tracker.update(null, frozenCpu, true) }
        assertTrue(TemperatureSensor.Cpu in tracker.update(null, frozenCpu, true).stale)

        tracker.reset()

        // The old baseline is gone and a new run starts counting from this sample.
        val tracked = tracker.update(null, frozenCpu, true)
        assertEquals(49.888f, tracked.readings.cpuTemperatureC!!, 0.001f)
        assertEquals(emptySet<TemperatureSensor>(), tracked.stale)
    }

    // --- captured device data ---------------------------------------------------------------

    @Test
    fun `replaying a frozen thermalservice three times hides CPU GPU and skin`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/device-thermalservice.txt"))
            .bufferedReader()
            .use { it.readText() }
        val hal = checkNotNull(StatsParsers.parseThermalservice(text))
        val tracker = TemperatureTracker()

        var tracked = tracker.update(null, hal, halResolved = true)
        tracked = tracker.update(null, hal, halResolved = true)
        tracked = tracker.update(null, hal, halResolved = true)

        assertNull(tracked.readings.cpuTemperatureC)
        assertNull(tracked.readings.gpuTemperatureC)
        assertNull(tracked.readings.skinTemperatureC)
        assertTrue(
            tracked.stale.containsAll(
                setOf(TemperatureSensor.Cpu, TemperatureSensor.Gpu, TemperatureSensor.Skin),
            ),
        )
    }

    @Test
    fun `replaying the thermalservice twice still shows the readings`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/device-thermalservice.txt"))
            .bufferedReader()
            .use { it.readText() }
        val hal = checkNotNull(StatsParsers.parseThermalservice(text))
        val tracker = TemperatureTracker()

        tracker.update(null, hal, halResolved = true)
        val tracked = tracker.update(null, hal, halResolved = true)

        assertEquals(49.888f, tracked.readings.cpuTemperatureC!!, 0.001f)
        assertEquals(49.628f, tracked.readings.gpuTemperatureC!!, 0.001f)
        assertEquals(45.125f, tracked.readings.skinTemperatureC!!, 0.001f)
    }

    @Test
    fun `a changing replay of the sensors shows them`() {
        val text = checkNotNull(javaClass.getResourceAsStream("/device-thermalservice.txt"))
            .bufferedReader()
            .use { it.readText() }
        val frozen = checkNotNull(StatsParsers.parseThermalservice(text))
        val tracker = TemperatureTracker()

        var tracked = tracker.update(null, frozen, halResolved = true)
        tracked = tracker.update(
            null,
            frozen.copy(
                cpuTemperatureC = frozen.cpuTemperatureC!! + 1f,
                gpuTemperatureC = frozen.gpuTemperatureC!! + 1f,
                skinTemperatureC = frozen.skinTemperatureC!! + 1f,
            ),
            halResolved = true,
        )
        tracked = tracker.update(null, frozen, halResolved = true)

        assertFalse(
            tracked.stale.any {
                it == TemperatureSensor.Cpu || it == TemperatureSensor.Gpu ||
                    it == TemperatureSensor.Skin
            },
        )
        assertEquals(49.888f, tracked.readings.cpuTemperatureC!!, 0.001f)
        assertEquals(49.628f, tracked.readings.gpuTemperatureC!!, 0.001f)
        assertEquals(45.125f, tracked.readings.skinTemperatureC!!, 0.001f)
    }
}
