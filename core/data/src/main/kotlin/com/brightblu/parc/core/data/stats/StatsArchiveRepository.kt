package com.brightblu.parc.core.data.stats

import android.content.Context
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The T78 persisted stats history: an [archive] of the last 24 hours, sampled every 5 minutes while
 * the process lives and written to DataStore at most once per 15 minutes.
 *
 * Lifecycle: the ticker starts with the repository and runs on the supplied scope, which must be the
 * application process scope. There is no alarm, no foreground service, no receiver and no manifest
 * entry; the only wakeup is one coroutine `delay` every [DefaultSampleIntervalMillis], and it stops
 * when the scope is cancelled. Create exactly one instance per process (the factory uses one
 * DataStore file; two live instances would sample twice and race on the same document).
 *
 * Timing: the first sample happens as soon as the stored document has loaded, then one sample every
 * 5 minutes. A sample is appended to the in-memory archive immediately; the whole archive is written
 * when at least 15 minutes have passed since the last persisted write. The persisted write timestamp
 * is part of the document, so a restart too soon after a write does not write again early. Points
 * newer than the last write can be lost if the process dies, which is the price of not writing every
 * 5 minutes; the previously written 24-hour window is still restored.
 *
 * Failure: a throwing sample provider loses that one tick, never the loop; a failed DataStore write
 * is retried on the next sample because the in-memory write timestamp is only advanced on success.
 * A clock that moves backwards simply postpones the next write until it catches up.
 *
 * Threading: [archive] is a [StateFlow] safe to collect anywhere. The sampler is the only writer of
 * the archive after construction, so no extra lock is needed.
 */
class StatsArchiveRepository internal constructor(
    private val store: StatsArchiveStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val sample: suspend () -> StatsArchiveReading?,
    private val sampleIntervalMillis: Long = DefaultSampleIntervalMillis,
    private val writeIntervalMillis: Long = DefaultWriteIntervalMillis,
) {

    init {
        require(sampleIntervalMillis > 0L) { "Sample interval must be positive" }
        require(writeIntervalMillis >= sampleIntervalMillis) {
            "Write interval must not be shorter than the sample interval"
        }
    }

    private val _archive = MutableStateFlow(StatsArchive.Empty)

    /** The restored and live history, oldest first per metric. */
    val archive: StateFlow<StatsArchive> = _archive.asStateFlow()

    /** Epoch millis of the last successful write, or null when nothing has been written yet. */
    private var lastWriteAtMillis: Long? = null

    init {
        scope.launch {
            loadStored()
            while (isActive) {
                val now = clock()
                val reading = readSample()
                if (reading != null && !reading.isEmpty) {
                    _archive.value = _archive.value.record(now, reading)
                    maybePersist(now)
                }
                delay(sampleIntervalMillis)
            }
        }
    }

    /** Restores the previous document; a failed read is an empty start, not a crash. */
    private suspend fun loadStored() {
        val stored = try {
            store.load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            StoredStatsArchive()
        }
        _archive.value = stored.archive
        lastWriteAtMillis = stored.writtenAtMillis
    }

    /** One guarded sample; a failing probe is a missing tick. */
    private suspend fun readSample(): StatsArchiveReading? = try {
        sample()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (unexpected: Throwable) {
        null
    }

    /** Writes the whole archive when the 15-minute gate allows it. */
    private suspend fun maybePersist(nowMillis: Long) {
        val last = lastWriteAtMillis
        if (last != null && nowMillis - last < writeIntervalMillis) return
        val snapshot = _archive.value
        val saved = try {
            store.save(snapshot, nowMillis)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            false
        }
        if (saved) lastWriteAtMillis = nowMillis
    }

    companion object {
        /** T78: one history point every 5 minutes. */
        const val DefaultSampleIntervalMillis = 5L * 60L * 1_000L

        /** T78: the archive is persisted at most once per 15 minutes. */
        const val DefaultWriteIntervalMillis = 15L * 60L * 1_000L

        /**
         * Production factory. The probe reads only battery, memory, storage and TrafficStats, so the
         * history needs no permission and spawns no `dumpsys` process. The caller supplies the
         * application process scope and keeps the returned instance process-wide.
         */
        fun create(context: Context, scope: CoroutineScope): StatsArchiveRepository {
            val appContext = context.applicationContext
            val probe = AndroidStatsProbe(
                context = appContext,
                dumpsysRunner = ProcessDumpsysRunner(),
                forecastSeconds = 0,
            )
            return StatsArchiveRepository(
                store = StatsArchivePrefs.create(appContext),
                scope = scope,
                clock = System::currentTimeMillis,
                sample = {
                    StatsArchiveMapping.reading(
                        battery = probe.battery(),
                        memory = probe.memory(),
                        storage = probe.storage(),
                        network = probe.network(),
                    )
                },
            )
        }
    }
}
