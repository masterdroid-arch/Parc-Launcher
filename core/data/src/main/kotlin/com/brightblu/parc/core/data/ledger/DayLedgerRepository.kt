package com.brightblu.parc.core.data.ledger

import android.content.Context
import com.brightblu.parc.core.data.power.ScreenOnTimeMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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

/**
 * Parc's own record of every calendar day (T185): one small [LedgerDay] per local date, kept on the
 * phone, included in the backup, and read by the Network page, the Screen time tile and the later
 * cards (Steps T187, Rhythm T188, Year T190, Today T192).
 *
 * How to use it from another card:
 * - Read: collect [ledger] and ask it, for example `ledger.range(from, to)` for the recorded days,
 *   or [LedgerMath.summary] for a period's sums. Days nobody recorded are absent, never zero.
 * - Write your own field: `updateToday { it.copy(steps = 4200) }` (or [update] for another date).
 *   Only the fields you change are touched; the live refresh never overwrites `steps`, `pickups` or
 *   `phoneFreeMillis`, and unknown fields survive.
 * - Do not write wifi, mobile, screen or the per-app maps, and do not set `closed`: [refresh] owns
 *   those and closes a day exactly once.
 *
 * Refresh: [refresh] rewrites today from Android's own numbers (never adds deltas, so a restart or
 * reboot cannot double count), closes every finished day from Android's whole-day numbers, and on
 * the first run backfills as far back as Android still answers. It runs when a page asks and on a
 * 5-minute coroutine tick while the process lives (no alarm, no service). A refresh is skipped when
 * the previous one is younger than [minRefreshMillis] unless forced.
 *
 * Storage: one JSON document in the `parc_day_ledger` DataStore. The stored text is the source of
 * truth, so a backup restore is picked up on the next refresh. Routine refreshes write at most every
 * [persistIntervalMillis]; closing a day, the backfill and [update] write at once.
 *
 * Threading: all changes are serialised by a mutex; Android reads run on [io]. [ledger] is safe to
 * collect anywhere.
 */
class DayLedgerRepository internal constructor(
    private val store: LedgerStore,
    private val source: LedgerSource,
    scope: CoroutineScope,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val minRefreshMillis: Long = DefaultMinRefreshMillis,
    private val persistIntervalMillis: Long = DefaultPersistIntervalMillis,
    private val tickMillis: Long = DefaultTickMillis,
    private val maxLookbackDays: Int = DefaultLookbackDays,
    startTicker: Boolean = true,
    /** M13 review: packages in locked folders. Their names and per-app numbers are never stored; totals still count them. */
    private val excludedPackages: () -> Set<String> = { emptySet() },
) {
    private val _ledger = MutableStateFlow(DayLedger.Empty)

    /** The current ledger. Empty until the stored document has been read. */
    val ledger: StateFlow<DayLedger> = _ledger.asStateFlow()

    private val _access = MutableStateFlow<Boolean?>(null)

    /** Whether usage access was granted at the last refresh; null before the first one. */
    val accessGranted: StateFlow<Boolean?> = _access.asStateFlow()

    private val mutex = Mutex()
    private var lastSeenText: String? = null
    private var loadedOnce = false
    private var lastRefreshAt: Long? = null
    private var lastPersistAt: Long? = null

    init {
        if (startTicker) {
            scope.launch {
                while (isActive) {
                    try {
                        refresh(force = true)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (unexpected: Throwable) {
                        // One failed tick is a missing tick, never the end of the loop.
                    }
                    delay(tickMillis)
                }
            }
        }
    }

    /** Today's local date in the phone's current time zone. */
    fun today(): LocalDate = dateOf(clock(), zone())

    /**
     * Brings the ledger up to date (see the class comment). Does nothing without usage access, except
     * that the stored document is still loaded so pages can show what was recorded before.
     */
    suspend fun refresh(force: Boolean = false) {
        mutex.withLock {
            val now = clock()
            var current = loadLocked()
            // Entries of apps that are now in a locked folder are removed from the stored record, with or without usage access.
            val cleaned = LedgerMath.withoutPackages(current, excludedPackages())
            if (cleaned !== current) {
                current = cleaned
                _ledger.value = cleaned
                persistLocked(cleaned, now, immediate = true)
            }
            val granted = withContext(io) { source.accessGranted() }
            _access.value = granted
            if (!granted) return
            val last = lastRefreshAt
            if (!force && last != null && now >= last && now - last < minRefreshMillis) return
            lastRefreshAt = now

            val before = current
            val next = withContext(io) { fill(current, now, zone()) }
            if (next == before) return
            _ledger.value = next
            val structural = next.backfilled != before.backfilled ||
                next.days.count { it.value.closed } != before.days.count { it.value.closed }
            persistLocked(next, now, immediate = structural)
        }
    }

    /** Changes the record of [date] (a new empty one if none), saves at once. See the class comment. */
    suspend fun update(date: LocalDate, change: (LedgerDay) -> LedgerDay) {
        mutex.withLock {
            val current = loadLocked()
            val day = current.day(date) ?: LedgerDay(date)
            val changed = change(day).copy(date = date)
            if (changed == day && current.day(date) != null) return
            val next = current.withDay(changed)
            _ledger.value = next
            persistLocked(next, clock(), immediate = true)
        }
    }

    /** [update] for today's date. */
    suspend fun updateToday(change: (LedgerDay) -> LedgerDay) = update(today(), change)

    /** The recorded days from [from] to [to] inclusive, oldest first. */
    fun range(from: LocalDate, to: LocalDate): List<LedgerDay> = _ledger.value.range(from, to)

    /** The sums of [period] as of today. */
    fun summary(period: LedgerPeriod): PeriodSummary = LedgerMath.summary(_ledger.value, period, today())

    // ---- internals ---------------------------------------------------------------------------

    /** Reads the stored text; keeps the in-memory ledger when the text is the one last seen. */
    private suspend fun loadLocked(): DayLedger {
        val text = try {
            withContext(io) { store.read() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            return _ledger.value
        }
        if (loadedOnce && text == lastSeenText) return _ledger.value
        val decoded = LedgerJson.decode(text)
        loadedOnce = true
        lastSeenText = text
        _ledger.value = decoded
        return decoded
    }

    private suspend fun persistLocked(ledger: DayLedger, now: Long, immediate: Boolean) {
        val last = lastPersistAt
        if (!immediate && last != null && now >= last && now - last < persistIntervalMillis) return
        val text = LedgerJson.encode(ledger)
        try {
            withContext(io) { store.write(text) }
            lastSeenText = text
            loadedOnce = true
            lastPersistAt = now
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            // Kept in memory; the next refresh writes again.
        }
    }

    /** The whole refresh as a pure-ish function of the ledger and Android's answers. */
    private fun fill(start: DayLedger, now: Long, z: ZoneId): DayLedger {
        val today = dateOf(now, z)
        var ledger = start
        if (!ledger.backfilled) {
            val dates = (maxLookbackDays downTo 1).map { today.minusDays(it.toLong()) }
            ledger = LedgerMath.mergeClosed(ledger, fetchClosedDays(dates, now, z)).copy(backfilled = true)
        } else {
            val pending = pendingDates(ledger, today)
            if (pending.isNotEmpty()) {
                ledger = LedgerMath.mergeClosed(ledger, fetchClosedDays(pending, now, z))
            }
        }
        ledger = writeToday(ledger, today, now, z)
        return LedgerMath.prune(ledger, today)
    }

    /** Days before today that still need closing: any gap after the last closed day, and open days. */
    private fun pendingDates(ledger: DayLedger, today: LocalDate): List<LocalDate> {
        val yesterday = today.minusDays(1)
        val lastClosed = ledger.days.values.filter { it.closed }.maxOfOrNull { it.date }
        val from = (lastClosed?.plusDays(1) ?: yesterday).let {
            val floor = today.minusDays(maxLookbackDays.toLong())
            if (it.isBefore(floor)) floor else it
        }
        val gap = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(yesterday) }.toList()
        val open = ledger.days.values.filter { !it.closed && it.date.isBefore(today) }.map { it.date }
        return (gap + open).distinct().sorted()
    }

    /**
     * Asks Android for each whole day in [dates] (all before today) and returns the days it could
     * answer, as unclosed records for [LedgerMath.mergeClosed] to close. A day Android cannot answer
     * is simply not in the result.
     */
    private fun fetchClosedDays(dates: List<LocalDate>, now: Long, z: ZoneId): List<LedgerDay> {
        if (dates.isEmpty()) return emptyList()
        val excluded = excludedPackages()
        val raws = ArrayList<LedgerBackfill.RawNetworkDay>()
        val foreground = HashMap<LocalDate, Map<String, Long>>()
        for (date in dates) {
            val (start, end) = bounds(date, z)
            source.network(start, end)?.let {
                raws += LedgerBackfill.RawNetworkDay(date, it.wifiBytes, it.mobileBytes, it.perPackage)
            }
            source.foreground(start, end)?.let { foreground[date] = it }
        }
        val network = LedgerBackfill.trimNetworkBackfill(raws).associateBy { it.date }
        val firstStart = bounds(dates.min(), z).first
        val events = source.screenEvents((firstStart - Day).coerceAtLeast(0L), now)
        val screen = events?.let { LedgerBackfill.screenOnPerDay(it, dates, z) }.orEmpty()
        return dates.mapNotNull { date ->
            val net = network[date]
            val screenMillis = screen[date]
            if (net == null && screenMillis == null) return@mapNotNull null
            LedgerDay(
                date = date,
                wifiBytes = net?.wifiBytes,
                mobileBytes = net?.mobileBytes,
                screenOnMillis = screenMillis,
                appData = LedgerMath.capMap(net?.perPackage.orEmpty().filterKeys { it !in excluded }, DayLedger.MaxAppsPerDay),
                appScreen = LedgerMath.capMap(foreground[date].orEmpty().filterKeys { it !in excluded }, DayLedger.MaxAppsPerDay),
            )
        }
    }

    /** Rewrites today's open record from Android; a closed record for the date is left alone. */
    private fun writeToday(ledger: DayLedger, today: LocalDate, now: Long, z: ZoneId): DayLedger {
        val existing = ledger.day(today)
        if (existing?.closed == true) return ledger
        val excluded = excludedPackages()
        val (start, _) = bounds(today, z)
        val end = maxOf(now + 1L, start + 1L)
        val net = source.network(start, end)
        val events = source.screenEvents((start - Day).coerceAtLeast(0L), end)
        val foreground = source.foreground(start, end)
        if (net == null && events == null && foreground == null) return ledger
        val base = existing ?: LedgerDay(today)
        val screen = events?.let { ScreenOnTimeMath.todayMillis(it, start, maxOf(now, start + 1L)) }
        return ledger.withDay(
            base.copy(
                wifiBytes = net?.wifiBytes ?: base.wifiBytes,
                mobileBytes = net?.mobileBytes ?: base.mobileBytes,
                screenOnMillis = screen ?: base.screenOnMillis,
                appData = net?.let { LedgerMath.capMap(it.perPackage.filterKeys { k -> k !in excluded }, DayLedger.MaxAppsPerDay) } ?: base.appData,
                appScreen = foreground?.let { LedgerMath.capMap(it.filterKeys { k -> k !in excluded }, DayLedger.MaxAppsPerDay) } ?: base.appScreen,
            ),
        )
    }

    private fun dateOf(millis: Long, z: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(z).toLocalDate()

    /** Local midnight to next local midnight; a day is 23 or 25 hours across a clock change. */
    private fun bounds(date: LocalDate, z: ZoneId): Pair<Long, Long> =
        date.atStartOfDay(z).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()

    companion object {
        const val DefaultMinRefreshMillis = 20_000L
        const val DefaultPersistIntervalMillis = 5L * 60L * 1_000L
        const val DefaultTickMillis = 5L * 60L * 1_000L

        /** How far back the first-run backfill asks. Android answers for far less; asking is cheap. */
        const val DefaultLookbackDays = 120
        private const val Day = 24L * 60L * 60L * 1_000L

        /**
         * A repository over fixed [ledger] data that never touches Android or the disk. For the debug
         * preview screen only: refresh answers nothing, so the data stays exactly as given.
         */
        fun preview(scope: CoroutineScope, ledger: DayLedger, accessGranted: Boolean = true): DayLedgerRepository {
            val memory = object : LedgerStore {
                var text: String? = LedgerJson.encode(ledger.copy(backfilled = true))
                override suspend fun read() = text
                override suspend fun write(text: String) { this.text = text }
            }
            val silent = object : LedgerSource {
                override fun accessGranted() = accessGranted
                override fun network(startMillis: Long, endMillis: Long): WindowNetwork? = null
                override fun screenEvents(fromMillis: Long, toMillis: Long): List<com.brightblu.parc.core.data.power.ScreenEvent>? = null
                override fun foreground(startMillis: Long, endMillis: Long): Map<String, Long>? = null
            }
            return DayLedgerRepository(
                store = memory, source = silent, scope = scope, clock = System::currentTimeMillis,
                zone = { ZoneId.systemDefault() }, startTicker = true,
            )
        }

        /** Production factory; [scope] is the application process scope. One instance per process. [startTicker] is false in demo mode. */
        fun create(context: Context, scope: CoroutineScope, startTicker: Boolean = true, excludedPackages: () -> Set<String> = { emptySet() }): DayLedgerRepository {
            val app = context.applicationContext
            return DayLedgerRepository(
                store = LedgerPrefs.create(app),
                source = AndroidLedgerSource(app),
                scope = scope,
                clock = System::currentTimeMillis,
                zone = { ZoneId.systemDefault() },
                startTicker = startTicker,
                excludedPackages = excludedPackages,
            )
        }
    }
}
