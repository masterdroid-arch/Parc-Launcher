package com.brightblu.parc.core.data.audio

import kotlin.math.roundToInt
import kotlinx.coroutines.flow.StateFlow

/** The four streams the Sound page controls (T52), in display order. */
enum class VolumeStream {
    Media,
    Ring,
    Alarm,
    Call,
    ;

    companion object {
        /** Fixed display order; an enum value can never be forgotten by a caller iterating this. */
        val All: List<VolumeStream> = entries.toList()
    }
}

/**
 * One stream's current and maximum index, exactly as `AudioManager` reports them. [fraction] is the
 * derived 0..1 slider position; it is not stored, so a caller cannot round-trip a different index.
 *
 * The platform can report a minimum index above zero for some streams. The card asks for current
 * and max only, so the slider spans 0..max and a platform minimum is treated as the bottom of the
 * range rather than exposed as a separate token.
 */
data class StreamVolume(
    val stream: VolumeStream,
    val index: Int,
    val maxIndex: Int,
) {
    init {
        require(maxIndex >= 0) { "maxIndex must be non-negative" }
        require(index in 0..maxIndex) { "index must be inside 0..maxIndex" }
    }

    val fraction: Float get() = VolumeScale.fraction(index, maxIndex)
}

/**
 * One immutable Sound-page snapshot (T52). [levels] is in [VolumeStream.All] order for the streams
 * the platform answered; a stream that could not be read is simply absent, and the page shows it as
 * unavailable rather than as zero. [outputDeviceName] is null when no connected output device could
 * be identified; the resolver never invents a device from nothing.
 */
data class VolumeState(
    val levels: List<StreamVolume> = emptyList(),
    val outputDeviceName: String? = null,
) {
    fun level(stream: VolumeStream): StreamVolume? = levels.firstOrNull { it.stream == stream }

    companion object {
        /** The all-empty snapshot. */
        val Empty = VolumeState()
    }
}

/**
 * Thread-safe volume control. [state] starts empty; call [refresh] when the Sound page opens (or
 * resumes) to fill it. [setVolume] and [setFraction] write through `AudioManager.setStreamVolume`
 * and update [state] only when the platform accepted the write; a refused write (fixed-volume
 * device, Do Not Disturb policy, missing service) returns false and leaves the previous value.
 *
 * The write calls are intentionally not suspend: a slider step is one cheap binder call, and the
 * page must not queue a coroutine per drag frame. [refresh] does the multi-stream read off the main
 * thread.
 */
interface VolumeRepository {

    val state: StateFlow<VolumeState>

    suspend fun refresh()

    /** Sets [stream] to [index], clamped to its max. Returns whether the platform accepted it. */
    fun setVolume(stream: VolumeStream, index: Int): Boolean

    /** Sets [stream] from a 0..1 slider position. Returns false when the stream is not known yet. */
    fun setFraction(stream: VolumeStream, fraction: Float): Boolean
}

/**
 * Slider maths, pure and unit-tested. An index maps to a fraction by division, so the page can draw
 * a thumb without knowing the platform scale. A fraction maps back by rounding to the nearest step;
 * that is what makes one drag step produce exactly one haptic tick in T54.
 *
 * A zero max (fixed-volume speaker, a platform that answered with zero) is not a division by zero:
 * the fraction is 0 and every requested index is 0.
 */
object VolumeScale {

    fun fraction(index: Int, maxIndex: Int): Float =
        if (maxIndex <= 0) 0f else index.coerceIn(0, maxIndex).toFloat() / maxIndex.toFloat()

    fun indexForFraction(fraction: Float, maxIndex: Int): Int {
        if (maxIndex <= 0 || fraction.isNaN()) return 0
        return (fraction.coerceIn(0f, 1f) * maxIndex.toFloat())
            .roundToInt()
            .coerceIn(0, maxIndex)
    }
}
