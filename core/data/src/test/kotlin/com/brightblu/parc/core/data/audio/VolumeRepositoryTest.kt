package com.brightblu.parc.core.data.audio

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T52 volume repository: reads in display order, isolated failures, clamped writes, slider maths. */
class VolumeRepositoryTest {

    @Test
    fun `the state starts empty`() {
        val repository = volumeRepository()

        assertEquals(VolumeState.Empty, repository.state.value)
        assertTrue(repository.state.value.levels.isEmpty())
    }

    @Test
    fun `refresh reads media ring alarm and call in that order`() = runTest {
        val probe = FakeVolumeProbe().apply {
            levels[VolumeStream.Call] = streamLevel(VolumeStream.Call, 4, 6)
            levels[VolumeStream.Media] = streamLevel(VolumeStream.Media, 3, 15)
            levels[VolumeStream.Ring] = streamLevel(VolumeStream.Ring, 2, 7)
            levels[VolumeStream.Alarm] = streamLevel(VolumeStream.Alarm, 5, 7)
        }

        val repository = volumeRepository(probe)
        repository.refresh()

        assertEquals(
            listOf(VolumeStream.Media, VolumeStream.Ring, VolumeStream.Alarm, VolumeStream.Call),
            repository.state.value.levels.map { it.stream },
        )
        assertEquals(3, repository.state.value.level(VolumeStream.Media)!!.index)
        assertEquals(15, repository.state.value.level(VolumeStream.Media)!!.maxIndex)
        assertEquals(6, repository.state.value.level(VolumeStream.Call)!!.maxIndex)
        assertEquals(VolumeStream.All, probe.requested)
    }

    @Test
    fun `a stream the platform could not read is absent, not zero`() = runTest {
        val probe = FakeVolumeProbe().apply {
            levels[VolumeStream.Media] = streamLevel(VolumeStream.Media, 3, 15)
        }

        val repository = volumeRepository(probe)
        repository.refresh()

        assertEquals(1, repository.state.value.levels.size)
        assertNull(repository.state.value.level(VolumeStream.Call))
    }

    @Test
    fun `refresh names the preferred connected output device`() = runTest {
        val probe = FakeVolumeProbe().apply {
            levels[VolumeStream.Media] = streamLevel(VolumeStream.Media, 3, 15)
            outputs = listOf(
                OutputDeviceSample(OutputKind.Speaker, "Speaker"),
                OutputDeviceSample(OutputKind.Bluetooth, "Pixel Buds"),
            )
            communication = OutputDeviceSample(OutputKind.Earpiece, "Earpiece")
        }

        val repository = volumeRepository(probe)
        repository.refresh()

        assertEquals("Pixel Buds", repository.state.value.outputDeviceName)
    }

    @Test
    fun `device read failures do not hide the volume levels`() = runTest {
        val probe = FakeVolumeProbe().apply {
            levels[VolumeStream.Media] = streamLevel(VolumeStream.Media, 3, 15)
            throwOnOutputs = true
            throwOnCommunication = true
        }

        val repository = volumeRepository(probe)
        repository.refresh()

        assertEquals(1, repository.state.value.levels.size)
        assertNull(repository.state.value.outputDeviceName)
    }

    @Test
    fun `a refresh replaces an earlier snapshot`() = runTest {
        val probe = FakeVolumeProbe().apply {
            levels[VolumeStream.Media] = streamLevel(VolumeStream.Media, 3, 15)
        }
        val repository = volumeRepository(probe)
        repository.refresh()

        probe.levels[VolumeStream.Media] = streamLevel(VolumeStream.Media, 9, 15)
        repository.refresh()

        assertEquals(9, repository.state.value.level(VolumeStream.Media)!!.index)
    }

    @Test
    fun `setVolume clamps above the max and records the accepted index`() = runTest {
        val probe = FakeVolumeProbe().apply {
            levels[VolumeStream.Media] = streamLevel(VolumeStream.Media, 3, 15)
        }
        val writer = FakeVolumeWriter()
        val repository = volumeRepository(probe, writer)
        repository.refresh()

        val accepted = repository.setVolume(VolumeStream.Media, 99)

        assertTrue(accepted)
        assertEquals(listOf(VolumeStream.Media to 15), writer.calls)
        assertEquals(15, repository.state.value.level(VolumeStream.Media)!!.index)
    }

    @Test
    fun `setVolume clamps below zero`() = runTest {
        val probe = FakeVolumeProbe().apply {
            levels[VolumeStream.Ring] = streamLevel(VolumeStream.Ring, 4, 7)
        }
        val writer = FakeVolumeWriter()
        val repository = volumeRepository(probe, writer)
        repository.refresh()

        assertTrue(repository.setVolume(VolumeStream.Ring, -3))
        assertEquals(listOf(VolumeStream.Ring to 0), writer.calls)
        assertEquals(0, repository.state.value.level(VolumeStream.Ring)!!.index)
    }

    @Test
    fun `setVolume before a refresh is refused without touching the writer`() {
        val writer = FakeVolumeWriter()
        val repository = volumeRepository(writer = writer)

        assertFalse(repository.setVolume(VolumeStream.Media, 5))
        assertTrue(writer.calls.isEmpty())
    }

    @Test
    fun `a refused write leaves the previous value in place`() = runTest {
        val probe = FakeVolumeProbe().apply {
            levels[VolumeStream.Media] = streamLevel(VolumeStream.Media, 3, 15)
        }
        val writer = FakeVolumeWriter().apply { result = false }
        val repository = volumeRepository(probe, writer)
        repository.refresh()

        assertFalse(repository.setVolume(VolumeStream.Media, 9))
        assertEquals(3, repository.state.value.level(VolumeStream.Media)!!.index)
    }

    @Test
    fun `a throwing writer is a refused write, not a crash`() = runTest {
        val probe = FakeVolumeProbe().apply {
            levels[VolumeStream.Alarm] = streamLevel(VolumeStream.Alarm, 5, 7)
        }
        val writer = FakeVolumeWriter().apply { throwOnWrite = true }
        val repository = volumeRepository(probe, writer)
        repository.refresh()

        assertFalse(repository.setVolume(VolumeStream.Alarm, 6))
        assertEquals(5, repository.state.value.level(VolumeStream.Alarm)!!.index)
    }

    @Test
    fun `setFraction maps a slider position to the nearest step`() = runTest {
        val probe = FakeVolumeProbe().apply {
            levels[VolumeStream.Media] = streamLevel(VolumeStream.Media, 0, 10)
        }
        val writer = FakeVolumeWriter()
        val repository = volumeRepository(probe, writer)
        repository.refresh()

        assertTrue(repository.setFraction(VolumeStream.Media, 0f))
        assertTrue(repository.setFraction(VolumeStream.Media, 0.44f))
        assertTrue(repository.setFraction(VolumeStream.Media, 1f))

        assertEquals(
            listOf(VolumeStream.Media to 0, VolumeStream.Media to 4, VolumeStream.Media to 10),
            writer.calls,
        )
        assertEquals(10, repository.state.value.level(VolumeStream.Media)!!.index)
    }

    @Test
    fun `setFraction with a NaN or an unknown stream is safe`() = runTest {
        val probe = FakeVolumeProbe().apply {
            levels[VolumeStream.Media] = streamLevel(VolumeStream.Media, 3, 10)
        }
        val writer = FakeVolumeWriter()
        val repository = volumeRepository(probe, writer)
        repository.refresh()

        assertTrue(repository.setFraction(VolumeStream.Media, Float.NaN))
        assertFalse(repository.setFraction(VolumeStream.Call, 0.5f))
        assertEquals(listOf(VolumeStream.Media to 0), writer.calls)
    }

    @Test
    fun `one stream read failure does not stop the next stream`() = runTest {
        val probe = object : VolumeProbe {
            override fun level(stream: VolumeStream): StreamVolume? =
                if (stream == VolumeStream.Ring) error("vendor refusal") else streamLevel(stream, 1, 10)

            override fun outputDevices(): List<OutputDeviceSample> = emptyList()

            override fun communicationDevice(): OutputDeviceSample? = null
        }

        val repository = volumeRepository(probe)
        repository.refresh()

        assertEquals(
            listOf(VolumeStream.Media, VolumeStream.Alarm, VolumeStream.Call),
            repository.state.value.levels.map { it.stream },
        )
    }
}
