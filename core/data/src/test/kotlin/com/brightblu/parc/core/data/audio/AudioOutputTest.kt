package com.brightblu.parc.core.data.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure T52 output-device naming and the AudioDeviceInfo type mapping. */
class AudioOutputTest {

    @Test
    fun `bluetooth wins over every other connected route`() {
        val name = OutputDevices.resolve(
            outputs = listOf(
                OutputDeviceSample(OutputKind.Speaker, "Speaker"),
                OutputDeviceSample(OutputKind.Wired, "Wired headphones"),
                OutputDeviceSample(OutputKind.Bluetooth, "Pixel Buds"),
            ),
            communication = null,
        )

        assertEquals("Pixel Buds", name)
    }

    @Test
    fun `wired wins over the built-in speaker`() {
        val name = OutputDevices.resolve(
            outputs = listOf(
                OutputDeviceSample(OutputKind.Speaker, "Speaker"),
                OutputDeviceSample(OutputKind.Wired, "Wired headphones"),
            ),
            communication = OutputDeviceSample(OutputKind.Earpiece, "Earpiece"),
        )

        assertEquals("Wired headphones", name)
    }

    @Test
    fun `the speaker wins over the earpiece when nothing is plugged in`() {
        val name = OutputDevices.resolve(
            outputs = listOf(
                OutputDeviceSample(OutputKind.Earpiece, "Earpiece"),
                OutputDeviceSample(OutputKind.Speaker, "Speaker"),
            ),
            communication = OutputDeviceSample(OutputKind.Earpiece, "Earpiece"),
        )

        assertEquals("Speaker", name)
    }

    @Test
    fun `the communication device is a fallback when getDevices is empty`() {
        val name = OutputDevices.resolve(
            outputs = emptyList(),
            communication = OutputDeviceSample(OutputKind.Bluetooth, "Car kit"),
        )

        assertEquals("Car kit", name)
    }

    @Test
    fun `no devices means no invented name`() {
        assertNull(OutputDevices.resolve(emptyList(), null))
    }

    @Test
    fun `product names are trimmed`() {
        assertEquals(
            "Pixel Buds",
            OutputDevices.name(OutputDeviceSample(OutputKind.Bluetooth, "  Pixel Buds  ")),
        )
    }

    @Test
    fun `a blank product name falls back to the type label`() {
        assertEquals("Speaker", OutputDevices.name(OutputDeviceSample(OutputKind.Speaker, "   ")))
        assertEquals("USB audio", OutputDevices.name(OutputDeviceSample(OutputKind.Usb, null)))
    }

    @Test
    fun `the generic unknown product name falls back to the type label`() {
        assertEquals(
            "Bluetooth",
            OutputDevices.name(OutputDeviceSample(OutputKind.Bluetooth, "unknown")),
        )
    }

    @Test
    fun `the same kind is ordered by product name so the choice is stable`() {
        val name = OutputDevices.resolve(
            outputs = listOf(
                OutputDeviceSample(OutputKind.Bluetooth, "Zeta Buds"),
                OutputDeviceSample(OutputKind.Bluetooth, "Alpha Buds"),
            ),
            communication = null,
        )

        assertEquals("Alpha Buds", name)
    }

    @Test
    fun `built-in output types map to speaker and earpiece`() {
        assertEquals(OutputKind.Speaker, OutputKinds.fromType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(
            OutputKind.Speaker,
            OutputKinds.fromType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE),
        )
        assertEquals(OutputKind.Earpiece, OutputKinds.fromType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
    }

    @Test
    fun `wireless output types map together`() {
        assertEquals(OutputKind.Bluetooth, OutputKinds.fromType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals(OutputKind.Bluetooth, OutputKinds.fromType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(OutputKind.Bluetooth, OutputKinds.fromType(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(OutputKind.Bluetooth, OutputKinds.fromType(AudioDeviceInfo.TYPE_BLE_SPEAKER))
        assertEquals(OutputKind.HearingAid, OutputKinds.fromType(AudioDeviceInfo.TYPE_HEARING_AID))
    }

    @Test
    fun `wired USB HDMI dock and line types map to their classes`() {
        assertEquals(OutputKind.Wired, OutputKinds.fromType(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(OutputKind.Wired, OutputKinds.fromType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(OutputKind.Usb, OutputKinds.fromType(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(OutputKind.Usb, OutputKinds.fromType(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(OutputKind.Hdmi, OutputKinds.fromType(AudioDeviceInfo.TYPE_HDMI))
        assertEquals(OutputKind.Dock, OutputKinds.fromType(AudioDeviceInfo.TYPE_DOCK))
        assertEquals(OutputKind.Line, OutputKinds.fromType(AudioDeviceInfo.TYPE_LINE_ANALOG))
    }

    @Test
    fun `an unknown type falls into Other instead of guessing`() {
        assertEquals(OutputKind.Other, OutputKinds.fromType(AudioDeviceInfo.TYPE_UNKNOWN))
        assertEquals(OutputKind.Other, OutputKinds.fromType(Int.MAX_VALUE))
    }
}
