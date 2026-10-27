package com.brightblu.parc.core.data.audio

import android.media.AudioDeviceInfo

/**
 * Coarse output classes used only for naming and priority. `AudioDeviceInfo` has many constants; the
 * Sound page needs one short name, and the class captures what matters for choosing between the
 * connected routes. [priority] is lower for the route a phone normally prefers (external before
 * built-in), so the resolver is deterministic without platform ordering assumptions.
 */
internal enum class OutputKind(val priority: Int, val fallbackLabel: String) {
    Bluetooth(priority = 0, fallbackLabel = "Bluetooth"),
    Usb(priority = 1, fallbackLabel = "USB audio"),
    Wired(priority = 2, fallbackLabel = "Wired headphones"),
    HearingAid(priority = 3, fallbackLabel = "Hearing aid"),
    Hdmi(priority = 4, fallbackLabel = "HDMI"),
    Dock(priority = 5, fallbackLabel = "Dock"),
    Line(priority = 6, fallbackLabel = "Line out"),
    Speaker(priority = 7, fallbackLabel = "Speaker"),
    Earpiece(priority = 8, fallbackLabel = "Earpiece"),
    Other(priority = 9, fallbackLabel = "Audio output"),
}

/** One connected output device, neutral so the resolver is unit-tested without Android. */
internal data class OutputDeviceSample(
    val kind: OutputKind,
    val productName: String? = null,
)

/**
 * Chooses the name the Sound page shows. Neither `getDevices` nor `getCommunicationDevice` reports a
 * single documented "active media route": the first lists connected outputs and the second the
 * communication selection. This resolver therefore combines both, prefers external routes, then the
 * built-in speaker over the earpiece (the page's sliders are media-first), and falls back to a type
 * label when the product name is missing or the generic "unknown".
 *
 * This ordering is an inference, not a platform guarantee; it is called out in `reports/T52.md`.
 */
internal object OutputDevices {

    private val Order: Comparator<OutputDeviceSample> =
        compareBy<OutputDeviceSample> { it.kind.priority }
            .thenBy { it.productName.isNullOrBlank() }
            .thenBy { it.productName?.trim()?.lowercase() ?: "" }

    fun resolve(
        outputs: List<OutputDeviceSample>,
        communication: OutputDeviceSample?,
    ): String? {
        val candidates = outputs + listOfNotNull(communication)
        return candidates.minWithOrNull(Order)?.let(::name)
    }

    fun name(device: OutputDeviceSample): String {
        val product = device.productName?.trim()
        return if (product.isNullOrEmpty() || product.equals("unknown", ignoreCase = true)) {
            device.kind.fallbackLabel
        } else {
            product
        }
    }
}

/**
 * `AudioDeviceInfo.TYPE_*` to [OutputKind]. The constants are compile-time ints, so the mapping is
 * unit-testable. Input-only types never appear in `GET_DEVICES_OUTPUTS`; they land in [OutputKind.Other]
 * if a vendor returns one anyway.
 */
internal object OutputKinds {

    fun fromType(type: Int): OutputKind = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputKind.Speaker

        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputKind.Earpiece

        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputKind.Bluetooth

        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> OutputKind.Wired

        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.Usb

        AudioDeviceInfo.TYPE_HEARING_AID -> OutputKind.HearingAid

        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC -> OutputKind.Hdmi

        AudioDeviceInfo.TYPE_DOCK -> OutputKind.Dock

        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE -> OutputKind.Line

        else -> OutputKind.Other
    }
}
