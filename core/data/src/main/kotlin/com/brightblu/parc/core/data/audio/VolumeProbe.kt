package com.brightblu.parc.core.data.audio

/**
 * The Android boundary for volume reads (T52). Implementations must not throw: a missing
 * AudioManager, a refused binder call or a stream the vendor does not expose is null / an empty
 * list, and the repository hides only that value.
 *
 * Thread-safety: may be called from any thread; implementations must be re-entrant.
 */
internal interface VolumeProbe {

    fun level(stream: VolumeStream): StreamVolume?

    fun outputDevices(): List<OutputDeviceSample>

    fun communicationDevice(): OutputDeviceSample?
}

/**
 * The `AudioManager.setStreamVolume` write boundary. Returns true only when the platform accepted
 * the write; the production implementation also verifies the read-back, so a fixed-volume device or
 * a Do Not Disturb refusal cannot move Parc's slider while the system stays put.
 *
 * Kept as a separate one-method interface so a unit test can fake the writer without an AudioManager
 * and assert the exact index Parc sends (T52's "fake-friendly interface").
 */
internal fun interface StreamVolumeWriter {

    fun setVolume(stream: VolumeStream, index: Int): Boolean
}
