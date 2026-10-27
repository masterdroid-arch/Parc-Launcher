package com.brightblu.parc.core.data.apps

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import com.brightblu.parc.core.data.usage.UsageAccess

/**
 * One platform usage bucket, reduced to the fields T47 consumes. [firstTimeStampMillis] and
 * [lastTimeStampMillis] delimit the interval the bucket describes; [foregroundMillis] is
 * `UsageStats.totalTimeInForeground` for that interval. Neutral so the aggregation is unit-tested
 * without Android.
 */
internal data class ForegroundBucket(
    val packageName: String,
    val firstTimeStampMillis: Long,
    val lastTimeStampMillis: Long,
    val foregroundMillis: Long,
)

/**
 * Thin `UsageStatsManager` boundary for screen time. Returns null when usage access is not granted
 * or the platform refuses the read, which the repository turns into `Unavailable`; an empty list is
 * a valid "nothing recorded" answer and must not be confused with a missing grant.
 */
internal fun interface ForegroundUsageQuery {
    fun buckets(beginMillis: Long, endMillis: Long): List<ForegroundBucket>?
}

/**
 * `queryUsageStats(INTERVAL_DAILY, …)` reader. The special access is an app op, so this declares no
 * permission and checks [UsageAccess] on every call.
 *
 * UNVERIFIED on a device: that a sideloaded build with the appop grant receives daily buckets whose
 * boundaries sit on local midnights. The repository asks with midnight-aligned bounds, and the
 * aggregation sums the reported totals without inventing a proportional split; if the platform
 * instead returns one aggregate for the whole range, the 7-day list would still be right and the
 * "today" list would under-report. T54's emulator pass can confirm the shape.
 */
internal class AndroidForegroundUsageQuery(private val context: Context) : ForegroundUsageQuery {

    override fun buckets(beginMillis: Long, endMillis: Long): List<ForegroundBucket>? {
        if (endMillis <= beginMillis || !UsageAccess.granted(context)) return null
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        return try {
            val stats = manager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                beginMillis,
                endMillis,
            ) ?: return null
            stats.map { it.toBucket() }
        } catch (t: Throwable) {
            null
        }
    }

    private fun UsageStats.toBucket(): ForegroundBucket = ForegroundBucket(
        packageName = packageName,
        firstTimeStampMillis = firstTimeStamp,
        lastTimeStampMillis = lastTimeStamp,
        foregroundMillis = totalTimeInForeground,
    )
}
