package com.brightblu.parc.core.data.apps

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.brightblu.parc.core.data.ParcDatabase
import com.brightblu.parc.core.data.usage.PackageIds
import com.brightblu.parc.core.data.usage.UsageAccess
import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** The app a hub double-tap should reopen (T49). All fields non-null; [foregroundAtMillis] is epoch ms. */
data class LastApp(
    val appId: AppId,
    val label: String,
    val packageName: String,
    val foregroundAtMillis: Long,
)

/**
 * Tracks "the app before Parc" (T47). [lastApp] is an immutable snapshot safe to read anywhere and
 * is null until an app is known. [onLaunched] is the zero-I/O hook for Parc's own launch path, so
 * the value is correct the moment the user leaves for an app. [refresh] folds in the Room launch
 * log and, when usage access is granted, `UsageEvents` foreground transitions seen outside Parc;
 * call it from a background dispatcher when Home resumes.
 *
 * Reading of the card: the tracker is queried while Parc is the current foreground (that is the only
 * time the hub exists), so "the previously launched app before the current foreground" is the newest
 * launchable, non-Parc app. If it were instead read while another app were current, the literal
 * predecessor would be one step further back; no Parc surface needs that, and it is noted in
 * `reports/T47.md`.
 */
interface LastAppTracker {
    val lastApp: StateFlow<LastApp?>

    /** Records a launch Parc just performed. Cheap enough for the main thread; does no I/O. */
    fun onLaunched(id: AppId)

    /** Re-reads the launch log and UsageEvents. Safe to call repeatedly; must run off the main thread. */
    suspend fun refresh()
}

/** One foreground transition, already reduced to a package and a time. Immutable, non-null. */
internal data class ForegroundMark(val packageName: String, val ts: Long)

/**
 * Thin `UsageEvents` boundary. Returns null when usage access is not granted or the platform
 * refuses the read; the tracker then keeps working from Parc's own log.
 *
 * `MOVE_TO_FOREGROUND` is the pre-API-29 name for the same event type as `ACTIVITY_RESUMED` (value
 * 1); current platforms report it under the latter name, which is what this reads.
 */
internal fun interface ForegroundEventQuery {
    fun events(sinceMillis: Long, untilMillis: Long): List<ForegroundMark>?
}

internal class AndroidForegroundEventQuery(private val context: Context) : ForegroundEventQuery {

    override fun events(sinceMillis: Long, untilMillis: Long): List<ForegroundMark>? {
        if (untilMillis <= sinceMillis || !UsageAccess.granted(context)) return null
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        return try {
            val events = manager.queryEvents(sinceMillis, untilMillis) ?: return null
            val out = ArrayList<ForegroundMark>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED) continue
                val packageName: String? = event.packageName
                if (packageName.isNullOrEmpty()) continue
                out += ForegroundMark(packageName, event.timeStamp)
            }
            out
        } catch (t: Throwable) {
            null
        }
    }
}

/**
 * Pure merge of the two sources. Sorting is newest first with a package tie-break so equal
 * timestamps are stable across runs; consecutive marks of the same package collapse, which is how
 * a Parc launch and the platform's own foreground event for it become one mark. The cap keeps the
 * in-memory list bounded. Own-package marks are kept here (they separate two uses of an app) and
 * removed by [LastAppResolver].
 */
internal object LastAppMarks {

    fun merge(
        newMarks: List<ForegroundMark>,
        existing: List<ForegroundMark>,
        limit: Int,
    ): List<ForegroundMark> {
        if (limit <= 0) return emptyList()
        val all = ArrayList<ForegroundMark>(existing.size + newMarks.size)
        all += existing
        all += newMarks
        all.sortWith(compareByDescending<ForegroundMark> { it.ts }.thenBy { it.packageName })
        val out = ArrayList<ForegroundMark>(minOf(limit, all.size))
        for (mark in all) {
            if (out.lastOrNull()?.packageName == mark.packageName) continue
            out += mark
            if (out.size >= limit) break
        }
        return out
    }
}

/** Pure selection: newest mark that is not Parc and maps to a launchable app. */
internal object LastAppResolver {

    fun resolve(
        marks: List<ForegroundMark>,
        appsByPackage: Map<String, AppEntry>,
        ownPackage: String,
    ): LastApp? {
        for (mark in marks) {
            if (mark.packageName == ownPackage) continue
            val app = appsByPackage[mark.packageName] ?: continue
            return LastApp(
                appId = app.id,
                label = app.label,
                packageName = mark.packageName,
                foregroundAtMillis = mark.ts,
            )
        }
        return null
    }
}

/**
 * Production [LastAppTracker]. No disk of its own: the Room launch log and the platform's event
 * stream are merged in memory, so there is no second source of truth to drift. A failed read can
 * only leave the previous value in place.
 *
 * The first refresh looks back [LookbackMillis]; later refreshes start at the previous watermark.
 * If usage access is unavailable the watermark does not advance, so granting the appop later still
 * makes the intervening events visible (bounded by [MaxLookbackMillis]).
 */
class AndroidLastAppTracker internal constructor(
    private val recentLaunches: suspend () -> List<ForegroundMark>,
    private val foregroundEvents: ForegroundEventQuery,
    private val installed: () -> List<AppEntry>,
    private val ownPackage: String,
    private val clock: () -> Long,
    private val ioDispatcher: CoroutineDispatcher,
) : LastAppTracker {

    private val lock = Any()
    private var marks: List<ForegroundMark> = emptyList()
    private var watermarkMillis: Long? = null
    private val _lastApp = MutableStateFlow<LastApp?>(null)

    override val lastApp: StateFlow<LastApp?> = _lastApp.asStateFlow()

    override fun onLaunched(id: AppId) {
        val mark = ForegroundMark(PackageIds.packageOf(id), clock())
        synchronized(lock) {
            marks = LastAppMarks.merge(listOf(mark), marks, MarkLimit)
        }
        publish()
    }

    override suspend fun refresh() = withContext(ioDispatcher) {
        val now = clock()
        val since = synchronized(lock) {
            val previous = watermarkMillis ?: (now - LookbackMillis)
            maxOf(previous, now - MaxLookbackMillis)
        }
        val until = if (now == Long.MAX_VALUE) now else now + 1L

        val fromLog = try {
            recentLaunches()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            emptyList()
        }
        val fromEvents = try {
            foregroundEvents.events(since, until)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            null
        }

        synchronized(lock) {
            marks = LastAppMarks.merge(fromLog + fromEvents.orEmpty(), marks, MarkLimit)
            if (fromEvents != null) watermarkMillis = now
        }
        publish()
    }

    private fun publish() {
        val snapshot = synchronized(lock) { marks }
        val apps = try {
            installed()
        } catch (unexpected: Throwable) {
            return
        }
        _lastApp.value = LastAppResolver.resolve(
            marks = snapshot,
            appsByPackage = AppPackages.byPackage(apps, ownPackage),
            ownPackage = ownPackage,
        )
    }

    companion object {
        /** Launch-log rows read per refresh. Enough for the last app; never the whole log. */
        const val LaunchLimit = 64

        /** In-memory marks kept; the value can never need more than a handful. */
        const val MarkLimit = 64

        /** How far back the first refresh looks for external foreground events. */
        const val LookbackMillis = 24L * 60L * 60L * 1000L

        /** Upper bound on the first refresh after a long period without usage access. */
        const val MaxLookbackMillis = 7L * 24L * 60L * 60L * 1000L

        /**
         * Builds the production tracker. [installed] is normally `appIndex.apps.value`; the Room
         * launch log is the same capped log T13 writes, so no new storage is introduced.
         */
        fun create(
            context: Context,
            installed: () -> List<AppEntry>,
        ): AndroidLastAppTracker {
            val appContext = context.applicationContext
            return AndroidLastAppTracker(
                recentLaunches = {
                    ParcDatabase.create(appContext)
                        .usageDao()
                        .recentLaunches(LaunchLimit)
                        .map { ForegroundMark(PackageIds.packageOf(AppId(it.appId)), it.ts) }
                },
                foregroundEvents = AndroidForegroundEventQuery(appContext),
                installed = installed,
                ownPackage = appContext.packageName,
                clock = System::currentTimeMillis,
                ioDispatcher = Dispatchers.IO,
            )
        }
    }
}
