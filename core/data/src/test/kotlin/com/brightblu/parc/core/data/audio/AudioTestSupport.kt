package com.brightblu.parc.core.data.audio

import kotlinx.coroutines.Dispatchers

internal class FakeVolumeProbe : VolumeProbe {

    val levels = LinkedHashMap<VolumeStream, StreamVolume?>()

    var outputs: List<OutputDeviceSample> = emptyList()
    var communication: OutputDeviceSample? = null

    var throwOnOutputs = false
    var throwOnCommunication = false

    val requested = ArrayList<VolumeStream>()

    override fun level(stream: VolumeStream): StreamVolume? {
        requested += stream
        return levels[stream]
    }

    override fun outputDevices(): List<OutputDeviceSample> {
        if (throwOnOutputs) error("getDevices")
        return outputs
    }

    override fun communicationDevice(): OutputDeviceSample? {
        if (throwOnCommunication) error("getCommunicationDevice")
        return communication
    }
}

internal class FakeVolumeWriter : StreamVolumeWriter {

    val calls = ArrayList<Pair<VolumeStream, Int>>()

    var result = true
    var throwOnWrite = false

    override fun setVolume(stream: VolumeStream, index: Int): Boolean {
        calls += stream to index
        if (throwOnWrite) error("setStreamVolume")
        return result
    }
}

internal fun volumeRepository(
    probe: VolumeProbe = FakeVolumeProbe(),
    writer: StreamVolumeWriter = FakeVolumeWriter(),
): AndroidVolumeRepository = AndroidVolumeRepository(
    probe = probe,
    writer = writer,
    ioDispatcher = Dispatchers.Unconfined,
)

internal fun streamLevel(
    stream: VolumeStream,
    index: Int,
    maxIndex: Int,
): StreamVolume = StreamVolume(stream = stream, index = index, maxIndex = maxIndex)
