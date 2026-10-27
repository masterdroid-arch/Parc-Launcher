package com.brightblu.parc.core.data.audio

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Production [VolumeRepository] (T52). Reads all four streams and the output name on [refresh]; a
 * failure of one stream leaves only that stream absent. Writes go through [StreamVolumeWriter], and
 * [state] changes only after a successful write, so the slider cannot drift away from the system.
 *
 * The repository is not a listener: another app or the hardware keys can change volume while the
 * page is open. T54 should call [refresh] when the Sound page resumes; no polling is done here.
 */
class AndroidVolumeRepository internal constructor(
    private val probe: VolumeProbe,
    private val writer: StreamVolumeWriter,
    private val ioDispatcher: CoroutineDispatcher,
) : VolumeRepository {

    private val mutableState = MutableStateFlow(VolumeState.Empty)
    override val state: StateFlow<VolumeState> = mutableState.asStateFlow()

    override suspend fun refresh() {
        withContext(ioDispatcher) {
            mutableState.value = readState()
        }
    }

    override fun setVolume(stream: VolumeStream, index: Int): Boolean {
        val current = mutableState.value.level(stream) ?: return false
        val target = index.coerceIn(0, current.maxIndex)
        val accepted = try {
            writer.setVolume(stream, target)
        } catch (unexpected: Throwable) {
            false
        }
        if (!accepted) return false
        mutableState.update { snapshot ->
            snapshot.withLevel(
                StreamVolume(stream = stream, index = target, maxIndex = current.maxIndex),
            )
        }
        return true
    }

    override fun setFraction(stream: VolumeStream, fraction: Float): Boolean {
        val current = mutableState.value.level(stream) ?: return false
        return setVolume(stream, VolumeScale.indexForFraction(fraction, current.maxIndex))
    }

    private fun readState(): VolumeState {
        val levels = ArrayList<StreamVolume>(VolumeStream.All.size)
        for (stream in VolumeStream.All) {
            val level = try {
                probe.level(stream)
            } catch (unexpected: Throwable) {
                null
            }
            if (level != null) levels += level
        }
        val outputs = try {
            probe.outputDevices()
        } catch (unexpected: Throwable) {
            emptyList()
        }
        val communication = try {
            probe.communicationDevice()
        } catch (unexpected: Throwable) {
            null
        }
        return VolumeState(
            levels = levels.toList(),
            outputDeviceName = OutputDevices.resolve(outputs, communication),
        )
    }

    private fun VolumeState.withLevel(level: StreamVolume): VolumeState {
        var replaced = false
        val next = levels.map { existing ->
            if (existing.stream == level.stream) {
                replaced = true
                level
            } else {
                existing
            }
        }
        return if (replaced) copy(levels = next) else this
    }

    companion object {
        /** Builds the production repository against the application AudioManager. */
        fun create(context: Context): AndroidVolumeRepository = AndroidVolumeRepository(
            probe = AndroidVolumeProbe(context.applicationContext),
            writer = AndroidStreamVolumeWriter(context.applicationContext),
            ioDispatcher = Dispatchers.IO,
        )
    }
}
