package com.brightblu.parc.core.data.usage

import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.FolderId
import kotlinx.serialization.Serializable

/**
 * Persisted ranking state (PROJECT.md §6.3). This is not the launch log; it is the decision the last
 * cold start made from it.
 *
 * - [ranked] is the order the ring is showing, per folder. Within an arc the order is stable
 *   (DESIGN.md §4), so it is stored rather than recomputed from scores: only a hysteresis decision
 *   can move an app to another bucket.
 * - [committed] is the arc bucket each app is displayed in, per folder. Folder-scoped because an app
 *   may be in several folders (PROJECT.md §3 decision 10) with a different rank in each.
 * - [pending] remembers the bucket a single cold start proposed but did not confirm, so the next
 *   daily recompute can see that the divergence lasted a second day.
 * - [lastRecomputeDay] is an epoch day (UTC); a second cold start on the same day does no work.
 * - [enrichmentWatermarkMillis] is the instant the optional `UsageStatsManager` enrichment last
 *   looked back from.
 *
 * Immutable, non-null; every string value is an id, never a label. No app names are ever stored.
 */
@Serializable
internal data class UsageRankState(
    val version: Int = SchemaVersion,
    val lastRecomputeDay: Long? = null,
    val ranked: Map<String, List<String>> = emptyMap(),
    val committed: Map<String, Map<String, Int>> = emptyMap(),
    val pending: Map<String, Map<String, Int>> = emptyMap(),
    val enrichmentWatermarkMillis: Long? = null,
) {
    /** Committed bucket of [appId] inside [folderId], or null when it has never been ranked. */
    fun committed(folderId: FolderId, appId: AppId): Int? = committed[folderId.value]?.get(appId.value)

    /** Bucket proposed by the previous recompute, or null when the app was not diverging then. */
    fun pending(folderId: FolderId, appId: AppId): Int? = pending[folderId.value]?.get(appId.value)

    companion object {
        /** Schema of this document. A different version is discarded instead of half-applied. */
        const val SchemaVersion = 1
    }
}
