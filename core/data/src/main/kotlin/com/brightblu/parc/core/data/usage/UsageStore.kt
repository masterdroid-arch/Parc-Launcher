package com.brightblu.parc.core.data.usage

import com.brightblu.parc.core.data.LaunchLogEntity
import com.brightblu.parc.model.AppId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Rows-only boundary for the launch log. Everything above it works with [LaunchRow] and the model.
 * The log is Parc's own and is capped (PROJECT.md §6.3): no value in it is required for the app to
 * work, so a failure loses history and never blanks the ring.
 */
internal interface UsageStore {
    /** Newest first, at most [limit] rows. */
    suspend fun load(limit: Int): List<LaunchRow>

    /** Appends one launch. */
    suspend fun record(row: LaunchRow)

    /** Drops everything but the newest [keep] rows. */
    suspend fun prune(keep: Int)
}

/** Room-backed log. Never called on the main thread: the caller supplies the dispatcher. */
internal class RoomUsageStore(
    private val dao: UsageDao,
    private val dispatcher: CoroutineDispatcher,
) : UsageStore {

    override suspend fun load(limit: Int): List<LaunchRow> = withContext(dispatcher) {
        dao.recentLaunches(limit).map { LaunchRow(AppId(it.appId), it.ts) }
    }

    override suspend fun record(row: LaunchRow) = withContext(dispatcher) {
        dao.insertLaunch(LaunchLogEntity(appId = row.appId.value, ts = row.ts))
    }

    override suspend fun prune(keep: Int) = withContext(dispatcher) {
        dao.prune(keep)
    }
}
