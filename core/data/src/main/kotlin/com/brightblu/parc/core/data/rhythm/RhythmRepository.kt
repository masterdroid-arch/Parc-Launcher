package com.brightblu.parc.core.data.rhythm

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.brightblu.parc.core.data.ledger.DayLedgerRepository
import com.brightblu.parc.core.data.usage.UsageAccess
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The phone's usage events, as the repository sees them. A fake stands in for it in tests. */
internal interface RhythmSource {
    fun accessGranted(): Boolean

    /** Screen and keyguard events in [fromMillis, toMillis], or null when the platform did not answer. */
    fun events(fromMillis: Long, toMillis: Long): List<RhythmEvent>?
}

/** What the repository writes for one day. The ledger is the only store; nothing else is kept. */
data class RhythmRecord(
    val pickups: Int,
    val phoneFreeMillis: Long?,
    val firstPickupMinute: Int?,
    val bins: String,
)

/** Where the day records go: the day ledger in the app, a fake in tests. */
internal interface RhythmSink {
    /** Replaces the rhythm fields of [date]; other fields of the day are left alone. */
    suspend fun write(date: LocalDate, record: RhythmRecord)
}

internal class AndroidRhythmSource(context: Context) : RhythmSource {
    private val app = context.applicationContext

    override fun accessGranted(): Boolean = UsageAccess.granted(app)

    override fun events(fromMillis: Long, toMillis: Long): List<RhythmEvent>? {
        if (toMillis <= fromMillis || !accessGranted()) return null
        val manager = app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        return try {
            val usage = manager.queryEvents(fromMillis.coerceAtLeast(0L), toMillis) ?: return null
            val out = ArrayList<RhythmEvent>()
            val event = UsageEvents.Event()
            while (usage.hasNextEvent()) {
                if (!usage.getNextEvent(event)) break
                when (event.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE -> out += RhythmEvent(RhythmEvent.Kind.ScreenOn, event.timeStamp)
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> out += RhythmEvent(RhythmEvent.Kind.ScreenOff, event.timeStamp)
                    UsageEvents.Event.KEYGUARD_HIDDEN -> out += RhythmEvent(RhythmEvent.Kind.KeyguardHidden, event.timeStamp)
                }
            }
            out
        } catch (unexpected: Throwable) {
            null
        }
    }
}

/**
 * Turns the phone's usage events into the day's rhythm and writes it into the day ledger (T188). Android keeps
 * the events for only a few days, so every sample recomputes the days Android still covers (today always, an
 * earlier day only when the oldest event Android returned is older than that day's midnight, so a day that is
 * only partly covered is never written as if whole) and writes them. A day that ages out of Android keeps the
 * last numbers written. A write that changes nothing is skipped by the ledger. No service, no job, no alarm:
 * [sample] runs on a 5-minute tick while the process lives and on the Rhythm page's own refresh.
 */
class RhythmRepository internal constructor(
    private val source: RhythmSource,
    private val sink: RhythmSink,
    scope: CoroutineScope,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    private val options: suspend () -> RhythmOptions,
    private val tickMillis: Long = DefaultTickMillis,
    startTicker: Boolean = true,
) {
    private val _access = MutableStateFlow<Boolean?>(null)

    /** Whether usage access was held at the last sample; null before the first. */
    val accessGranted: StateFlow<Boolean?> = _access.asStateFlow()

    private val mutex = Mutex()

    init {
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

    /** Recomputes the days Android still covers and writes them. Does nothing without usage access. */
    suspend fun sample() {
        mutex.withLock {
            val granted = withContext(Dispatchers.IO) { source.accessGranted() }
            _access.value = granted
            if (!granted) return
            val now = clock()
            val z = zone()
            val today = Instant.ofEpochMilli(now).atZone(z).toLocalDate()
            val first = today.minusDays(DaysChecked.toLong())
            val from = first.atStartOfDay(z).toInstant().toEpochMilli() - DayMillis
            val events = withContext(Dispatchers.IO) { source.events(from, now) } ?: return
            if (events.isEmpty()) return
            val oldest = events.minOf { it.atMillis }
            val opts = options()
            var date = first
            while (!date.isAfter(today)) {
                val start = date.atStartOfDay(z).toInstant().toEpochMilli()
                if (date == today || oldest <= start) {
                    val day = RhythmMath.day(events, date, z, opts, now)
                    sink.write(date, RhythmRecord(day.pickups, day.longestFreeMillis, day.firstPickupMinute, RhythmBins.encode(day.bins)))
                }
                date = date.plusDays(1)
            }
        }
    }

    companion object {
        const val DefaultTickMillis = 5L * 60L * 1_000L
        /** How many days before today a sample looks at; Android keeps events for about a week. */
        const val DaysChecked = 6
        private const val DayMillis = 24L * 60L * 60L * 1_000L

        /** Production factory; one instance per process. [startTicker] is false in demo mode. */
        fun create(
            context: Context, scope: CoroutineScope, ledger: DayLedgerRepository,
            options: suspend () -> RhythmOptions, startTicker: Boolean = true,
        ): RhythmRepository {
            val sink = object : RhythmSink {
                override suspend fun write(date: LocalDate, record: RhythmRecord) {
                    ledger.update(date) {
                        it.copy(
                            pickups = record.pickups,
                            // A day whose window has not started yet has no stretch; keep an older value rather than blank it.
                            phoneFreeMillis = record.phoneFreeMillis ?: it.phoneFreeMillis,
                            firstPickupMinute = record.firstPickupMinute,
                            rhythmBins = record.bins,
                        )
                    }
                }
            }
            return RhythmRepository(
                source = AndroidRhythmSource(context), sink = sink, scope = scope, clock = System::currentTimeMillis,
                zone = { ZoneId.systemDefault() }, options = options, startTicker = startTicker,
            )
        }
    }
}
