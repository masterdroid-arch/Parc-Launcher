package com.brightblu.parc.core.data.usage

import android.content.Context
import android.util.Log
import com.brightblu.parc.core.data.ParcDatabase
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.UsageRepository
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Production [UsageRepository] (PROJECT.md §6.3): Parc's own launch log is the source of truth, a
 * 7-day half-life decides the score, and `UsageStatsManager` is optional enrichment only.
 *
 * Folder order is **alphabetical by label, case-insensitive** (T36): usage ranking no longer re-ranks
 * the contents of a folder. Usage still drives the A2 learned slots, through
 * [com.brightblu.parc.core.data.usage.UsageLayerManager] and its own daily rule.
 *
 * Threading: [recordLaunch] is called on the main thread by the launch path and only touches memory
 * plus one background write; [recents] and [rank] are safe to read from any thread. All disk work
 * runs on [ioDispatcher]. [rank] reads the layout's folder membership, so the ring never waits for
 * I/O.
 *
 * Cold start ([loadAndRecompute], run once from the constructor): read the log, fold in optional
 * enrichment, then prune. Nothing here moves a slot.
 */
class UsageRepositoryImpl internal constructor(
    private val store: UsageStore,
    private val stateStore: UsageStateStore,
    private val folders: () -> List<Folder>,
    private val pinned: () -> Set<AppId>,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val enrichment: UsageStatsEnrichment?,
    private val appIdForPackage: () -> (String) -> AppId?,
    private val logWarn: (String) -> Unit,
    private val labels: () -> Map<AppId, String> = { emptyMap() },
) : UsageRepository, LaunchScoreSource {

    /** Newest-first, distinct app ids, capped at [RecentIds]; never includes a launch twice. */
    private val recentIds = MutableStateFlow<List<AppId>>(emptyList())

    @Volatile
    private var rows: List<LaunchRow> = emptyList()

    /** Read synchronously in the constructor: the first folder frame needs an order, not I/O. */
    @Volatile
    private var state: UsageRankState = stateStore.read()

    private val coldStart = scope.launch(ioDispatcher) { loadAndRecompute() }

    /**
     * Appends one launch. The in-memory log and the recents flow update immediately; the row is
     * written in the background. A lost write costs a launch of history, never the layout.
     */
    override fun recordLaunch(id: AppId) {
        val row = LaunchRow(id, clock())
        rows = (listOf(row) + rows).take(LoadLimit)
        publishRecents()
        scope.launch(ioDispatcher) { guarded("usage write failed") { store.record(row) } }
    }

    /** Newest first, distinct, without pinned apps (PROJECT.md §6.3: recents exclude pins). */
    override fun recents(limit: Int): Flow<List<AppId>> {
        val capped = limit.coerceAtLeast(0)
        return recentIds.map { ids -> ids.filter { it !in pinned() }.take(capped) }
    }

    /**
     * The folder's members in display order (T36): **alphabetical by label, case-insensitive**, so
     * the ring never re-ranks a folder's contents from usage. With no labels known (the default
     * provider) the stored membership order is used, which is what the seed writes and is already
     * alphabetical. Empty for a folder id the layout does not know.
     */
    override fun rank(folderId: FolderId): List<AppId> {
        val folder = folders().firstOrNull { it.id == folderId } ?: return emptyList()
        val byId = labels()
        if (byId.isEmpty()) return folder.appIds
        return folder.appIds.sortedWith(
            compareBy({ (byId[it] ?: it.value).lowercase() }, { it.value }),
        )
    }

    /** Decayed score of every app in the in-memory log (T34); the usage layer's input. */
    override fun scores(nowMillis: Long): Map<AppId, Double> = UsageRanking.scores(rows, nowMillis)

    /** Waits for the cold-start log read (T34). Failures are already logged by [loadAndRecompute]. */
    override suspend fun awaitLoaded() {
        coldStart.join()
    }

    /** One cold start: log, optional enrichment, then prune. Folder order is never usage-ranked (T36). */
    internal suspend fun loadAndRecompute() {
        guarded("usage read failed") { store.load(LoadLimit) }?.let { loaded ->
            rows = mergePending(loaded)
            publishRecents()
        }
        if (enrichment != null) {
            val current = enrich(state)
            if (current != state) {
                state = current
                guarded("usage state write failed") { stateStore.write(current) }
            }
        }
        guarded("usage prune failed") { store.prune(MaxRows) }
    }

    /** Cancels the cold-start job. The repository lives for the process lifetime. */
    fun close() {
        coldStart.cancel()
    }

    /**
     * Adds the foreground events the platform saw but our log does not contain, then advances the
     * watermark. Returns [current] unchanged when enrichment is unavailable or yields nothing.
     */
    private suspend fun enrich(current: UsageRankState): UsageRankState {
        val until = clock()
        val since = current.enrichmentWatermarkMillis ?: (until - EnrichmentWindowMillis)
        if (until <= since) return current
        val events = guarded("usage enrichment failed") {
            enrichment!!.foregroundEvents(since, until)
        } ?: return current
        val plan = EnrichedLaunches.plan(events, rows, appIdForPackage())
        if (plan.isNotEmpty()) {
            for (row in plan) guarded("usage write failed") { store.record(row) }
            rows = (plan + rows).sortedByDescending { it.ts }.take(LoadLimit)
            publishRecents()
        }
        return current.copy(enrichmentWatermarkMillis = until)
    }

    /**
     * Keeps a launch recorded between construction and the first read: those rows are newer than
     * anything the read returned, and the row is in the database (or lost) either way.
     */
    private fun mergePending(loaded: List<LaunchRow>): List<LaunchRow> {
        if (rows.isEmpty() || loaded.isEmpty()) return if (loaded.isEmpty()) rows else loaded
        val newestLoaded = loaded.first().ts
        return (rows.filter { it.ts > newestLoaded } + loaded).take(LoadLimit)
    }

    private fun publishRecents() {
        val seen = HashSet<AppId>(RecentIds * 2)
        val out = ArrayList<AppId>(RecentIds)
        for (row in rows) {
            if (out.size >= RecentIds) break
            if (seen.add(row.appId)) out += row.appId
        }
        recentIds.value = out
    }

    private inline fun <T> guarded(message: String, read: () -> T): T? =
        try {
            read()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (unexpected: Throwable) {
            logWarn(message)
            null
        }

    companion object {
        const val LogTag = "Parc"
        const val StateFileName = "usage_rank_state.json"

        /** Rows considered when scoring. The table itself keeps [MaxRows]. */
        const val LoadLimit = 5_000

        /** PROJECT.md §6.3: the log is capped. */
        const val MaxRows = 20_000

        /** Distinct recents kept in memory; the sheet asks for 12. */
        const val RecentIds = 32

        /** How far back the optional enrichment looks on a first run. */
        const val EnrichmentWindowMillis = 30L * UsageRanking.MillisPerDay

        /**
         * Builds the production repository for an app context.
         *
         * [installedIds] is only used by the optional enrichment, to attribute a package-level
         * foreground event to an installed app id; an empty provider simply disables that step.
         *
         * [labels] is the app id to label map the alphabetical folder order needs (T36). With no
         * provider the stored membership order is used instead, so a build that does not wire this
         * still shows the seed's alphabetical order.
         */
        fun create(
            context: Context,
            scope: CoroutineScope,
            folders: () -> List<Folder>,
            pinned: () -> Set<AppId>,
            installedIds: () -> List<AppId> = { emptyList() },
            labels: () -> Map<AppId, String> = { emptyMap() },
        ): UsageRepositoryImpl = UsageRepositoryImpl(
            store = RoomUsageStore(ParcDatabase.create(context).usageDao(), Dispatchers.IO),
            stateStore = FileUsageStateStore(File(context.applicationContext.filesDir, StateFileName)),
            folders = folders,
            pinned = pinned,
            clock = System::currentTimeMillis,
            scope = scope,
            ioDispatcher = Dispatchers.IO,
            enrichment = AndroidUsageStatsEnrichment(context.applicationContext),
            appIdForPackage = { PackageIds.firstByPackage(installedIds())::get },
            logWarn = { Log.d(LogTag, it) },
            labels = labels,
        )
    }
}
