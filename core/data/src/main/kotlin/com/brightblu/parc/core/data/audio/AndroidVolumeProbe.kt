package com.brightblu.parc.core.data.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * `VolumeStream` to the legacy `AudioManager.STREAM_*` constants. These are the only public stream
 * constants the card's four sliders map to; the constants are compile-time ints, so the mapping is
 * unit-testable.
 */
internal object VolumeStreams {

    fun streamType(stream: VolumeStream): Int = when (stream) {
        VolumeStream.Media -> AudioManager.STREAM_MUSIC
        VolumeStream.Ring -> AudioManager.STREAM_RING
        VolumeStream.Alarm -> AudioManager.STREAM_ALARM
        VolumeStream.Call -> AudioManager.STREAM_VOICE_CALL
    }
}

/**
 * Production read probe. `getStreamVolume` / `getStreamMaxVolume` are binder calls on the main audio
 * service; [AndroidVolumeRepository] performs them on the IO dispatcher. A platform that answers a
 * negative max or an out-of-range index is treated as unreadable rather than clamped into a lie.
 *
 * `getDevices(GET_DEVICES_OUTPUTS)` lists connected routes and `getCommunicationDevice()` the
 * communication selection; neither is documented as "the active media route". [OutputDevices]
 * resolves a single display name from both and the limitation is reported.
 */
internal class AndroidVolumeProbe(context: Context) : VolumeProbe {

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    override fun level(stream: VolumeStream): StreamVolume? {
        val manager = audioManager ?: return null
        return try {
            val type = VolumeStreams.streamType(stream)
            val max = manager.getStreamMaxVolume(type)
            if (max < 0) return null
            val index = manager.getStreamVolume(type)
            if (index !in 0..max) return null
            StreamVolume(stream = stream, index = index, maxIndex = max)
        } catch (unexpected: Throwable) {
            null
        }
    }

    override fun outputDevices(): List<OutputDeviceSample> {
        val manager = audioManager ?: return emptyList()
        return try {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .mapNotNull(::sample)
        } catch (unexpected: Throwable) {
            emptyList()
        }
    }

    override fun communicationDevice(): OutputDeviceSample? {
        val manager = audioManager ?: return null
        return try {
            manager.communicationDevice?.let(::sample)
        } catch (unexpected: Throwable) {
            null
        }
    }

    private fun sample(device: AudioDeviceInfo): OutputDeviceSample? = try {
        OutputDeviceSample(
            kind = OutputKinds.fromType(device.type),
            productName = device.productName?.toString(),
        )
    } catch (unexpected: Throwable) {
        null
    }
}

/**
 * Production writer. Index bounds are re-checked here even though the repository clamps, because a
 * caller could hold an old `StreamVolume` across an audio-policy change. A fixed-volume device is a
 * refused write, and the read-back catches a policy refusal that did not throw.
 *
 * No `FLAG_SHOW_UI`: the Sound page draws its own slider, and the system panel would cover it.
 */
internal class AndroidStreamVolumeWriter(context: Context) : StreamVolumeWriter {

    private val audioManager: AudioManager? =
        context.applicationContext.getSystemService(AudioManager::class.java)

    override fun setVolume(stream: VolumeStream, index: Int): Boolean {
        val manager = audioManager ?: return false
        return try {
            if (manager.isVolumeFixed) return false
            val type = VolumeStreams.streamType(stream)
            val max = manager.getStreamMaxVolume(type)
            if (max < 0 || index !in 0..max) return false
            manager.setStreamVolume(type, index, 0)
            manager.getStreamVolume(type) == index
        } catch (unexpected: Throwable) {
            // SecurityException from a Do Not Disturb policy change, a dead service, or a vendor
            // refusal. The caller keeps the previous value instead of showing a move that did not
            // happen.
            false
        }
    }
}
