package com.brightblu.parc.core.data.steps

import android.content.Context
import com.brightblu.parc.core.data.ledger.DayLedgerRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where counted steps go: the day ledger in the app, a fake in tests. */
internal interface StepSink {
    /** The steps saved for [date], or null when none were ever saved. */
    fun saved(date: LocalDate): Int?

    /** Adds [delta] to [date]'s steps (a day with none becomes [delta]). Saves at once. */
    suspend fun add(date: LocalDate, delta: Int)
}

/**
 * Counts steps from the phone's step counter into the day ledger (T187). Offline, no other app, no
 * service and no job: it reads the counter when [sample] is called (the 5-minute tick and every
 * ledger refresh of an open page) and while a page collects [watch].
 *
 * Each reading adds the difference to the last reading to the ledger day of the reading ([StepMath.delta]).
 * A reboot is recognised and nothing already counted is lost. Steps between two readings that straddle
 * midnight all go to the later day. Readings arrive step by step while the page is open, so the
 * additions are gathered in memory ([live]) and written to the ledger at most every [flushMillis], when
 * the date changes, and when [watch] stops. A process killed in between loses at most that gap.
 * The ledger is written before the new baseline, so a crash in between can only count a few steps twice,
 * never lose the earlier ones.
 */
class StepsRepository internal constructor(
    private val store: StepStore,
    private val source: StepSource,
    private val sink: StepSink,
    scope: CoroutineScope,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    private val flushMillis: Long = DefaultFlushMillis,
    private val tickMillis: Long = DefaultTickMillis,
    startTicker: Boolean = true,
) {
    /** What the page needs besides the ledger: the sensor and permission state and steps not yet saved. */
    data class Live(
        val sensor: Boolean? = null,
        val granted: Boolean = false,
        val pending: StepPending? = null,
    )

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    private val mutex = Mutex()
    private var baseline: StepReading? = null
    private var baselineLoaded = false
    private var lastFlushAt: Long? = null

    init {
        checkAccess()
        if (startTicker) {
            scope.launch {
                while (isActive) {
                    try {
                        sample()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (unexpected: Throwable) {
                        // One failed tick is a missing tick.
                    }
                    delay(tickMillis)
                }
            }
        }
    }

    /** Refreshes [Live.sensor] and [Live.granted] (cheap; the page calls it after the permission answer). */
    fun checkAccess() {
        _live.value = _live.value.copy(sensor = source.hasSensor(), granted = source.granted())
    }

    /** Reads the counter once and counts the difference. Does nothing without a sensor or the permission. */
    suspend fun sample() {
        checkAccess()
        val state = _live.value
        if (state.sensor != true || !state.granted) return
        val reading = withContext(Dispatchers.IO) { source.readOnce() } ?: return
        record(reading)
    }

    /** Counts every reading the sensor sends until cancelled; saves what is pending on the way out. */
    suspend fun watch() {
        checkAccess()
        val state = _live.value
        if (state.sensor != true || !state.granted) return
        try {
            source.updates().collect { record(it) }
        } finally {
            withContext(NonCancellable) { flush() }
        }
    }

    /** Writes the pending steps and the baseline now. */
    suspend fun flush() {
        mutex.withLock { flushLocked(clock()) }
    }

    internal suspend fun record(reading: StepReading) {
        mutex.withLock {
            val now = clock()
            loadBaselineLocked()
            val date = dateOf(now)
            val added = StepMath.delta(baseline, reading).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            var pending = _live.value.pending
            if (pending != null && pending.date != date) {
                // Midnight passed since the last reading: close the old day first, the new steps are the later day's.
                flushLocked(now)
                pending = null
            }
            val firstOfDay = pending == null && sink.saved(date) == null
            baseline = reading
            _live.value = _live.value.copy(pending = StepPending(date, (pending?.steps ?: 0) + added))
            val last = lastFlushAt
            if (firstOfDay || last == null || now < last || now - last >= flushMillis) flushLocked(now)
        }
    }

    private suspend fun loadBaselineLocked() {
        if (baselineLoaded) return
        baseline = try {
            StepReading.decode(store.read())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            null
        }
        baselineLoaded = true
    }

    private suspend fun flushLocked(now: Long) {
        val pending = _live.value.pending
        try {
            if (pending != null) {
                sink.add(pending.date, pending.steps)
                _live.value = _live.value.copy(pending = null)
            }
            baseline?.let { store.write(it.encode()) }
            lastFlushAt = now
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            // Kept in memory; the next flush tries again.
        }
    }

    private fun dateOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate()

    companion object {
        const val DefaultFlushMillis = 30_000L
        const val DefaultTickMillis = 5L * 60L * 1_000L

        /** Production factory; one instance per process. [startTicker] is false in demo mode. */
        fun create(context: Context, scope: CoroutineScope, ledger: DayLedgerRepository, startTicker: Boolean = true): StepsRepository {
            val app = context.applicationContext
            val sink = object : StepSink {
                override fun saved(date: LocalDate): Int? = ledger.ledger.value.day(date)?.steps
                override suspend fun add(date: LocalDate, delta: Int) {
                    ledger.update(date) { it.copy(steps = (it.steps ?: 0) + delta) }
                }
            }
            return StepsRepository(
                store = StepPrefs.create(app), source = AndroidStepSource(app), sink = sink, scope = scope,
                clock = System::currentTimeMillis, zone = { ZoneId.systemDefault() }, startTicker = startTicker,
            )
        }
    }
}
