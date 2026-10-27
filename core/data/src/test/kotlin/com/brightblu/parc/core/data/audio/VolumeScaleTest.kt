package com.brightblu.parc.core.data.audio

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure T52 slider maths and the stream-to-platform mapping. */
class VolumeScaleTest {

    @Test
    fun `fraction maps the index range to zero through one`() {
        assertEquals(0f, VolumeScale.fraction(0, 15), 0f)
        assertEquals(0.5f, VolumeScale.fraction(5, 10), 0f)
        assertEquals(1f, VolumeScale.fraction(15, 15), 0f)
    }

    @Test
    fun `fraction clamps an out-of-range index`() {
        assertEquals(0f, VolumeScale.fraction(-4, 10), 0f)
        assertEquals(1f, VolumeScale.fraction(40, 10), 0f)
    }

    @Test
    fun `fraction of a zero max is zero, never a division by zero`() {
        assertEquals(0f, VolumeScale.fraction(0, 0), 0f)
        assertEquals(0f, VolumeScale.fraction(5, -1), 0f)
    }

    @Test
    fun `indexForFraction rounds to the nearest step`() {
        assertEquals(0, VolumeScale.indexForFraction(0f, 10))
        assertEquals(4, VolumeScale.indexForFraction(0.44f, 10))
        assertEquals(5, VolumeScale.indexForFraction(0.46f, 10))
        assertEquals(5, VolumeScale.indexForFraction(0.5f, 10))
        assertEquals(10, VolumeScale.indexForFraction(1f, 10))
    }

    @Test
    fun `indexForFraction clamps positions outside the slider`() {
        assertEquals(0, VolumeScale.indexForFraction(-0.5f, 10))
        assertEquals(10, VolumeScale.indexForFraction(1.5f, 10))
    }

    @Test
    fun `indexForFraction rejects NaN and a zero max`() {
        assertEquals(0, VolumeScale.indexForFraction(Float.NaN, 10))
        assertEquals(0, VolumeScale.indexForFraction(0.7f, 0))
        assertEquals(0, VolumeScale.indexForFraction(0.7f, -3))
    }

    @Test
    fun `every index survives a fraction round trip`() {
        for (index in 0..15) {
            assertEquals(index, VolumeScale.indexForFraction(VolumeScale.fraction(index, 15), 15))
        }
    }

    @Test
    fun `the four streams map to the documented AudioManager stream types`() {
        assertEquals(AudioManager.STREAM_MUSIC, VolumeStreams.streamType(VolumeStream.Media))
        assertEquals(AudioManager.STREAM_RING, VolumeStreams.streamType(VolumeStream.Ring))
        assertEquals(AudioManager.STREAM_ALARM, VolumeStreams.streamType(VolumeStream.Alarm))
        assertEquals(AudioManager.STREAM_VOICE_CALL, VolumeStreams.streamType(VolumeStream.Call))
    }
}
