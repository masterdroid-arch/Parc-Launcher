package com.brightblu.parc.core.data.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.brightblu.parc.model.AppId
import kotlin.math.abs

/** One foreground transition another component saw. Immutable, non-null; [ts] is epoch millis. */
internal data class UsageEvent(val packageName: String, val ts: Long)

/**
 * Optional enrichment source (PROJECT.md §6.3, decision 15): launches that did not come from Parc
 * (notifications, deep links, another launcher). Never required: returning null means "unavailable",
 * and the launcher works exactly the same without it.
 */
internal interface UsageStatsEnrichment {
    /**
     * Foreground events in `[sinceMillis, untilMillis)`, newest first, or null when the special
     * access is not granted or the platform refused the read. Never throws.
     */
    fun foregroundEvents(sinceMillis: Long, untilMillis: Long): List<UsageEvent>?
}

/**
 * Package-level helpers. A usage event only carries a package, while [AppId] is component-level
 * (T04: Instagram and Threads, and two activities of one package, are separate entries), so an event
 * is attributed to the first id of that package by id value. That is an approximation and is why
 * enrichment is optional: it can only ever add launches to an app Parc already knows.
 */
internal object PackageIds {

    /** Package name of an app id: `pkg/pkg.Class@0` -> `pkg`. */
    fun packageOf(appId: AppId): String =
        appId.value.substringBeforeLast('@').substringBefore('/')

    /** First id per package, deterministic by id value. */
    fun firstByPackage(ids: Collection<AppId>): Map<String, AppId> {
        val out = HashMap<String, AppId>()
        for (id in ids) {
            val pkg = packageOf(id)
            val known = out[pkg]
            if (known == null || id.value < known.value) out[pkg] = id
        }
        return out
    }
}

/**
 * Turns usage events plus Parc's own log into the launches Parc is missing. Pure and deterministic.
 *
 * An event within [MatchWindowMillis] of a launch we recorded for the same app is our own launch seen
 * a second time, so it is dropped: a Parc launch is never counted twice. Events for a package that no
 * installed app owns, and events beyond [maxPerRun], are dropped as well, so a corrupt or very busy
 * window cannot flood the table. The result is oldest first, ready to append.
 */
internal object EnrichedLaunches {

    const val MaxPerRun = 200

    /** An event this close to one of our own launches for the same app is that same launch. */
    const val MatchWindowMillis = 3_000L

    fun plan(
        events: List<UsageEvent>,
        ours: List<LaunchRow>,
        appIdForPackage: (String) -> AppId?,
        maxPerRun: Int = MaxPerRun,
    ): List<LaunchRow> {
        if (events.isEmpty() || maxPerRun <= 0) return emptyList()
        val ourLaunches = HashMap<AppId, MutableList<Long>>()
        for (row in ours) ourLaunches.getOrPut(row.appId) { ArrayList(4) } += row.ts

        val newestFirst = events.sortedWith(compareByDescending<UsageEvent> { it.ts }.thenBy { it.packageName })
        val perApp = LinkedHashMap<AppId, MutableList<Long>>()
        for (event in newestFirst) {
            val id = appIdForPackage(event.packageName) ?: continue
            perApp.getOrPut(id) { ArrayList(4) } += event.ts
        }

        val planned = ArrayList<LaunchRow>()
        for ((id, stamps) in perApp) {
            val unmatched = ourLaunches[id]?.toMutableList() ?: mutableListOf()
            for (ts in stamps) {
                val own = unmatched.indexOfFirst { abs(it - ts) <= MatchWindowMillis }
                if (own >= 0) {
                    unmatched.removeAt(own)
                    continue
                }
                planned += LaunchRow(id, ts)
            }
        }
        return planned
            .sortedWith(compareByDescending<LaunchRow> { it.ts }.thenBy { it.appId.value })
            .take(maxPerRun)
            .sortedWith(compareBy<LaunchRow> { it.ts }.thenBy { it.appId.value })
    }
}

/**
 * `UsageStatsManager` reader. The special access is an app op, not a runtime permission
 * (PROJECT.md §6.3), so the guard is the shared [UsageAccess] app-op check rather than
 * `checkSelfPermission`, and Parc declares no manifest permission for it. Dev grant:
 * `adb shell appops set com.brightblu.parc GET_USAGE_STATS allow`.
 *
 * UNVERIFIED on a device: that this appop read and `queryEvents` return events for a sideloaded
 * build that does not declare `PACKAGE_USAGE_STATS`. Every failure path returns null, so an
 * unverified platform answer can only disable enrichment.
 */
internal class AndroidUsageStatsEnrichment(private val context: Context) : UsageStatsEnrichment {

    override fun foregroundEvents(sinceMillis: Long, untilMillis: Long): List<UsageEvent>? {
        if (untilMillis <= sinceMillis || !UsageAccess.granted(context)) return null
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        return try {
            val events = manager.queryEvents(sinceMillis, untilMillis) ?: return null
            val out = ArrayList<UsageEvent>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    out += UsageEvent(event.packageName, event.timeStamp)
                }
            }
            out.sortedWith(compareByDescending<UsageEvent> { it.ts }.thenBy { it.packageName })
        } catch (t: Throwable) {
            null
        }
    }
}
