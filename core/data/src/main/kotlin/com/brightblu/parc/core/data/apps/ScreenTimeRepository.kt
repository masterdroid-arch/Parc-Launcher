package com.brightblu.parc.core.data.apps

import android.content.Context
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Foreground time of one launchable app. UsageStats is package-level, so two activities of one
 * package share a row and [appId] is the deterministic first launchable entry of that package
 * (T04's ids are component-level). All durations are milliseconds; [last7DaysMillis] includes
 * today. Immutable and non-null.
 */
data class AppScreenTime(
    val appId: AppId,
    val label: String,
    val packageName: String,
    val todayMillis: Long,
    val last7DaysMillis: Long,
)

/**
 * The result of one screen-time read. [Unavailable] means usage access is not granted or the
 * platform refused the query: no figure is invented. [Available] may hold no entries, which is a
 * real "nothing recorded" answer and must be shown as such rather than as missing access.
 *
 * Entries are immutable and sorted by 7-day time, descending. `topToday` and `topLast7Days` re-sort
 * for their own windows and cap at five by default, as the card asks.
 */
sealed interface ScreenTimeResult {

    data object Unavailable : ScreenTimeResult

    data class Available(
        val entries: List<AppScreenTime>,
        val todayTotalMillis: Long,
        val last7DaysTotalMillis: Long,
    ) : ScreenTimeResult {

        /** Apps with time today, largest first, capped at [limit]. */
        fun topToday(limit: Int = ScreenTimeRepository.TopCount): List<AppScreenTime> =
            entries.filter { it.todayMillis > 0L }
                .sortedWith(TODAY_ORDER)
                .take(limit.coerceAtLeast(0))

        /** Apps in the 7-day window (today included), largest first, capped at [limit]. */
        fun topLast7Days(limit: Int = ScreenTimeRepository.TopCount): List<AppScreenTime> =
            entries.filter { it.last7DaysMillis > 0L }
                .sortedWith(WEEK_ORDER)
                .take(limit.coerceAtLeast(0))
    }
}

/**
 * Per-app foreground time (T47). [read] performs the `UsageStatsManager` query and must be called
 * off the main thread; the result is an immutable snapshot safe to publish. No caching is done
 * here: the Apps page reads when it opens, so a stale figure cannot outlive a refresh.
 */
interface ScreenTimeRepository {

    suspend fun read(): ScreenTimeResult

    companion object {
        /** The card's "top 5" list. */
        const val TopCount = 5

        /** The window is today plus the six preceding local days (7 calendar days in total). */
        const val WindowDays = 7
    }
}

private val WEEK_ORDER: Comparator<AppScreenTime> =
    compareByDescending<AppScreenTime> { it.last7DaysMillis }
        .thenByDescending { it.todayMillis }
        .thenBy { it.label.lowercase() }
        .thenBy { it.packageName }

private val TODAY_ORDER: Comparator<AppScreenTime> =
    compareByDescending<AppScreenTime> { it.todayMillis }
        .thenByDescending { it.last7DaysMillis }
        .thenBy { it.label.lowercase() }
        .thenBy { it.packageName }

/**
 * Production [ScreenTimeRepository]. `queryUsageStats` is asked twice with midnight-aligned bounds:
 * once for today and once for the seven-day window that starts six local days before today. The
 * two answers are summed per package and joined to launchable apps, so system processes and
 * packages Parc cannot launch never appear.
 *
 * Both readings of "today and the last 7 days" are listed in `reports/T47.md`; this class uses the
 * inclusive one (today is part of the 7-day total), which makes `last7DaysMillis >= todayMillis`
 * always hold.
 */
class AndroidScreenTimeRepository internal constructor(
    private val query: ForegroundUsageQuery,
    private val installed: () -> List<AppEntry>,
    private val ownPackage: String,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
    private val ioDispatcher: CoroutineDispatcher,
) : ScreenTimeRepository {

    override suspend fun read(): ScreenTimeResult = withContext(ioDispatcher) {
        val now = clock()
        val currentZone = zone()
        val todayStart = ScreenTimeWindows.startOfToday(now, currentZone)
        val windowStart = ScreenTimeWindows.windowStart(now, currentZone)
        val end = if (now == Long.MAX_VALUE) now else now + 1L

        val todayBuckets = guarded { query.buckets(todayStart, end) }
            ?: return@withContext ScreenTimeResult.Unavailable
        val weekBuckets = guarded { query.buckets(windowStart, end) }
            ?: return@withContext ScreenTimeResult.Unavailable

        val today = ScreenTimeMath.perPackage(todayBuckets, todayStart, end)
        val week = ScreenTimeMath.perPackage(weekBuckets, windowStart, end)
        val known = try {
            AppPackages.byPackage(installed(), ownPackage)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            emptyMap()
        }

        val entries = ArrayList<AppScreenTime>(week.size + today.size)
        for ((packageName, weekMillis) in week) {
            val app = known[packageName] ?: continue
            val todayMillis = today[packageName] ?: 0L
            entries += AppScreenTime(
                appId = app.id,
                label = app.label,
                packageName = packageName,
                todayMillis = todayMillis,
                last7DaysMillis = maxOf(weekMillis, todayMillis),
            )
        }
        // A partially answered platform query can know today but not the wider window; keep the
        // app visible instead of dropping its figure.
        for ((packageName, todayMillis) in today) {
            if (packageName in week) continue
            val app = known[packageName] ?: continue
            entries += AppScreenTime(
                appId = app.id,
                label = app.label,
                packageName = packageName,
                todayMillis = todayMillis,
                last7DaysMillis = todayMillis,
            )
        }
        entries.sortWith(WEEK_ORDER)

        val snapshot = entries.toList()
        ScreenTimeResult.Available(
            entries = snapshot,
            todayTotalMillis = snapshot.sumOf { it.todayMillis },
            last7DaysTotalMillis = snapshot.sumOf { it.last7DaysMillis },
        )
    }

    private inline fun <T> guarded(read: () -> T?): T? = try {
        read()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (unexpected: Throwable) {
        null
    }

    companion object {
        /** Builds the production repository. [installed] is normally `appIndex.apps.value`. */
        fun create(context: Context, installed: () -> List<AppEntry>): AndroidScreenTimeRepository =
            AndroidScreenTimeRepository(
                query = AndroidForegroundUsageQuery(context.applicationContext),
                installed = installed,
                ownPackage = context.packageName,
                clock = System::currentTimeMillis,
                zone = { ZoneId.systemDefault() },
                ioDispatcher = Dispatchers.IO,
            )
    }
}

/**
 * Local-calendar window maths, pure and DST-safe. A "day" starts at local midnight, so a fall-back
 * day is longer than 24 hours and the 7-day window can span 145 hours rather than 144.
 */
internal object ScreenTimeWindows {

    fun startOfToday(nowMillis: Long, zone: ZoneId): Long =
        localDate(nowMillis, zone).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Midnight of the first day in the inclusive seven-day window (today minus six days). */
    fun windowStart(nowMillis: Long, zone: ZoneId): Long =
        localDate(nowMillis, zone)
            .minusDays((ScreenTimeRepository.WindowDays - 1).toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    private fun localDate(nowMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
}

/**
 * Pure aggregation of platform buckets. Durations are summed per package as reported (the query
 * uses midnight-aligned bounds, so daily buckets sit inside the window); zero and negative
 * durations are ignored, as are buckets that do not overlap the window at all.
 */
internal object ScreenTimeMath {

    fun perPackage(
        buckets: List<ForegroundBucket>,
        windowStartMillis: Long,
        windowEndMillis: Long,
    ): Map<String, Long> {
        val totals = LinkedHashMap<String, Long>()
        for (bucket in buckets) {
            if (bucket.foregroundMillis <= 0L) continue
            if (bucket.lastTimeStampMillis <= windowStartMillis) continue
            if (bucket.firstTimeStampMillis >= windowEndMillis) continue
            totals[bucket.packageName] = (totals[bucket.packageName] ?: 0L) + bucket.foregroundMillis
        }
        return totals
    }
}
