package com.brightblu.parc.core.data.usage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.brightblu.parc.core.data.LaunchLogEntity

/**
 * Parc's own launch log (PROJECT.md §6.3). The table is part of schema v1 (T05), so T13 adds queries
 * without a migration. Every method is suspend and reads newest first, with the autoincrement id as
 * the tie-break so two launches in the same millisecond still have a stable order.
 */
@Dao
internal interface UsageDao {

    @Insert
    suspend fun insertLaunch(row: LaunchLogEntity)

    @Query("SELECT * FROM launch_log ORDER BY ts DESC, id DESC LIMIT :limit")
    suspend fun recentLaunches(limit: Int): List<LaunchLogEntity>

    @Query(
        "DELETE FROM launch_log WHERE id NOT IN " +
            "(SELECT id FROM launch_log ORDER BY ts DESC, id DESC LIMIT :keep)",
    )
    suspend fun prune(keep: Int)
}
