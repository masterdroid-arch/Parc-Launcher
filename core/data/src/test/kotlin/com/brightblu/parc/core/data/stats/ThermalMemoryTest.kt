package com.brightblu.parc.core.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The DataStore payload for the T58 cross-start HAL baseline; no Android runtime needed. */
class ThermalMemoryTest {

    @Test
    fun `values round-trip in declaration order`() {
        val text = ThermalMemoryCodec.encode(
            mapOf(
                TemperatureSensor.Skin to 45.125f,
                TemperatureSensor.Cpu to 49.888f,
                TemperatureSensor.Gpu to 49.628f,
            ),
        )

        assertEquals("CPU=49.888;GPU=49.628;SKIN=45.125", text)
        assertEquals(
            mapOf(
                TemperatureSensor.Cpu to 49.888f,
                TemperatureSensor.Gpu to 49.628f,
                TemperatureSensor.Skin to 45.125f,
            ),
            ThermalMemoryCodec.decode(text),
        )
    }

    @Test
    fun `the battery is never stored`() {
        val text = ThermalMemoryCodec.encode(
            mapOf(
                TemperatureSensor.Cpu to 49.9f,
                TemperatureSensor.Battery to 31f,
            ),
        )

        assertEquals("CPU=49.9", text)
        assertTrue(TemperatureSensor.Battery !in ThermalMemoryCodec.decode(text))
    }

    @Test
    fun `malformed entries are skipped without losing the valid ones`() {
        val decoded = ThermalMemoryCodec.decode("CPU=49.9;junk;GPU=nope;SKIN=45.1;NOPE=1")

        assertEquals(
            mapOf(TemperatureSensor.Cpu to 49.9f, TemperatureSensor.Skin to 45.1f),
            decoded,
        )
    }

    @Test
    fun `an absent or blank payload is an empty baseline`() {
        assertEquals(emptyMap<TemperatureSensor, Float>(), ThermalMemoryCodec.decode(null))
        assertEquals(emptyMap<TemperatureSensor, Float>(), ThermalMemoryCodec.decode(""))
        assertEquals(emptyMap<TemperatureSensor, Float>(), ThermalMemoryCodec.decode("   "))
    }

    @Test
    fun `non finite values are dropped`() {
        val text = ThermalMemoryCodec.encode(
            mapOf(
                TemperatureSensor.Cpu to Float.NaN,
                TemperatureSensor.Gpu to Float.POSITIVE_INFINITY,
                TemperatureSensor.Skin to 45f,
            ),
        )

        assertEquals("SKIN=45.0", text)
        assertTrue(Float.NaN !in ThermalMemoryCodec.decode("CPU=NaN;GPU=Infinity").values)
    }

    @Test
    fun `only a baseline from an earlier process counts as a restart`() {
        assertTrue(
            ThermalMemoryCodec.isFromPreviousProcess(
                storedProcessStart = 1_000L,
                currentProcessStart = 2_000L,
            ),
        )
        assertFalse(
            ThermalMemoryCodec.isFromPreviousProcess(
                storedProcessStart = 2_000L,
                currentProcessStart = 2_000L,
            ),
        )
        assertFalse(
            ThermalMemoryCodec.isFromPreviousProcess(
                storedProcessStart = null,
                currentProcessStart = 2_000L,
            ),
        )
    }
}
