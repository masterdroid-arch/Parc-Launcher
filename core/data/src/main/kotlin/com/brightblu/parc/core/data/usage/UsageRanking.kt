package com.brightblu.parc.core.data.usage

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.Folder
import com.brightblu.parc.model.FolderId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/** One recorded launch. Immutable, non-null; [ts] is epoch milliseconds. */
internal data class LaunchRow(val appId: AppId, val ts: Long)

/** One app that changed arc inside one folder on a recompute; the audit trail of the hysteresis. */
internal data class ArcMove(val folderId: FolderId, val appId: AppId, val fromBucket: Int, val toBucket: Int)

/** The outcome of one cold-start recompute: the new state plus what moved. */
internal data class RankRecompute(val state: UsageRankState, val moves: List<ArcMove>)

/**
 * Framework-free usage maths (PROJECT.md §6.3). No Android, no coroutines, no clock of its own:
 * every function is a deterministic snapshot transform, so ranking and hysteresis are unit-tested
 * against a fake clock instead of a real one.
 *
 * Score = sum over launches of `exp(-ln2 · age / halfLife)`, i.e. an exponentially decayed launch
 * count with a 7-day half-life. Buckets are the DESIGN.md §4 folder arcs: A1 = top 4, A2 = next 6,
 * A3 = next 8, A4 = the rest.
 */
internal object UsageRanking {

    /** PROJECT.md §6.3 half-life. */
    const val HalfLifeMillis = 7L * 24L * 60L * 60L * 1000L

    const val MillisPerDay = 24L * 60L * 60L * 1000L

    /** A move also needs a score gap above this fraction of the higher of the two scores. */
    const val GapThreshold = 0.20

    /** Arc capacities of DESIGN.md §4 for A1/A2/A3; A4 takes everything beyond. */
    val BucketSizes = intArrayOf(4, 6, 8)

    /** Sentinel for "never ranked"; sorts after every real bucket. */
    const val UnknownBucket = Int.MAX_VALUE

    /** Buckets the ring can display (A1..A4). */
    const val BucketCount = 4

    private val decayPerMilli = ln(2.0) / HalfLifeMillis

    /** Epoch day (UTC) of an instant. The recompute is daily, not per 24 h of uptime. */
    fun dayOf(nowMillis: Long): Long = Math.floorDiv(nowMillis, MillisPerDay)

    /**
     * True when no recompute has run on [today] yet. Only a cold start calls this, and a second
     * start on the same day returns false, which is what keeps a session's arcs still.
     */
    fun isDue(state: UsageRankState, today: Long): Boolean = state.lastRecomputeDay != today

    /** Exponentially decayed launch score; future timestamps (clock skew) count as age zero. */
    fun score(timestamps: List<Long>, nowMillis: Long): Double =
        timestamps.fold(0.0) { total, ts ->
            total + exp(-decayPerMilli * (nowMillis - ts).coerceAtLeast(0L))
        }

    /** Scores for every app that appears in [rows]; absent apps have no entry (treated as 0). */
    fun scores(rows: List<LaunchRow>, nowMillis: Long): Map<AppId, Double> {
        val timestamps = HashMap<AppId, MutableList<Long>>()
        for (row in rows) timestamps.getOrPut(row.appId) { ArrayList(4) } += row.ts
        return timestamps.mapValues { (_, values) -> score(values, nowMillis) }
    }

    /** Arc bucket of a rank position: 0..2 are A1..A3, every position beyond is A4 (index 3). */
    fun bucketOf(rank: Int): Int = when {
        rank < BucketSizes[0] -> 0
        rank < BucketSizes[0] + BucketSizes[1] -> 1
        rank < BucketSizes[0] + BucketSizes[1] + BucketSizes[2] -> 2
        else -> 3
    }

    /** First rank inside [bucket]; A4 is open ended, so its start is only a boundary marker. */
    fun bucketStart(bucket: Int): Int = when {
        bucket <= 0 -> 0
        bucket == 1 -> BucketSizes[0]
        bucket == 2 -> BucketSizes[0] + BucketSizes[1]
        else -> BucketSizes[0] + BucketSizes[1] + BucketSizes[2]
    }

    /** Relative difference between two decayed scores; 0 when there is nothing to compare. */
    fun relativeGap(a: Double, b: Double): Double {
        val high = max(a, b)
        return if (high <= 0.0) 0.0 else abs(a - b) / high
    }

    /**
     * What the folder's arcs would be if usage decided alone: score descending, then id ascending so
     * a tie is deterministic across runs.
     */
    fun candidateOrder(folder: Folder, scores: Map<AppId, Double>): List<AppId> =
        folder.appIds.distinct().sortedWith(
            compareByDescending<AppId> { scores[it] ?: 0.0 }.thenBy { it.value },
        )

    /**
     * One daily recompute of every folder, the only place a slot may change arc.
     *
     * Rules (PROJECT.md §6.3, DESIGN.md §4):
     * - An app seen for the first time is placed in its candidate bucket; there is no history to
     *   hold it back.
     * - An app whose candidate bucket differs from its committed bucket is left where it is and its
     *   candidate is remembered in [UsageRankState.pending]. It changes arc only when the next
     *   daily recompute still finds it diverged and it clearly beats the app at the top of the arc
     *   it is leaving (relative gap > [GapThreshold]).
     * - A pinned app never changes bucket inside a folder.
     * - A mover is appended to the end of its new bucket, so no other app's position changes by more
     *   than one slot (the neighbor ripple); the committed bucket is then read back from the final
     *   position, so the stored decision always describes what the ring draws.
     *
     * The caller must have checked [isDue]; this function always computes and stamps [today].
     */
    fun recompute(
        state: UsageRankState,
        folders: List<Folder>,
        scores: Map<AppId, Double>,
        pinned: Set<AppId>,
        today: Long,
    ): RankRecompute {
        val ranked = LinkedHashMap<String, List<String>>(folders.size)
        val committed = LinkedHashMap<String, Map<String, Int>>(folders.size)
        val pending = LinkedHashMap<String, Map<String, Int>>(folders.size)
        val moves = ArrayList<ArcMove>()

        for (folder in folders) {
            val candidate = candidateOrder(folder, scores)
            if (candidate.isEmpty()) {
                ranked[folder.id.value] = emptyList()
                committed[folder.id.value] = emptyMap()
                pending[folder.id.value] = emptyMap()
                continue
            }
            val candidateBucket = HashMap<AppId, Int>(candidate.size)
            candidate.forEachIndexed { index, id -> candidateBucket[id] = bucketOf(index) }

            val members = candidate.toHashSet()
            val stored = state.ranked[folder.id.value].orEmpty().map(::AppId).filter { it in members }
            val storedIds = stored.toHashSet()
            val fresh = candidate.filter { it !in storedIds }

            val bucketOfApp = HashMap<AppId, Int>(candidate.size)
            val relocated = HashSet<AppId>()
            val nextPending = HashMap<AppId, Int>()

            for (id in stored) {
                val target = candidateBucket.getValue(id)
                val previous = state.committed(folder.id, id)
                if (previous == null || id in pinned || previous == target) {
                    bucketOfApp[id] = previous ?: target
                    continue
                }
                val gap = boundaryGap(candidate, id, previous, target, scores)
                val divergedBefore = state.pending(folder.id, id) != null
                if (divergedBefore && gap > GapThreshold) {
                    bucketOfApp[id] = target
                    relocated += id
                    moves += ArcMove(folder.id, id, previous, target)
                } else {
                    bucketOfApp[id] = previous
                    nextPending[id] = target
                }
            }
            for (id in fresh) bucketOfApp[id] = candidateBucket.getValue(id)

            // Rebuild: residents keep their stored order; every confirmed mover (and every app seen
            // for the first time) is removed and put back at the last position of its target arc.
            // Inserting at the arc's far edge is the minimal-displacement choice: it shifts the apps
            // behind it by one slot and keeps the arc capacities exact, because positions are what
            // the ring slices, so the committed bucket is then read back from the final position.
            val order = ArrayList(stored)
            for (id in stored) {
                if (id !in relocated) continue
                val index = order.indexOf(id)
                if (index < 0) continue
                order.removeAt(index)
                order.add(insertIndex(order, candidateBucket.getValue(id)), id)
            }
            for (id in fresh) order.add(insertIndex(order, candidateBucket.getValue(id)), id)

            val committedByApp = HashMap<AppId, Int>(order.size)
            order.forEachIndexed { index, id -> committedByApp[id] = bucketOf(index) }

            ranked[folder.id.value] = order.map { it.value }
            committed[folder.id.value] = committedByApp.mapKeys { it.key.value }
            pending[folder.id.value] = nextPending.mapKeys { it.key.value }
        }

        return RankRecompute(
            state = state.copy(
                lastRecomputeDay = today,
                ranked = ranked,
                committed = committed,
                pending = pending,
            ),
            moves = moves,
        )
    }

    /**
     * How far above the boundary the app is: compared with the app at the top of the bucket it is
     * leaving, so a barely-crossing app waits a further day instead of flickering between arcs.
     */
    private fun boundaryGap(
        candidate: List<AppId>,
        id: AppId,
        previousBucket: Int,
        targetBucket: Int,
        scores: Map<AppId, Double>,
    ): Double {
        val index = candidate.indexOf(id)
        if (index < 0) return 0.0
        val deeper = max(previousBucket, targetBucket)
        val neighbour = (bucketStart(deeper) - 1).coerceIn(0, candidate.lastIndex)
        if (neighbour == index) return 0.0
        return relativeGap(scores[id] ?: 0.0, scores[candidate[neighbour]] ?: 0.0)
    }

    /** Last position of an arc; A4 is open ended, so its end is the end of the list. */
    private fun insertIndex(order: List<AppId>, targetBucket: Int): Int {
        val end = if (targetBucket >= BucketCount - 1) order.size else bucketStart(targetBucket + 1) - 1
        return end.coerceIn(0, order.size)
    }
}
